// Card Game v2 — Rendering: Overlay Components
// Extracted from cardGame-v2.js: ImagePreviewOverlay, CardPreviewOverlay, GalleryPickerOverlay
(function() {
    "use strict";

    // ── Image Preview Overlay ──────────────────────────────────────────
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
                    class: "cg2-image-preview-overlay",
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
                let R = window.CardGame.Rendering;
                let ctx = window.CardGame.ctx || {};
                let viewingDeck = ctx.viewingDeck;

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
                            m(R.CardFace, { card: previewCard, bgImage: cardFrontBg, full: true })
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

    // ── Gallery Picker Overlay ─────────────────────────────────────────
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
            let ctx = window.CardGame.ctx || {};
            let fetchCharPerson = ctx.fetchCharPerson;
            let char = fetchCharPerson ? await fetchCharPerson(card.sourceId) : null;
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

    function GalleryPickerOverlay() {
        return {
            view() {
                if (!galleryPickerCard) return null;
                let ctx = window.CardGame.ctx || {};
                let viewingDeck = ctx.viewingDeck;
                let deckStorage = ctx.deckStorage;
                let orgPath = am7client.dotPath(am7client.currentOrganization);

                return m("div", {
                    class: "cg2-image-preview-overlay",
                    style: { cursor: "default" },
                    onclick: closeGalleryPicker
                }, [
                    m("div", {
                        class: "cg2-gallery-dialog",
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
                                            if (viewingDeck && deckStorage) {
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

    window.CardGame = window.CardGame || {};
    window.CardGame.Rendering = window.CardGame.Rendering || {};
    Object.assign(window.CardGame.Rendering, {
        showImagePreview, closeImagePreview, ImagePreviewOverlay,
        showCardPreview, closeCardPreview, CardPreviewOverlay,
        openGalleryPicker, closeGalleryPicker, GalleryPickerOverlay
    });
})();
