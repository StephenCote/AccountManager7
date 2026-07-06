/**
 * CardGame Designer — Export Dialog
 * Modal dialog for configuring and executing card export.
 *
 * Depends on:
 *   - window.CardGame.Constants (CARD_SIZES)
 *   - window.CardGame.Designer.ExportPipeline (exportDeck, cancelExport, exportState)
 *
 * Exposes: window.CardGame.Designer.ExportDialog
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Designer = window.CardGame.Designer || {};

    function C() { return window.CardGame.Constants; }
    function EP() { return window.CardGame.Designer.ExportPipeline; }

    let dialogVisible = false;
    let dialogConfig = {
        sizeKey: "poker",
        customW: 2.5,
        customH: 3.5,
        customDpi: 300,
        format: "png",
        quality: 90,
        includeBack: false
    };

    function openDialog() {
        dialogVisible = true;
        EP().resetState();
        m.redraw();
    }

    function closeDialog() {
        dialogVisible = false;
        m.redraw();
    }

    // ── Export Dialog Component ──────────────────────────────────────
    function ExportDialogOverlay() {
        return {
            view: function(vnode) {
                if (!dialogVisible) return null;

                let deck = vnode.attrs.deck;
                let bgImage = vnode.attrs.bgImage;
                let state = EP().exportState;
                let sizes = C().CARD_SIZES;

                // Compute custom size pixels
                if (dialogConfig.sizeKey === "custom") {
                    sizes.custom.w = dialogConfig.customW;
                    sizes.custom.h = dialogConfig.customH;
                    sizes.custom.dpi = dialogConfig.customDpi;
                    sizes.custom.px = [
                        Math.round(dialogConfig.customW * dialogConfig.customDpi),
                        Math.round(dialogConfig.customH * dialogConfig.customDpi)
                    ];
                }

                let selectedSize = sizes[dialogConfig.sizeKey] || sizes.poker;
                let cardCount = 0;
                if (deck?.cards) {
                    let seen = {};
                    deck.cards.forEach(function(c) {
                        let sig = c.name + "|" + c.type;
                        if (!seen[sig]) { seen[sig] = true; cardCount++; }
                    });
                }

                // Check for missing libraries
                let missingLibs = EP().checkLibraries();

                return m("div", {
                    class: "cg2-export-overlay",
                    onclick: function(e) { if (e.target === e.currentTarget && !state.active) closeDialog(); }
                }, m("div", { class: "cg2-export-panel" }, [
                    // Header
                    m("div", { class: "cg2-export-header" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "20px", marginRight: "8px" } }, "file_download"),
                        m("span", { style: { fontWeight: 700, fontSize: "16px" } }, "Export Cards"),
                        !state.active ? m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            style: { marginLeft: "auto" },
                            onclick: closeDialog
                        }, "\u00d7") : null
                    ]),

                    // Library warning
                    missingLibs.length > 0 ? m("div", { class: "cg2-export-warning" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "4px", color: "#C62828" } }, "error"),
                        "Missing libraries: " + missingLibs.join(", ") + ". Please reload the page."
                    ]) : null,

                    // Settings (hidden when exporting)
                    !state.active ? m("div", { class: "cg2-export-settings" }, [
                        // Card size
                        m("div", { class: "cg2-export-row" }, [
                            m("label", "Card Size:"),
                            m("select", {
                                value: dialogConfig.sizeKey,
                                onchange: function(e) { dialogConfig.sizeKey = e.target.value; m.redraw(); }
                            }, Object.keys(sizes).map(function(key) {
                                return m("option", { value: key }, sizes[key].label);
                            }))
                        ]),

                        // Custom size inputs
                        dialogConfig.sizeKey === "custom" ? m("div", { class: "cg2-export-custom" }, [
                            m("div", { class: "cg2-export-row" }, [
                                m("label", "Width (inches):"),
                                m("input", {
                                    type: "number", step: "0.25", min: "1", max: "10",
                                    value: dialogConfig.customW,
                                    oninput: function(e) { dialogConfig.customW = parseFloat(e.target.value) || 2.5; }
                                })
                            ]),
                            m("div", { class: "cg2-export-row" }, [
                                m("label", "Height (inches):"),
                                m("input", {
                                    type: "number", step: "0.25", min: "1", max: "12",
                                    value: dialogConfig.customH,
                                    oninput: function(e) { dialogConfig.customH = parseFloat(e.target.value) || 3.5; }
                                })
                            ]),
                            m("div", { class: "cg2-export-row" }, [
                                m("label", "DPI:"),
                                m("select", {
                                    value: dialogConfig.customDpi,
                                    onchange: function(e) { dialogConfig.customDpi = parseInt(e.target.value) || 300; }
                                }, [
                                    m("option", { value: "150" }, "150 (Draft)"),
                                    m("option", { value: "300" }, "300 (Print)"),
                                    m("option", { value: "600" }, "600 (High Quality)")
                                ])
                            ])
                        ]) : null,

                        // Pixel preview
                        m("div", { class: "cg2-export-preview" }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", marginRight: "4px" } }, "straighten"),
                            selectedSize.px[0] + " \u00d7 " + selectedSize.px[1] + " px",
                            m("span", { style: { color: "#888", marginLeft: "8px" } },
                                "(" + selectedSize.w + "\" \u00d7 " + selectedSize.h + "\" @ " + selectedSize.dpi + " DPI)")
                        ]),

                        // Format
                        m("div", { class: "cg2-export-row" }, [
                            m("label", "Format:"),
                            m("div", { style: { display: "flex", gap: "8px" } }, [
                                m("button", {
                                    class: "cg2-btn cg2-btn-sm" + (dialogConfig.format === "png" ? " cg2-btn-active" : ""),
                                    onclick: function() { dialogConfig.format = "png"; m.redraw(); }
                                }, "PNG"),
                                m("button", {
                                    class: "cg2-btn cg2-btn-sm" + (dialogConfig.format === "jpg" ? " cg2-btn-active" : ""),
                                    onclick: function() { dialogConfig.format = "jpg"; m.redraw(); }
                                }, "JPG")
                            ])
                        ]),

                        // JPG quality
                        dialogConfig.format === "jpg" ? m("div", { class: "cg2-export-row" }, [
                            m("label", "Quality: " + dialogConfig.quality + "%"),
                            m("input", {
                                type: "range", min: "60", max: "100", step: "5",
                                value: dialogConfig.quality,
                                oninput: function(e) { dialogConfig.quality = parseInt(e.target.value); }
                            })
                        ]) : null,

                        // Include backs
                        m("div", { class: "cg2-export-row" }, [
                            m("label", { style: { display: "flex", alignItems: "center", gap: "6px" } }, [
                                m("input", {
                                    type: "checkbox",
                                    checked: dialogConfig.includeBack,
                                    onchange: function(e) { dialogConfig.includeBack = e.target.checked; }
                                }),
                                "Include card backs"
                            ])
                        ]),

                        // Summary
                        m("div", { class: "cg2-export-summary" }, [
                            cardCount + " unique card" + (cardCount !== 1 ? "s" : "") +
                            " \u2192 " + (cardCount * (dialogConfig.includeBack ? 2 : 1)) + " images"
                        ]),

                        // Export button
                        m("div", { style: { display: "flex", justifyContent: "flex-end", marginTop: "12px", gap: "8px" } }, [
                            m("button", {
                                class: "cg2-btn",
                                onclick: closeDialog
                            }, "Cancel"),
                            m("button", {
                                class: "cg2-btn cg2-btn-primary",
                                disabled: missingLibs.length > 0 || cardCount === 0,
                                async onclick() {
                                    try {
                                        let result = await EP().exportDeck(deck, {
                                            sizeKey: dialogConfig.sizeKey,
                                            format: dialogConfig.format,
                                            quality: dialogConfig.quality,
                                            includeBack: dialogConfig.includeBack,
                                            bgImage: bgImage
                                        });
                                        if (result) {
                                            if (window.page && page.toast) {
                                                page.toast("success", "Exported " + result.count + " cards (" + Math.round(result.size / 1024) + " KB)");
                                            }
                                            closeDialog();
                                        }
                                    } catch (e) {
                                        if (window.page && page.toast) {
                                            page.toast("error", "Export failed: " + e.message);
                                        }
                                    }
                                }
                            }, [
                                m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "file_download"),
                                "Export ZIP"
                            ])
                        ])
                    ]) : null,

                    // Progress (shown when exporting)
                    state.active ? m("div", { class: "cg2-export-progress" }, [
                        m("div", { class: "cg2-export-progress-label" }, [
                            m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "16px", marginRight: "6px" } }, "progress_activity"),
                            state.currentCard || "Starting..."
                        ]),
                        m("div", { class: "cg2-export-progress-bar" },
                            m("div", {
                                class: "cg2-export-progress-fill",
                                style: { width: (state.total > 0 ? (state.completed / state.total * 100) : 0) + "%" }
                            })
                        ),
                        m("div", { style: { display: "flex", justifyContent: "space-between", fontSize: "11px", color: "#888" } }, [
                            m("span", state.completed + " / " + state.total),
                            m("button", {
                                class: "cg2-btn cg2-btn-sm cg2-btn-danger",
                                onclick: function() { EP().cancelExport(); }
                            }, "Cancel")
                        ])
                    ]) : null,

                    // Error
                    state.error ? m("div", { class: "cg2-export-error" }, [
                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "4px" } }, "error"),
                        state.error
                    ]) : null
                ]));
            }
        };
    }

    // ── Expose ──────────────────────────────────────────────────────
    window.CardGame.Designer.ExportDialog = {
        ExportDialogOverlay: ExportDialogOverlay,
        openDialog: openDialog,
        closeDialog: closeDialog,
        open: openDialog,
        close: closeDialog,
        dialogConfig: dialogConfig
    };

    console.log("[CardGame] Designer.ExportDialog loaded");
}());
