/**
 * Card Game v2 — Rendering: Overlay Components
 * Extracted from cardGame-v2.js: ImagePreviewOverlay, CardPreviewOverlay, GalleryPickerOverlay
 *
 * Ported from Ux7 IIFE to ESM.
 *
 * Depends on: am7model (late-bound page/client), cardFace (CardFace component)
 */
import m from 'mithril';
import { am7model } from '../../core/model.js';

function getPage() { return am7model._page; }
function getClient() { return am7model._client; }

// Late-bound: CardFace is in a sibling module that imports from us (circular).
// We resolve it lazily to avoid import cycle issues.
let _cardFaceRef = null;
export function _setCardFaceRef(ref) { _cardFaceRef = ref; }
function getCardFace() { return _cardFaceRef; }

// Late-bound: ctx and other CardGame modules accessed via setters
let _ctxFn = null;
export function _setCtxFn(fn) { _ctxFn = fn; }
function getCtx() { return _ctxFn ? _ctxFn() : null; }

let _artPipelineRef = null;
export function _setArtPipelineRef(ref) { _artPipelineRef = ref; }
function getArtPipeline() { return _artPipelineRef; }

let _charactersRef = null;
export function _setCharactersRef(ref) { _charactersRef = ref; }
function getCharacters() { return _charactersRef; }

let _deckStorageFn = null;
export function _setDeckStorageFn(fn) { _deckStorageFn = fn; }
function getDeckStorage() { return _deckStorageFn ? _deckStorageFn() : null; }

// ── Image Preview Overlay ──────────────────────────────────────────
let previewImageUrl = null;

export function showImagePreview(url) {
    if (!url) return;
    // Upgrade to larger thumbnail size for preview
    previewImageUrl = url.replace(/\/\d+x\d+/, "/512x512");
    m.redraw();
}

export function closeImagePreview() {
    previewImageUrl = null;
    m.redraw();
}

export function ImagePreviewOverlay() {
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

export function showCardPreview(card) {
    if (!card) return;
    previewCard = card;
    previewFlipped = false;  // Always start showing front
    m.redraw();
}

export function closeCardPreview() {
    previewCard = null;
    previewFlipped = false;
    m.redraw();
}

export function CardPreviewOverlay() {
    return {
        view() {
            if (!previewCard) return null;
            let CF = getCardFace();
            if (!CF) return null;
            let ctx = getCtx() || {};
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
                        m(CF.CardFace, { card: previewCard, bgImage: cardFrontBg, full: true })
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

export async function openGalleryPicker(card) {
    if (!card) return;
    let page = getPage();
    let client = getClient();
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
        let ctx = getCtx() || {};

        // Helper to search a group for images
        async function searchGroupImages(groupId) {
            if (!groupId) return [];
            let q = client.newQuery("data.data");
            q.field("groupId", groupId);
            q.range(0, 100);
            q.sort("createdDate");
            q.order("descending");
            let qr = await page.search(q);
            if (qr && qr.results) {
                return qr.results.filter(r => r.contentType && r.contentType.match(/^image\//i));
            }
            return [];
        }

        // Search deck art directory (all card types, including character portraits)
        try {
            let ap = getArtPipeline();
            let artDirGroup = ap?.getArtDir?.();
            let searchGroupId = artDirGroup?.id;
            if (!searchGroupId && ctx.viewingDeck) {
                let deckName = (ctx.viewingDeck.deckName || "").replace(/[^a-zA-Z0-9_\-]/g, "_");
                if (deckName) {
                    let artPath = "~/CardGame/" + deckName + "/Art";
                    let dir = await page.makePath("auth.group", "DATA", artPath);
                    if (dir) searchGroupId = dir.id;
                }
            }
            let allImages = await searchGroupImages(searchGroupId);
            // For character cards: filter to images matching this character's name
            if (card.type === "character" && card.name) {
                let prefix = card.name.replace(/[^a-zA-Z0-9_\-]/g, "_");
                galleryImages = allImages.filter(img => img.name && img.name.startsWith(prefix));
                // If no matches, leave empty so portrait group fallback runs
            } else {
                galleryImages = allImages;
            }
        } catch (e) {
            console.warn("[CardGame v2] Deck art dir search failed:", e);
        }

        // Fallback for characters: check portrait group (legacy portraits before deck art dir storage)
        if (!galleryImages.length && card.type === "character" && card.sourceId) {
            let chars = getCharacters();
            let fetchCharPerson = chars?.fetchCharPerson;
            let char = fetchCharPerson ? await fetchCharPerson(card.sourceId) : null;
            if (char && char.profile && char.profile.portrait && char.profile.portrait.groupId) {
                galleryImages = await searchGroupImages(char.profile.portrait.groupId);
            }
        }

        if (!galleryImages.length) {
            page.toast("warn", "No gallery images found for " + card.name);
            galleryPickerCard = null;
        }
    } catch (e) {
        console.error("[CardGame v2] Gallery load failed", e);
        let page2 = getPage();
        if (page2) page2.toast("error", "Failed to load gallery");
        galleryPickerCard = null;
    }
    galleryLoading = false;
    m.redraw();
}

export function closeGalleryPicker() {
    galleryPickerCard = null;
    galleryImages = [];
    m.redraw();
}

export function GalleryPickerOverlay() {
    return {
        view() {
            if (!galleryPickerCard) return null;
            let client = getClient();
            let ctx = getCtx() || {};
            let viewingDeck = ctx.viewingDeck;
            let deckStorage = getDeckStorage();
            let orgPath = client.dotPath(client.currentOrganization);
            let basePath = client.base().replace("/rest", "");

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
                                let encodedName = encodeURIComponent(img.name);
                                let src = basePath + "/thumbnail/" + orgPath + "/data.data" + img.groupPath + "/" + encodedName + "/96x96";
                                let fullSrc = basePath + "/thumbnail/" + orgPath + "/data.data" + img.groupPath + "/" + encodedName + "/256x256";
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
                                        let page = getPage();
                                        if (page) page.toast("success", "Portrait updated");
                                    }
                                });
                            })
                        )
                ])
            ]);
        }
    };
}

// ── Barrel export ────────────────────────────────────────────────
export const overlays = {
    showImagePreview, closeImagePreview, ImagePreviewOverlay,
    showCardPreview, closeCardPreview, CardPreviewOverlay,
    openGalleryPicker, closeGalleryPicker, GalleryPickerOverlay,
    _setCardFaceRef, _setCtxFn, _setArtPipelineRef, _setCharactersRef, _setDeckStorageFn
};
