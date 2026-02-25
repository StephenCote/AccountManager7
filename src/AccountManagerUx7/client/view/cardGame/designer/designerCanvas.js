/**
 * CardGame Designer — Designer Canvas
 * Zone-based visual card layout editor.
 * Users can add/remove/reorder elements within zones, adjust zone heights,
 * and edit element styles. Includes property panel and element list sidebar.
 *
 * Depends on:
 *   - window.CardGame.Constants (CARD_TYPES, CARD_SIZES, LAYOUT_ZONES, LAYOUT_ELEMENT_TYPES)
 *   - window.CardGame.Designer.LayoutConfig (getLayout, saveLayout, cloneLayout, addElement,
 *       removeElement, moveElement, findElement, updateElementStyle, updateZoneHeight)
 *   - window.CardGame.Designer.LayoutRenderer (LayoutCardFace)
 *   - window.CardGame.Designer.IconPicker (IconPickerButton, IconPickerOverlay)
 *   - window.CardGame.Storage (deckStorage)
 *
 * Exposes: window.CardGame.Designer.DesignerCanvas
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Designer = window.CardGame.Designer || {};

    function C() { return window.CardGame.Constants; }
    function LC() { return window.CardGame.Designer.LayoutConfig; }
    function LR() { return window.CardGame.Designer.LayoutRenderer; }
    function IP() { return window.CardGame.Designer.IconPicker; }

    // ── Designer state ──────────────────────────────────────────────
    let designerState = {
        cardType: "character",
        sizeKey: "poker",
        selectedElementId: null,
        designMode: true,     // true = edit mode, false = preview mode
        previewCardIndex: 0,  // which test card to preview with
        dirty: false,         // unsaved changes
        zoom: 1.5             // canvas zoom level (1.0 = base, up to 2.5)
    };

    // ── Get the current working layout (cloned for editing) ─────────
    let workingLayout = null;

    function loadWorkingLayout(deck) {
        workingLayout = LC().cloneLayout(
            LC().getLayout(deck, designerState.cardType, designerState.sizeKey)
        );
        designerState.selectedElementId = null;
        designerState.dirty = false;
    }

    function getSelectedElement() {
        if (!workingLayout || !designerState.selectedElementId) return null;
        return LC().findElement(workingLayout, designerState.selectedElementId);
    }

    // ── Get one sample card per type for preview cycling ────────────
    // Returns { card, typeList, typeIndex } where typeList is available types
    function getSampleCard(deck) {
        let cards = deck?.cards || [];
        // Build one sample per card type (first card of each type)
        let typeMap = {};
        let typeOrder = [];
        for (let c of cards) {
            if (!typeMap[c.type]) {
                typeMap[c.type] = c;
                typeOrder.push(c.type);
            }
        }
        // Ensure current cardType is in the list even if no card exists
        if (!typeMap[designerState.cardType]) {
            typeOrder.push(designerState.cardType);
        }

        let idx = typeOrder.indexOf(designerState.cardType);
        if (idx < 0) idx = 0;

        return typeMap[designerState.cardType] || {
            type: designerState.cardType, name: "Sample " + designerState.cardType,
            stats: { STR: 12, AGI: 10, END: 14, INT: 8, MAG: 6, CHA: 11 },
            needs: { hp: 18, energy: 8, morale: 16 },
            race: "Human", _templateClass: "Fighter", level: 3,
            rarity: "UNCOMMON", slot: "Body", effect: "Sample effect",
            atk: 5, def: 3, subtype: "weapon", damageType: "Slashing",
            description: "A sample card for layout preview."
        };
    }

    // Get the ordered list of types that have cards in the deck
    function getPreviewTypeList(deck) {
        let cards = deck?.cards || [];
        let seen = {};
        let typeOrder = [];
        for (let c of cards) {
            if (!seen[c.type]) {
                seen[c.type] = true;
                typeOrder.push(c.type);
            }
        }
        return typeOrder;
    }

    // ── Compute screen-display dimensions for a card size key ─────
    // Returns { width, height } in CSS px for the designer preview
    function getPreviewDimensions(sizeKey) {
        let sizes = C().CARD_SIZES;
        let maxH = 380; // max preview height in px

        // Screen display variants use fixed sizes
        if (sizeKey === "_compact") return { width: 120, height: 168 };
        if (sizeKey === "_mini") return { width: 70, height: 98 };
        if (sizeKey === "_full") return { width: 280, height: 392 };

        let size = sizes[sizeKey] || sizes.poker;
        let aspect = size.w / size.h;
        let h = maxH;
        let w = Math.round(h * aspect);
        return { width: w, height: h };
    }

    // ── Zone Container (reorderable elements) ───────────────────────
    function ZoneContainer() {
        let dragOverIndex = -1;
        let dragSourceId = null;

        return {
            view: function(vnode) {
                let zoneName = vnode.attrs.zoneName;
                let zone = vnode.attrs.zone;
                let selectedId = designerState.selectedElementId;
                let designMode = designerState.designMode;

                if (!zone) return null;

                return m("div", {
                    class: "cg2-dz-zone" + (designMode ? " cg2-dz-zone-edit" : ""),
                    "data-zone": zoneName,
                    style: { flex: "0 0 " + zone.height + "%" }
                }, [
                    // Zone label (edit mode only)
                    designMode ? m("div", { class: "cg2-dz-zone-label" }, [
                        m("span", zoneName),
                        m("span", { class: "cg2-dz-zone-pct" }, zone.height + "%"),
                        // Zone height adjust buttons
                        m("button", {
                            class: "cg2-dz-zone-btn",
                            title: "Increase zone height",
                            onclick: function() {
                                LC().updateZoneHeight(workingLayout, zoneName, zone.height + 2);
                                designerState.dirty = true;
                                m.redraw();
                            }
                        }, "+"),
                        m("button", {
                            class: "cg2-dz-zone-btn",
                            title: "Decrease zone height",
                            onclick: function() {
                                LC().updateZoneHeight(workingLayout, zoneName, zone.height - 2);
                                designerState.dirty = true;
                                m.redraw();
                            }
                        }, "\u2013")
                    ]) : null,

                    // Elements
                    zone.elements.map(function(el, idx) {
                        let isSelected = selectedId === el.id;
                        return m("div", {
                            key: el.id,
                            class: "cg2-dz-element" + (isSelected ? " cg2-dz-selected" : "") + (!el.visible ? " cg2-dz-hidden" : ""),
                            draggable: designMode,
                            onclick: function(e) {
                                e.stopPropagation();
                                designerState.selectedElementId = el.id;
                                m.redraw();
                            },
                            ondragstart: designMode ? function(e) {
                                dragSourceId = el.id;
                                e.dataTransfer.effectAllowed = "move";
                                e.dataTransfer.setData("text/plain", el.id);
                            } : null,
                            ondragover: designMode ? function(e) {
                                e.preventDefault();
                                dragOverIndex = idx;
                            } : null,
                            ondrop: designMode ? function(e) {
                                e.preventDefault();
                                if (dragSourceId && dragSourceId !== el.id) {
                                    LC().moveElement(workingLayout, zoneName, dragSourceId, idx);
                                    designerState.dirty = true;
                                }
                                dragSourceId = null;
                                dragOverIndex = -1;
                                m.redraw();
                            } : null
                        }, [
                            // Drag handle + type icon
                            designMode ? m("span", { class: "cg2-dz-drag-handle" }, "\u2261") : null,
                            designMode ? m("span", {
                                class: "material-symbols-outlined",
                                style: { fontSize: "12px", color: "#888", marginRight: "4px" }
                            }, (C().LAYOUT_ELEMENT_TYPES[el.type] || {}).icon || "help") : null,
                            // Element label
                            designMode ? m("span", { class: "cg2-dz-el-label" }, [
                                el.id,
                                !el.visible ? m("span", { style: { color: "#bbb", marginLeft: "4px" } }, "(hidden)") : null
                            ]) : null
                        ]);
                    })
                ]);
            }
        };
    }

    // ── Property Panel (right sidebar) ──────────────────────────────
    function PropertyPanel() {
        return {
            view: function(vnode) {
                let found = getSelectedElement();
                if (!found) {
                    return m("div", { class: "cg2-dz-props-empty" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "32px", color: "#ccc" } }, "touch_app"),
                        m("div", { style: { color: "#999", marginTop: "8px" } }, "Select an element to edit")
                    ]);
                }

                let el = found.element;
                let zone = found.zone;

                function updateField(key, value) {
                    el[key] = value;
                    designerState.dirty = true;
                    m.redraw();
                }

                function updateStyle(key, value) {
                    if (!el.style) el.style = {};
                    el.style[key] = value;
                    designerState.dirty = true;
                    m.redraw();
                }

                let typeDef = C().LAYOUT_ELEMENT_TYPES[el.type] || {};

                return m("div", { class: "cg2-dz-props" }, [
                    // Element info header
                    m("div", { class: "cg2-dz-props-header" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "6px" } }, typeDef.icon || "help"),
                        m("span", { style: { fontWeight: 700 } }, el.id),
                        m("span", { style: { fontSize: "10px", color: "#888", marginLeft: "auto" } }, el.type)
                    ]),

                    // Visibility toggle
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Visible"),
                        m("input", {
                            type: "checkbox", checked: el.visible,
                            onchange: function(e) { updateField("visible", e.target.checked); }
                        })
                    ]),

                    // Content (for labels, iconDetails, custom)
                    (el.type === "label" || el.type === "iconDetail" || el.type === "custom" || el.type === "emoji") ?
                        m("div", { class: "cg2-dz-prop-row" }, [
                            m("label", "Content"),
                            m("input", {
                                type: "text", value: el.content || "",
                                oninput: function(e) { updateField("content", e.target.value); }
                            })
                        ]) : null,

                    // Icon
                    (el.type === "iconDetail" || el.type === "icon" || el.type === "label") ?
                        m("div", { class: "cg2-dz-prop-row" }, [
                            m("label", "Icon"),
                            m(IP().IconPickerButton, {
                                value: el.icon || "",
                                onchange: function(icon) { updateField("icon", icon); }
                            })
                        ]) : null,

                    // Emoji picker integration
                    el.type === "emoji" ? m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Emoji"),
                        typeof page !== "undefined" && page.components && page.components.emoji ?
                            m(page.components.emoji.component) :
                            m("input", {
                                type: "text", value: el.content || "",
                                placeholder: "Paste emoji here",
                                oninput: function(e) { updateField("content", e.target.value); }
                            })
                    ]) : null,

                    // Style properties
                    m("div", { class: "cg2-dz-props-section" }, "Style"),

                    // Font size
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Font Size"),
                        m("input", {
                            type: "number", min: "4", max: "48", step: "1",
                            value: el.style?.fontSize || 10,
                            oninput: function(e) { updateStyle("fontSize", parseInt(e.target.value) || 10); }
                        })
                    ]),

                    // Font weight
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Weight"),
                        m("select", {
                            value: el.style?.fontWeight || 400,
                            onchange: function(e) { updateStyle("fontWeight", parseInt(e.target.value)); }
                        }, [
                            m("option", { value: "300" }, "Light"),
                            m("option", { value: "400" }, "Normal"),
                            m("option", { value: "600" }, "Semibold"),
                            m("option", { value: "700" }, "Bold"),
                            m("option", { value: "900" }, "Black")
                        ])
                    ]),

                    // Color
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Color"),
                        m("input", {
                            type: "text",
                            value: el.style?.color || "",
                            placeholder: "{{typeColor}} or #hex",
                            oninput: function(e) { updateStyle("color", e.target.value); }
                        }),
                        el.style?.color && !el.style.color.startsWith("{") ? m("input", {
                            type: "color",
                            value: el.style.color,
                            oninput: function(e) { updateStyle("color", e.target.value); }
                        }) : null
                    ]),

                    // Text alignment
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Align"),
                        m("div", { style: { display: "flex", gap: "4px" } },
                            ["left", "center", "right"].map(function(a) {
                                return m("button", {
                                    class: "cg2-btn cg2-btn-sm" + (el.style?.textAlign === a ? " cg2-btn-active" : ""),
                                    onclick: function() { updateStyle("textAlign", a); }
                                }, a.charAt(0).toUpperCase());
                            })
                        )
                    ]),

                    // Background
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Background"),
                        m("input", {
                            type: "text",
                            value: el.style?.background || "",
                            placeholder: "{{typeColor}} or #hex or empty",
                            oninput: function(e) { updateStyle("background", e.target.value); }
                        })
                    ]),

                    // Need bar specific: color
                    el.type === "needBar" ? m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Bar Color"),
                        m("input", {
                            type: "color", value: el.color || "#e53935",
                            oninput: function(e) { updateField("color", e.target.value); }
                        }),
                        m("input", {
                            type: "text", value: el.color || "",
                            style: { marginLeft: "4px", flex: 1 },
                            oninput: function(e) { updateField("color", e.target.value); }
                        })
                    ]) : null,

                    // Need bar specific: label
                    el.type === "needBar" ? m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Bar Label"),
                        m("input", {
                            type: "text", value: el.label || "",
                            oninput: function(e) { updateField("label", e.target.value); }
                        })
                    ]) : null,

                    // Stat row specific: fields
                    el.type === "statRow" ? m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Fields"),
                        m("input", {
                            type: "text",
                            value: (el.fields || []).join(", "),
                            placeholder: "atk, init, ap, cha",
                            oninput: function(e) {
                                updateField("fields", e.target.value.split(",").map(function(s) { return s.trim(); }).filter(Boolean));
                            }
                        })
                    ]) : null,

                    // Zone info
                    m("div", { class: "cg2-dz-props-section" }, "Location"),
                    m("div", { class: "cg2-dz-prop-row" }, [
                        m("label", "Zone"),
                        m("span", { style: { fontSize: "12px" } }, zone)
                    ]),

                    // Remove button (only for addable types)
                    zone !== "_overlays" ? m("div", { style: { marginTop: "12px" } },
                        m("button", {
                            class: "cg2-btn cg2-btn-danger cg2-btn-sm",
                            onclick: function() {
                                LC().removeElement(workingLayout, zone, el.id);
                                designerState.selectedElementId = null;
                                designerState.dirty = true;
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "delete"),
                            "Remove Element"
                        ])
                    ) : null
                ]);
            }
        };
    }

    // ── Add Element Menu ────────────────────────────────────────────
    function AddElementMenu() {
        let menuOpen = false;
        let targetZone = null;

        return {
            view: function(vnode) {
                let zoneName = vnode.attrs.zoneName;
                let addableTypes = Object.entries(C().LAYOUT_ELEMENT_TYPES).filter(function(pair) { return pair[1].canAdd; });

                return m("div", { class: "cg2-dz-add-wrap" }, [
                    m("button", {
                        class: "cg2-btn cg2-btn-sm",
                        onclick: function() {
                            menuOpen = !menuOpen;
                            targetZone = zoneName;
                            m.redraw();
                        }
                    }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle" } }, "add"),
                        " Add"
                    ]),
                    menuOpen && targetZone === zoneName ? m("div", { class: "cg2-dz-add-menu" },
                        addableTypes.map(function(pair) {
                            let typeKey = pair[0];
                            let typeDef = pair[1];
                            return m("button", {
                                class: "cg2-dz-add-item",
                                onclick: function() {
                                    let newEl = LC().addElement(workingLayout, zoneName, typeKey, {
                                        content: typeKey === "label" ? "New Label" : "",
                                        icon: typeKey === "iconDetail" ? "info" : "",
                                        style: { fontSize: 9 }
                                    });
                                    if (newEl) {
                                        designerState.selectedElementId = newEl.id;
                                        designerState.dirty = true;
                                    }
                                    menuOpen = false;
                                    m.redraw();
                                }
                            }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", marginRight: "6px" } }, typeDef.icon),
                                typeDef.label
                            ]);
                        })
                    ) : null
                ]);
            }
        };
    }

    // ── Main Designer View ──────────────────────────────────────────
    function DesignerView() {
        return {
            oninit: function(vnode) {
                let ctx = window.CardGame.ctx || {};
                let deck = vnode.attrs.deck || ctx.designerDeck || ctx.viewingDeck;
                if (deck) loadWorkingLayout(deck);
            },
            view: function(vnode) {
                let ctx = window.CardGame.ctx || {};
                let deck = ctx.designerDeck || ctx.viewingDeck;
                if (!deck) return m("div", { style: { padding: "20px", color: "#aaa" } }, [
                    m("button", {
                        class: "cg2-btn",
                        onclick: function() { ctx.screen = "deckList"; m.redraw(); }
                    }, "\u2190 Back to Decks"),
                    m("p", "No deck loaded. Open a deck first, then click Designer.")
                ]);

                let types = C().CARD_TYPES;
                let sizes = C().CARD_SIZES;
                let sampleCard = getSampleCard(deck);

                // Ensure layout is loaded
                if (!workingLayout || workingLayout.cardType !== designerState.cardType || workingLayout.cardSize !== designerState.sizeKey) {
                    loadWorkingLayout(deck);
                }

                return m("div", { class: "cg2-designer" }, [
                    // Toolbar
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", {
                            class: "cg2-btn",
                            onclick: function() { ctx.screen = "deckView"; m.redraw(); }
                        }, "\u2190 Back to Deck"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px", marginLeft: "8px" } }, "Card Designer"),
                        designerState.dirty ? m("span", { style: { color: "#E65100", fontSize: "11px", marginLeft: "8px" } }, "\u2022 unsaved") : null,
                        m("span", { style: { marginLeft: "auto" } }),
                        // Design / Preview toggle
                        m("button", {
                            class: "cg2-btn cg2-btn-sm" + (designerState.designMode ? " cg2-btn-active" : ""),
                            onclick: function() { designerState.designMode = true; m.redraw(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "design_services"),
                            "Design"
                        ]),
                        m("button", {
                            class: "cg2-btn cg2-btn-sm" + (!designerState.designMode ? " cg2-btn-active" : ""),
                            onclick: function() { designerState.designMode = false; m.redraw(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "visibility"),
                            "Preview"
                        ]),
                        // Reset
                        m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            style: { marginLeft: "8px" },
                            onclick: function() {
                                workingLayout = null;
                                loadWorkingLayout(deck);
                                m.redraw();
                            }
                        }, "Reset"),
                        // Save
                        m("button", {
                            class: "cg2-btn cg2-btn-primary cg2-btn-sm",
                            style: { marginLeft: "4px" },
                            disabled: !designerState.dirty,
                            async onclick() {
                                LC().saveLayout(deck, workingLayout);
                                let safeName = (deck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                                await CardGame.Storage.deckStorage.save(safeName, deck);
                                designerState.dirty = false;
                                if (window.page && page.toast) page.toast("success", "Layout saved");
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "save"),
                            "Save"
                        ])
                    ]),

                    // Card type tabs
                    m("div", { class: "cg2-dz-type-tabs" },
                        Object.keys(types).map(function(t) {
                            let cfg = types[t];
                            return m("button", {
                                class: "cg2-dz-tab" + (designerState.cardType === t ? " cg2-dz-tab-active" : ""),
                                style: designerState.cardType === t ? { borderBottomColor: cfg.color } : {},
                                onclick: function() {
                                    designerState.cardType = t;
                                    loadWorkingLayout(deck);
                                    m.redraw();
                                }
                            }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", marginRight: "3px", color: cfg.color } }, cfg.icon),
                                cfg.label
                            ]);
                        })
                    ),

                    // Card size tabs
                    m("div", { class: "cg2-dz-size-tabs" },
                        Object.keys(sizes).concat(["_compact", "_mini"]).map(function(s) {
                            let label = sizes[s] ? sizes[s].label : (s === "_compact" ? "Compact (Hand)" : "Mini (Action Bar)");
                            return m("button", {
                                class: "cg2-dz-tab cg2-dz-tab-sm" + (designerState.sizeKey === s ? " cg2-dz-tab-active" : ""),
                                onclick: function() {
                                    designerState.sizeKey = s;
                                    loadWorkingLayout(deck);
                                    m.redraw();
                                }
                            }, label);
                        })
                    ),

                    // Main content: left (element list) | center (canvas + preview) | right (properties)
                    m("div", { class: "cg2-dz-main" }, [
                        // Left sidebar: zone/element tree
                        designerState.designMode ? m("div", { class: "cg2-dz-sidebar-left" }, [
                            m("div", { class: "cg2-dz-sidebar-title" }, "Zones"),
                            C().LAYOUT_ZONES.map(function(zn) {
                                let zone = workingLayout?.zones?.[zn];
                                if (!zone) return null;
                                return m("div", { class: "cg2-dz-zone-tree", key: zn }, [
                                    m("div", { class: "cg2-dz-zone-tree-header" }, [
                                        m("span", zn),
                                        m("span", { style: { fontSize: "10px", color: "#888", marginLeft: "auto" } }, zone.height + "%"),
                                    ]),
                                    zone.elements.map(function(el) {
                                        let isSelected = designerState.selectedElementId === el.id;
                                        return m("div", {
                                            class: "cg2-dz-tree-el" + (isSelected ? " cg2-dz-tree-el-selected" : "") + (!el.visible ? " cg2-dz-tree-el-hidden" : ""),
                                            onclick: function() {
                                                designerState.selectedElementId = el.id;
                                                m.redraw();
                                            }
                                        }, [
                                            m("span", {
                                                class: "material-symbols-outlined",
                                                style: { fontSize: "12px", marginRight: "4px", cursor: "pointer", color: el.visible ? "#666" : "#ccc" },
                                                onclick: function(e) {
                                                    e.stopPropagation();
                                                    el.visible = !el.visible;
                                                    designerState.dirty = true;
                                                    m.redraw();
                                                }
                                            }, el.visible ? "visibility" : "visibility_off"),
                                            m("span", { style: { fontSize: "11px" } }, el.id)
                                        ]);
                                    }),
                                    m(AddElementMenu, { zoneName: zn })
                                ]);
                            })
                        ]) : null,

                        // Center: canvas area
                        (function() {
                            let dim = getPreviewDimensions(designerState.sizeKey);
                            let zoom = designerState.zoom;
                            let sizeInfo = sizes[designerState.sizeKey];
                            let sizeLabel = sizeInfo ? (sizeInfo.px[0] + " \u00d7 " + sizeInfo.px[1] + " px") :
                                (designerState.sizeKey === "_compact" ? "120 \u00d7 168 px (Hand)" :
                                 designerState.sizeKey === "_mini" ? "70 \u00d7 98 px (Action Bar)" :
                                 designerState.sizeKey === "_full" ? "280 \u00d7 392 px (Full)" : "");

                            let typeList = getPreviewTypeList(deck);

                            function renderCardFace() {
                                return m(LR().LayoutCardFace, {
                                    card: sampleCard,
                                    deck: deck,
                                    sizeKey: designerState.sizeKey,
                                    layoutConfig: workingLayout,
                                    bgImage: deck.cardFrontImageUrl || null,
                                    compact: designerState.sizeKey === "_compact",
                                    mini: designerState.sizeKey === "_mini",
                                    full: designerState.sizeKey === "_full",
                                    noPreview: true
                                });
                            }

                            return m("div", { class: "cg2-dz-canvas" }, [
                                // Top bar: size indicator + zoom controls
                                m("div", { class: "cg2-dz-size-info" }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", marginRight: "4px" } }, "straighten"),
                                    sizeLabel,
                                    m("span", { style: { margin: "0 12px", color: "#444" } }, "|"),
                                    // Zoom controls
                                    m("div", { class: "cg2-dz-zoom-controls" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "zoom_out"),
                                        m("input", {
                                            type: "range", min: "0.75", max: "2.5", step: "0.25",
                                            value: zoom,
                                            oninput: function(e) {
                                                designerState.zoom = parseFloat(e.target.value);
                                                m.redraw();
                                            }
                                        }),
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "zoom_in"),
                                        m("span", { class: "cg2-dz-zoom-pct" }, Math.round(zoom * 100) + "%")
                                    ])
                                ]),

                                // Card display area — zoomed
                                m("div", {
                                    class: "cg2-dz-zoom-wrap",
                                    style: { transform: "scale(" + zoom + ")", marginBottom: Math.round((zoom - 1) * dim.height) + "px" }
                                }, [
                                    // Side by side in design mode
                                    designerState.designMode ?
                                        m("div", { class: "cg2-dz-design-split" }, [
                                            // Zone editor
                                            m("div", {
                                                class: "cg2-dz-edit-wrap",
                                                style: { width: dim.width + "px", height: dim.height + "px" }
                                            }, [
                                                C().LAYOUT_ZONES.map(function(zn) {
                                                    let zone = workingLayout?.zones?.[zn];
                                                    return m(ZoneContainer, { zoneName: zn, zone: zone, key: zn });
                                                })
                                            ]),
                                            // Live preview
                                            m("div", { class: "cg2-dz-live-preview" }, [
                                                m("div", { class: "cg2-dz-live-label" }, "Live Preview"),
                                                m("div", {
                                                    class: "cg2-dz-preview-wrap",
                                                    style: { width: dim.width + "px", height: dim.height + "px" }
                                                }, renderCardFace())
                                            ])
                                        ]) :
                                        // Preview-only mode: single card
                                        m("div", {
                                            class: "cg2-dz-preview-wrap",
                                            style: { width: dim.width + "px", height: dim.height + "px" }
                                        }, renderCardFace())
                                ]),

                                // Card type prev/next (cycles through types, one card per type)
                                m("div", { class: "cg2-dz-card-picker" }, [
                                    m("button", {
                                        class: "cg2-btn cg2-btn-sm",
                                        disabled: typeList.length < 2,
                                        onclick: function() {
                                            let idx = typeList.indexOf(designerState.cardType);
                                            let prev = (idx - 1 + typeList.length) % typeList.length;
                                            designerState.cardType = typeList[prev];
                                            loadWorkingLayout(deck);
                                            m.redraw();
                                        }
                                    }, "\u25C0"),
                                    m("span", { style: { fontSize: "11px", fontWeight: 600, minWidth: "100px", textAlign: "center" } }, [
                                        m("span", {
                                            class: "material-symbols-outlined",
                                            style: { fontSize: "12px", marginRight: "4px", verticalAlign: "middle", color: (types[designerState.cardType] || {}).color || "#888" }
                                        }, (types[designerState.cardType] || {}).icon || "help"),
                                        sampleCard.name || ("Sample " + designerState.cardType)
                                    ]),
                                    m("button", {
                                        class: "cg2-btn cg2-btn-sm",
                                        disabled: typeList.length < 2,
                                        onclick: function() {
                                            let idx = typeList.indexOf(designerState.cardType);
                                            let next = (idx + 1) % typeList.length;
                                            designerState.cardType = typeList[next];
                                            loadWorkingLayout(deck);
                                            m.redraw();
                                        }
                                    }, "\u25B6")
                                ])
                            ]);
                        })(),

                        // Right sidebar: property panel
                        designerState.designMode ? m("div", { class: "cg2-dz-sidebar-right" },
                            m(PropertyPanel)
                        ) : null
                    ]),

                    // Icon picker overlay
                    m(IP().IconPickerOverlay)
                ]);
            }
        };
    }

    // ── Expose ──────────────────────────────────────────────────────
    window.CardGame.Designer.DesignerCanvas = {
        DesignerView: DesignerView,
        designerState: designerState,
        loadWorkingLayout: loadWorkingLayout
    };
    // Also expose DesignerView directly on Designer namespace for CardGameApp routing
    window.CardGame.Designer.DesignerView = DesignerView;

    console.log("[CardGame] Designer.DesignerCanvas loaded");
}());
