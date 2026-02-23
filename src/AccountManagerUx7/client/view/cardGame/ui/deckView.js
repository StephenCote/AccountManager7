/**
 * CardGame UI - Deck View / Editor with Art Generation Controls
 * Card grid display with flip capability, art generation controls (queue all,
 * single card, background, tabletop, card front/back), SD Config panel with
 * override tabs, Game Config panel (LLM, voice settings), and refresh/outfit controls.
 *
 * Extracted from cardGame-v2.js (lines ~9906-10584).
 *
 * Depends on:
 *   - CardGame.Constants (CARD_TYPES, TEMPLATE_TYPES)
 *   - CardGame.Rendering (CardFace, showImagePreview, ImagePreviewOverlay, GalleryPickerOverlay,
 *                         renderCharacterBackBody)
 *   - CardGame.ctx (viewingDeck, screen, gameState, activeTheme, artQueue, artCompleted,
 *                   artTotal, artPaused, artDir, backgroundImageId, backgroundThumbUrl,
 *                   backgroundPrompt, backgroundGenerating, tabletopImageId, tabletopThumbUrl,
 *                   tabletopGenerating, cardFrontImageUrl, cardBackImageUrl, cardFrontGenerating,
 *                   cardBackGenerating, flippedCards, sdOverrides, sdOverrideInsts,
 *                   sdOverrideViews, sdConfigExpanded, sdConfigTab, gameConfigExpanded,
 *                   voiceProfiles, voiceProfilesLoaded, applyingOutfits, sequenceCardId,
 *                   sequenceProgress, gameCharSelection)
 *   - CardGame.Services (refreshAllCharacters, refreshCharacterCard, applyThemeOutfits,
 *                        queueDeckArt, cancelArtQueue, processArtQueue, generateBackground,
 *                        generateTabletop, generateTemplateArt, generateImageSequence,
 *                        openGalleryPicker, buildCardPrompt, loadVoiceProfiles, loadThemeConfig)
 *   - CardGame.Storage (deckStorage)
 *   - CardGame.Actions (newSdOverride, defaultSdOverrides, resetSdOverrideInsts,
 *                       getSdOverrideInst, getDeckGameConfig, currentDeckSafeName,
 *                       ArtQueueProgress)
 *   - CardGame.AI (CardGameNarrator)
 *
 * Exposes: window.CardGame.UI.DeckView
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    const C = window.CardGame.Constants;

    let deckHasSave = false;
    let saveCheckPending = false;
    let saveCheckDeck = null;

    function checkForSaves(viewingDeck) {
        let deckId = viewingDeck?.storageName;
        if (!deckId) {
            console.log("[CardGame v2] Save check: no storageName on deck", viewingDeck?.deckName || "(no deck)");
            return;
        }
        if (saveCheckPending) return;
        // Only check once per deck per mount (oninit resets saveCheckDeck)
        if (saveCheckDeck === deckId) return;
        saveCheckPending = true;
        saveCheckDeck = deckId;
        console.log("[CardGame v2] Save check: looking for saves in", deckId);
        if (CardGame.Storage && CardGame.Storage.gameStorage) {
            CardGame.Storage.gameStorage.list(deckId).then(function(saves) {
                deckHasSave = saves && saves.length > 0;
                saveCheckPending = false;
                console.log("[CardGame v2] Save check result:", deckId, "→", saves.length, "saves found, deckHasSave=" + deckHasSave);
                if (saves.length > 0) console.log("[CardGame v2] Save files:", saves.map(function(s) { return s.name; }));
                m.redraw();
            }).catch(function(e) {
                console.warn("[CardGame v2] Save check failed for", deckId, ":", e);
                deckHasSave = false;
                saveCheckPending = false;
            });
        } else {
            console.warn("[CardGame v2] Save check: CardGame.Storage.gameStorage not available");
            saveCheckPending = false;
        }
    }

    function DeckView() {
        return {
            oninit() {
                let ctx = window.CardGame.ctx || {};
                let viewingDeck = ctx.viewingDeck;

                // Reset save check on mount so it re-runs
                deckHasSave = false;
                saveCheckDeck = null;
                saveCheckPending = false;
                checkForSaves(viewingDeck);

                // Restore background reference from saved deck
                if (viewingDeck && viewingDeck.backgroundImageId) {
                    ctx.backgroundImageId = viewingDeck.backgroundImageId;
                    ctx.backgroundPrompt = viewingDeck.backgroundPrompt || null;
                    ctx.backgroundThumbUrl = viewingDeck.backgroundThumbUrl || null;
                } else {
                    ctx.backgroundImageId = null;
                    ctx.backgroundPrompt = null;
                    ctx.backgroundThumbUrl = null;
                }
                // Restore tabletop reference from saved deck
                if (viewingDeck && viewingDeck.tabletopImageId) {
                    ctx.tabletopImageId = viewingDeck.tabletopImageId;
                    ctx.tabletopThumbUrl = viewingDeck.tabletopThumbUrl || null;
                } else {
                    ctx.tabletopImageId = null;
                    ctx.tabletopThumbUrl = null;
                }
                // Restore SD overrides from saved deck
                if (viewingDeck && viewingDeck.sdOverrides) {
                    let saved = viewingDeck.sdOverrides;
                    ctx.sdOverrides = { _default: CardGame.ArtPipeline.newSdOverride() };
                    // Merge saved _default with model defaults
                    if (saved._default) Object.assign(ctx.sdOverrides._default, saved._default);
                    // Restore any explicitly-saved non-default tabs
                    Object.keys(saved).forEach(k => {
                        if (k !== "_default" && saved[k]) {
                            let copy = JSON.parse(JSON.stringify(saved[k]));
                            copy[am7model.jsonModelKey] = "olio.sd.config";
                            ctx.sdOverrides[k] = copy;
                        }
                    });
                } else {
                    ctx.sdOverrides = CardGame.ArtPipeline.defaultSdOverrides();
                }
                CardGame.ArtPipeline.resetSdOverrideInsts();
                ctx.sdConfigExpanded = false;
                ctx.sdConfigTab = "_default";
                ctx.gameConfigExpanded = false;
                ctx.flippedCards = {};
            },
            view() {
                let ctx = window.CardGame.ctx || {};
                let viewingDeck = ctx.viewingDeck;
                let activeTheme = ctx.activeTheme;

                // Re-check saves on each render (debounced — only runs if needed)
                checkForSaves(viewingDeck);
                let CARD_TYPES = C.CARD_TYPES;
                let TEMPLATE_TYPES = C.TEMPLATE_TYPES;
                let R = window.CardGame.Rendering;

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

                let artQueue = ctx.artQueue || [];
                let backgroundGenerating = ctx.backgroundGenerating;
                let tabletopGenerating = ctx.tabletopGenerating;
                let cardFrontGenerating = ctx.cardFrontGenerating;
                let cardBackGenerating = ctx.cardBackGenerating;
                let sequenceCardId = ctx.sequenceCardId;
                let backgroundImageId = ctx.backgroundImageId;
                let backgroundThumbUrl = ctx.backgroundThumbUrl;
                let tabletopImageId = ctx.tabletopImageId;
                let tabletopThumbUrl = ctx.tabletopThumbUrl;
                let cardFrontImageUrl = ctx.cardFrontImageUrl;
                let cardBackImageUrl = ctx.cardBackImageUrl;
                let flippedCards = ctx.flippedCards || {};
                let sdOverrides = ctx.sdOverrides || {};
                let sdOverrideInsts = ctx.sdOverrideInsts || {};
                let sdConfigExpanded = ctx.sdConfigExpanded;
                let sdConfigTab = ctx.sdConfigTab || "_default";
                let gameConfigExpanded = ctx.gameConfigExpanded;
                let voiceProfiles = ctx.voiceProfiles || [];
                let voiceProfilesLoaded = ctx.voiceProfilesLoaded;
                let applyingOutfits = ctx.applyingOutfits;

                let queueActive = artQueue.some(j => j.status === "pending" || j.status === "processing");
                let busy = queueActive || backgroundGenerating || tabletopGenerating || cardFrontGenerating || cardBackGenerating || !!sequenceCardId;
                return m("div", [
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", { class: "cg2-btn", onclick: () => { ctx.screen = "deckList"; ctx.viewingDeck = null; CardGame.ArtPipeline.cancelArtQueue(); ctx.artTotal = 0; ctx.backgroundImageId = null; ctx.backgroundThumbUrl = null; CardGame.UI.loadSavedDecks(); m.redraw(); } }, "\u2190 Back to Decks"),
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
                                await CardGame.Characters.refreshAllCharacters(viewingDeck);
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
                                await CardGame.Themes.applyThemeOutfits(viewingDeck, activeTheme);
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "checkroom"),
                            applyingOutfits ? "Outfitting..." : "Apply Theme Outfits"
                        ]),
                        deckHasSave ? m("button", {
                            class: "cg2-btn cg2-btn-accent",
                            style: { fontSize: "11px", marginLeft: "8px" },
                            disabled: busy,
                            title: "Resume saved game",
                            onclick() {
                                if (ctx.resumeGame) ctx.resumeGame(viewingDeck.storageName);
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "restore"),
                            "Resume"
                        ]) : null,
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            style: { fontSize: "11px", marginLeft: deckHasSave ? "4px" : "8px" },
                            disabled: busy,
                            title: deckHasSave ? "Start a new game (overwrites save)" : "Start a new game with this deck",
                            onclick() {
                                ctx.gameState = null; // Reset any existing game
                                ctx.gameCharSelection = null; // Reset character selection
                                ctx.screen = "game";
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "play_arrow"),
                            deckHasSave ? "New Game" : "Play Game"
                        ]),
                        m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            style: { fontSize: "11px", marginLeft: "4px" },
                            disabled: busy,
                            title: "Run tests using this deck",
                            onclick() {
                                let deckName = viewingDeck?.deckName || viewingDeck?.storageName || null;
                                if (CardGame.TestMode && CardGame.TestMode.setTestDeck) {
                                    CardGame.TestMode.setTestDeck(viewingDeck, deckName);
                                }
                                ctx.screen = "test";
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "science"),
                            "Test"
                        ]),
                        m("button", {
                            class: "cg2-btn cg2-btn-sm",
                            style: { fontSize: "11px", marginLeft: "4px" },
                            title: "Print cards and rules (use Save as PDF for digital export)",
                            onclick() { window.print(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "print"),
                            "Print / PDF"
                        ])
                    ]),
                    // SD Config panel (per-type)
                    m("div", { class: "cg2-sd-panel" }, [
                        m("div", {
                            class: "cg2-sd-panel-header",
                            onclick() { ctx.sdConfigExpanded = !ctx.sdConfigExpanded; m.redraw(); }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "6px", transition: "transform 0.2s", transform: ctx.sdConfigExpanded ? "rotate(90deg)" : "" } }, "chevron_right"),
                            m("span", { style: { fontWeight: 600, fontSize: "13px" } }, "SD Config"),
                            (() => {
                                let insts = ctx.sdOverrideInsts || {};
                                let active = Object.keys(insts).filter(k => {
                                    return insts[k] && insts[k].changes && insts[k].changes.length > 0;
                                });
                                return active.length
                                    ? m("span", { style: { fontSize: "10px", color: "#2E7D32", marginLeft: "8px" } }, active.length + " type" + (active.length > 1 ? "s" : "") + " modified")
                                    : null;
                            })()
                        ]),
                        ctx.sdConfigExpanded ? m("div", { class: "cg2-sd-panel-body" }, [
                            // Type tabs
                            m("div", { class: "cg2-sd-tabs" }, [
                                m("span", {
                                    class: "cg2-sd-tab" + (ctx.sdConfigTab === "_default" ? " cg2-sd-tab-active" : ""),
                                    onclick() { ctx.sdConfigTab = "_default"; m.redraw(); }
                                }, "Default"),
                                ...Object.keys(CARD_TYPES).map(t =>
                                    m("span", {
                                        class: "cg2-sd-tab" + (ctx.sdConfigTab === t ? " cg2-sd-tab-active" : ""),
                                        style: { borderBottomColor: ctx.sdConfigTab === t ? CARD_TYPES[t].color : "transparent" },
                                        onclick() { ctx.sdConfigTab = t; m.redraw(); }
                                    }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", marginRight: "2px", color: CARD_TYPES[t].color } }, CARD_TYPES[t].icon),
                                        CARD_TYPES[t].label
                                    ])
                                ),
                                m("span", { class: "cg2-sd-tab-sep" }),
                                ...Object.keys(TEMPLATE_TYPES).map(t =>
                                    m("span", {
                                        class: "cg2-sd-tab" + (ctx.sdConfigTab === t ? " cg2-sd-tab-active" : ""),
                                        style: { borderBottomColor: ctx.sdConfigTab === t ? TEMPLATE_TYPES[t].color : "transparent" },
                                        onclick() { ctx.sdConfigTab = t; m.redraw(); }
                                    }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "12px", marginRight: "2px", color: TEMPLATE_TYPES[t].color } }, TEMPLATE_TYPES[t].icon),
                                        TEMPLATE_TYPES[t].label
                                    ])
                                )
                            ]),
                            // Fields rendered via standard form system
                            (() => {
                                let inst = CardGame.ArtPipeline.getSdOverrideInst(ctx.sdConfigTab);
                                let sdOvViews = ctx.sdOverrideViews || {};
                                let ov = sdOvViews[ctx.sdConfigTab];
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
                                        ctx.sdOverrides = CardGame.ArtPipeline.defaultSdOverrides();
                                        CardGame.ArtPipeline.resetSdOverrideInsts();
                                        ctx.sdConfigTab = "_default";
                                        m.redraw();
                                    }
                                }, "Reset All"),
                                m("div", { style: { display: "flex", gap: "6px" } }, [
                                    m("button", {
                                        class: "cg2-btn", style: { fontSize: "11px" },
                                        onclick() {
                                            if (ctx.sdConfigTab === "_default") {
                                                ctx.sdOverrides[ctx.sdConfigTab] = CardGame.ArtPipeline.newSdOverride();
                                            } else {
                                                // Reset card type to a copy of _default
                                                let copy = JSON.parse(JSON.stringify(ctx.sdOverrides._default));
                                                copy[am7model.jsonModelKey] = "olio.sd.config";
                                                ctx.sdOverrides[ctx.sdConfigTab] = copy;
                                            }
                                            let insts = ctx.sdOverrideInsts || {};
                                            let views = ctx.sdOverrideViews || {};
                                            delete insts[ctx.sdConfigTab];
                                            delete views[ctx.sdConfigTab];
                                            m.redraw();
                                        }
                                    }, "Clear " + (ctx.sdConfigTab === "_default" ? "Default" : (CARD_TYPES[ctx.sdConfigTab] || TEMPLATE_TYPES[ctx.sdConfigTab] || {}).label || ctx.sdConfigTab)),
                                    m("button", {
                                        class: "cg2-btn cg2-btn-primary", style: { fontSize: "11px" },
                                        async onclick() {
                                            if (viewingDeck) {
                                                // Fill any undefined types from _default before saving
                                                let allKeys = Object.keys(CARD_TYPES).concat(Object.keys(TEMPLATE_TYPES));
                                                let overrides = ctx.sdOverrides || {};
                                                allKeys.forEach(k => {
                                                    if (!overrides[k]) {
                                                        overrides[k] = JSON.parse(JSON.stringify(overrides._default));
                                                        overrides[k][am7model.jsonModelKey] = "olio.sd.config";
                                                    }
                                                });
                                                viewingDeck.sdOverrides = JSON.parse(JSON.stringify(overrides));
                                                let safeName = (viewingDeck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");
                                                await CardGame.Storage.deckStorage.save(safeName, viewingDeck);
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
                                ctx.gameConfigExpanded = !ctx.gameConfigExpanded;
                                if (ctx.gameConfigExpanded && !ctx.voiceProfilesLoaded) CardGame.ArtPipeline.loadVoiceProfiles();
                                m.redraw();
                            }
                        }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", marginRight: "6px", transition: "transform 0.2s", transform: ctx.gameConfigExpanded ? "rotate(90deg)" : "" } }, "chevron_right"),
                            m("span", { style: { fontWeight: 600, fontSize: "13px" } }, "Game Config"),
                            (() => {
                                let gc = CardGame.ArtPipeline.getDeckGameConfig(viewingDeck);
                                let tags = [];
                                if (gc.narrationEnabled === false) tags.push("LLM off");
                                if (gc.opponentVoiceEnabled) tags.push("opponent voice");
                                if (gc.announcerEnabled) tags.push("announcer" + (gc.announcerVoiceEnabled ? " +voice" : ""));
                                return tags.length > 0 ? m("span", { style: { marginLeft: "8px", fontSize: "11px", color: "#888" } }, tags.join(" \u00b7 ")) : null;
                            })()
                        ]),
                        ctx.gameConfigExpanded ? m("div", { style: { padding: "12px 16px" } }, (() => {
                            let gc = CardGame.ArtPipeline.getDeckGameConfig(viewingDeck);
                            let vProfiles = ctx.voiceProfiles || [];
                            let vLoaded = ctx.voiceProfilesLoaded;
                            let voiceProfilePicker = (label, valueKey, icon) => m("div", { class: "cg2-config-row", style: { paddingLeft: "24px" } }, [
                                m("label", { class: "cg2-config-label" }, [
                                    m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, icon || "mic"),
                                    label
                                ]),
                                m("div", { class: "cg2-config-control" }, [
                                    vProfiles.length > 0
                                        ? m("select", {
                                            class: "cg2-config-select",
                                            value: gc[valueKey] || "",
                                            onchange(e) { gc[valueKey] = e.target.value || null; m.redraw(); }
                                        }, [
                                            m("option", { value: "" }, "-- Default Voice (Piper) --"),
                                            ...vProfiles.map(vp =>
                                                m("option", { value: vp.objectId },
                                                    vp.name || ((vp.engine || "piper") + " \u2014 " + (vp.speaker || "default")))
                                            )
                                        ])
                                        : m("span", { style: { fontSize: "11px", color: "#999" } },
                                            vLoaded ? "No voice profiles found \u2014 default Piper voice will be used" : "Loading voice profiles...")
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
                                // Announcer style info (from theme)
                                gc.announcerEnabled ? m("div", { class: "cg2-config-row", style: { paddingLeft: "24px" } }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "style"),
                                        "Style"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("span", { style: { fontSize: "12px", color: "#666" } },
                                            (function() {
                                                let prof = activeTheme?.narration?.announcerProfile || "arena-announcer";
                                                let profiles = CardGame.AI?.CardGameNarrator?.PROFILES;
                                                return profiles?.[prof]?.name || prof;
                                            })() + " (from theme)")
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

                                // ── Section: Poker Face & Banter ──
                                m("div", { class: "cg2-config-section-label" }, "Poker Face & Banter"),
                                m("div", { class: "cg2-config-row" }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "theater_comedy"),
                                        "Poker Face"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("button", {
                                            class: "cg2-btn cg2-btn-sm" + (gc.pokerFaceEnabled ? " cg2-btn-active" : ""),
                                            onclick() { gc.pokerFaceEnabled = !gc.pokerFaceEnabled; m.redraw(); }
                                        }, gc.pokerFaceEnabled ? "Enabled" : "Disabled"),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            gc.pokerFaceEnabled ? "Webcam detects your emotions during play" : "No emotion detection")
                                    ])
                                ]),
                                gc.pokerFaceEnabled ? m("div", { class: "cg2-config-row", style: { paddingLeft: "24px" } }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "chat_bubble"),
                                        "Banter Level"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("select", {
                                            class: "cg2-config-select",
                                            value: gc.banterLevel || "moderate",
                                            onchange(e) { gc.banterLevel = e.target.value; m.redraw(); }
                                        }, [
                                            m("option", { value: "subtle" }, "Subtle"),
                                            m("option", { value: "moderate" }, "Moderate"),
                                            m("option", { value: "aggressive" }, "Aggressive")
                                        ]),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            gc.banterLevel === "aggressive" ? "AI taunts based on your expressions"
                                            : gc.banterLevel === "subtle" ? "Emotion used as atmosphere only"
                                            : "AI references body language indirectly")
                                    ])
                                ]) : null,

                                // ── Section: Chat Management ──
                                m("div", { class: "cg2-config-section-label" }, "Chat Management"),
                                m("div", { class: "cg2-config-row" }, [
                                    m("label", { class: "cg2-config-label" }, [
                                        m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "6px" } }, "forum"),
                                        "Game Chats"
                                    ]),
                                    m("div", { class: "cg2-config-control" }, [
                                        m("button", {
                                            class: "cg2-btn cg2-btn-sm cg2-btn-danger",
                                            style: { fontSize: "11px" },
                                            async onclick() {
                                                let safeName = CardGame.ArtPipeline.currentDeckSafeName();
                                                if (!safeName) return;
                                                let count = await CardGame.UI.deleteGameChats(safeName);
                                                if (count > 0) {
                                                    page.toast("success", "Deleted " + count + " game chat(s)");
                                                } else {
                                                    page.toast("info", "No game chats found for this deck");
                                                }
                                                m.redraw();
                                            }
                                        }, [
                                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "3px" } }, "delete_sweep"),
                                            "Delete All Chats"
                                        ]),
                                        m("span", { style: { fontSize: "10px", color: "#999", marginLeft: "8px" } },
                                            "Removes Director, Narrator, and NPC chat sessions")
                                    ])
                                ]),

                                // Save button
                                m("div", { style: { marginTop: "12px", textAlign: "right" } }, [
                                    m("button", {
                                        class: "cg2-btn cg2-btn-primary",
                                        style: { fontSize: "11px" },
                                        async onclick() {
                                            viewingDeck.gameConfig = gc;
                                            let safeName = CardGame.ArtPipeline.currentDeckSafeName();
                                            if (safeName) {
                                                await CardGame.Storage.deckStorage.save(safeName, viewingDeck);
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
                                    onclick() { R.showImagePreview(backgroundThumbUrl); }
                                }) : null
                            ]),
                            m("div", { class: "cg2-bg-panel-right" }, [
                                m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { CardGame.ArtPipeline.generateBackground(activeTheme); }
                                }, [
                                    backgroundGenerating
                                        ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "progress_activity")
                                        : m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "landscape"),
                                    backgroundGenerating ? "..." : (backgroundImageId ? "Regen" : "Gen")
                                ]),
                                backgroundImageId ? m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { ctx.backgroundImageId = null; ctx.backgroundThumbUrl = null; m.redraw(); }
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
                                    onclick() { R.showImagePreview(tabletopThumbUrl); }
                                }) : null
                            ]),
                            m("div", { class: "cg2-bg-panel-right" }, [
                                m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { CardGame.ArtPipeline.generateTabletop(activeTheme); }
                                }, [
                                    tabletopGenerating
                                        ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "progress_activity")
                                        : m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "table_restaurant"),
                                    tabletopGenerating ? "..." : (tabletopImageId ? "Regen" : "Gen")
                                ]),
                                tabletopImageId ? m("button", {
                                    class: "cg2-btn",
                                    disabled: busy,
                                    onclick() { ctx.tabletopImageId = null; ctx.tabletopThumbUrl = null; m.redraw(); }
                                }, "\u00d7") : null
                            ])
                        ]),
                        // Generate All Character Sequences
                        (function() {
                            let charCount = allCards.filter(c => c.type === "character" && c.sourceId).length;
                            if (!charCount) return null;
                            let batchActive = CardGame.ArtPipeline.state.batchSequenceActive;
                            let batchDone = CardGame.ArtPipeline.state.batchSequenceCompleted;
                            let batchTotal = CardGame.ArtPipeline.state.batchSequenceTotal;
                            return m("div", { class: "cg2-bg-panel", style: { flex: "1 1 200px" } }, [
                                m("div", { class: "cg2-bg-panel-left" }, [
                                    m("span", { style: { fontWeight: 700, fontSize: "12px" } }, "Char Sequences"),
                                    m("span", { style: { fontSize: "11px", color: "#888" } },
                                        batchActive
                                            ? batchDone + " / " + batchTotal + " done"
                                            : charCount + " character" + (charCount > 1 ? "s" : ""))
                                ]),
                                m("div", { class: "cg2-bg-panel-right" }, [
                                    m("button", {
                                        class: "cg2-btn",
                                        disabled: busy || batchActive,
                                        onclick() { CardGame.ArtPipeline.generateAllSequences(); }
                                    }, [
                                        batchActive
                                            ? m("span", { class: "material-symbols-outlined cg2-spin", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "progress_activity")
                                            : m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "4px" } }, "photo_library"),
                                        batchActive ? "..." : "Gen All"
                                    ])
                                ])
                            ]);
                        })()
                    ]),
                    // Art toolbar
                    m("div", { class: "cg2-toolbar" }, [
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: busy,
                            title: "Generate art only for cards that don't have images yet",
                            onclick() { CardGame.ArtPipeline.queueDeckArt(viewingDeck); }
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
                                allCards.forEach(c => { delete c.imageUrl; delete c.artObjectId; delete c.portraitUrl; });
                                // Clear front/back template art
                                ctx.cardFrontImageUrl = null;
                                ctx.cardBackImageUrl = null;
                                // Clear background and tabletop
                                ctx.backgroundImageId = null;
                                ctx.backgroundThumbUrl = null;
                                ctx.tabletopImageId = null;
                                ctx.tabletopThumbUrl = null;
                                if (viewingDeck) {
                                    delete viewingDeck.cardFrontImageUrl;
                                    delete viewingDeck.cardBackImageUrl;
                                    delete viewingDeck.backgroundImageId;
                                    delete viewingDeck.backgroundThumbUrl;
                                    delete viewingDeck.tabletopImageId;
                                    delete viewingDeck.tabletopThumbUrl;
                                }
                                CardGame.ArtPipeline.queueDeckArt(viewingDeck);
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
                    m(CardGame.ArtPipeline.ArtQueueProgress),
                    // Card grid -- card front, card back, then all card faces
                    m("div", { class: "cg2-card-grid" }, [
                        // Card Front template
                        m("div", { class: "cg2-card-art-wrapper", key: "card-front" }, [
                            m("div", {
                                class: "cg2-card cg2-card-front cg2-card-front-bg",
                                style: cardFrontImageUrl ? { backgroundImage: "url('" + cardFrontImageUrl + "')", backgroundSize: "cover", backgroundPosition: "center" } : {},
                                onclick() { if (cardFrontImageUrl) R.showImagePreview(cardFrontImageUrl); }
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
                                    onclick(e) { e.stopPropagation(); CardGame.ArtPipeline.generateTemplateArt("front"); }
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
                                onclick() { if (cardBackImageUrl) R.showImagePreview(cardBackImageUrl); }
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
                                    onclick(e) { e.stopPropagation(); CardGame.ArtPipeline.generateTemplateArt("back"); }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "auto_awesome"))
                            ]) : null
                        ]),
                        // Card faces (unique cards only)
                        ...uniqueCards.map(({ card, index: i, count }) => {
                            let artJob = artQueue.find(j => j.cardIndex === i);
                            let isGenerating = artJob && artJob.status === "processing";
                            let hasArt = card.imageUrl || (card.type === "character" && card.portraitUrl);
                            let isChar = card.type === "character";
                            // v3.1: Use CardFlipContainer for character cards (handles flip internally)
                            let cardContent;
                            if (isChar) {
                                cardContent = m(R.CardFlipContainer, {
                                    card,
                                    bgImage: cardFrontImageUrl,
                                    bgImageBack: cardBackImageUrl,
                                    flipped: flippedCards[i],
                                    onFlip(v) { flippedCards[i] = v; }
                                });
                            } else {
                                cardContent = m(R.CardFace, { card, bgImage: cardFrontImageUrl });
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
                                    onclick() { R.showImagePreview(card.imageUrl); }
                                }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "zoom_in")) : null,
                                // Per-card action buttons
                                !busy ? m("div", { class: "cg2-card-actions" }, [
                                    // Reimage button
                                    m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Regenerate art\n" + CardGame.ArtPipeline.buildCardPrompt(card, activeTheme),
                                        onclick(e) {
                                            e.stopPropagation();
                                            delete card.imageUrl;
                                            delete card.artObjectId;
                                            if (card.type === "character") delete card.portraitUrl;
                                            ctx.artQueue = [{ card, cardIndex: i, status: "pending", result: null, error: null }];
                                            ctx.artCompleted = 0;
                                            ctx.artTotal = 1;
                                            ctx.artPaused = false;
                                            ctx.artDir = null;
                                            CardGame.ArtPipeline.processArtQueue();
                                        }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "auto_awesome")),
                                    // Gallery picker (character cards only)
                                    card.sourceId && card.type === "character" ? m("button", {
                                        class: "cg2-card-action-btn",
                                        title: "Pick portrait from gallery",
                                        onclick(e) {
                                            e.stopPropagation();
                                            CardGame.Rendering.openGalleryPicker(card);
                                        }
                                    }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "photo_library")) : null,
                                    // Image sequence (character cards only) -- dress-up progression
                                    card.sourceId && card.type === "character" ? (function() {
                                        let isThisCard = sequenceCardId === card.sourceId;
                                        let seqProgress = ctx.sequenceProgress || "";
                                        return m("button", {
                                            class: "cg2-card-action-btn",
                                            title: isThisCard ? seqProgress : "Generate apparel image sequence",
                                            disabled: !!sequenceCardId,
                                            onclick(e) {
                                                e.stopPropagation();
                                                CardGame.ArtPipeline.generateImageSequence(card);
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
                                            let ok = await CardGame.Characters.refreshCharacterCard(card);
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
                    m(R.ImagePreviewOverlay),
                    m(R.GalleryPickerOverlay),

                    // Printable instruction sheet — hidden on screen, visible in print
                    m(PrintRulesSheet, { deckName: viewingDeck.deckName })
                ]);
            }
        };
    }

    // ── Printable Rules / Quick-Reference Sheet ───────────────────────
    function PrintRulesSheet() {
        return {
            view(vnode) {
                let deckName = vnode.attrs.deckName || "Card Game";
                let AD = window.CardGame.Constants.ACTION_DEFINITIONS || {};
                let commonActions = window.CardGame.Constants.COMMON_ACTIONS || [];

                return m("div", { class: "cg2-print-rules" }, [
                    m("h1", { class: "cg2-print-rules-title" }, deckName + " — Quick Rules"),

                    // ── Overview
                    m("h2", "Overview"),
                    m("p", "A 1v1 card game played in rounds. Each round has four phases: Initiative, Placement, Resolution, and Cleanup. " +
                        "Reduce your opponent's HP to 0 to win. Characters also have Energy (for spells and abilities) and Morale (affected by Talk actions)."),

                    // ── Setup
                    m("h2", "Setup"),
                    m("ol", [
                        m("li", "Each player picks a Character card. Your character's stats determine your combat abilities."),
                        m("li", "Shuffle all remaining cards (items, skills, magic, encounters, scenarios, loot) into a shared deck."),
                        m("li", "Each player draws 5 cards to form their starting hand."),
                        m("li", "Equip any Apparel or Weapon cards from your hand onto your character (free action before the game starts).")
                    ]),

                    // ── Stats
                    m("h2", "Character Stats"),
                    m("table", { class: "cg2-print-table" }, [
                        m("thead", m("tr", [m("th", "Stat"), m("th", "Abbr"), m("th", "Effect")])),
                        m("tbody", [
                            m("tr", [m("td", "Strength"),     m("td", "STR"), m("td", "Melee attack damage bonus")]),
                            m("tr", [m("td", "Agility"),      m("td", "AGI"), m("td", "Initiative rolls, dodge, flee checks")]),
                            m("tr", [m("td", "Endurance"),    m("td", "END"), m("td", "Action Points per round = floor(END/5)+1, min 2")]),
                            m("tr", [m("td", "Intelligence"), m("td", "INT"), m("td", "Skill effects, investigate, craft checks")]),
                            m("tr", [m("td", "Magic"),        m("td", "MAG"), m("td", "Energy pool (for spells), spell power")]),
                            m("tr", [m("td", "Charisma"),     m("td", "CHA"), m("td", "Talk actions, morale effects, trade prices")])
                        ])
                    ]),

                    // ── Resources
                    m("h2", "Resources"),
                    m("ul", [
                        m("li", [m("strong", "HP"), " — Hit Points (default max 20). Reach 0 = defeat."]),
                        m("li", [m("strong", "Energy"), " — Powers spells and special abilities. Based on MAG stat. Regenerates +3 on Rest."]),
                        m("li", [m("strong", "Morale"), " — Affected by Talk actions and combat outcomes (default max 20)."]),
                        m("li", [m("strong", "AP"), " — Action Points. Spent to place cards each round. Resets every round."])
                    ]),

                    // ── Round Phases
                    m("h2", "Round Phases"),

                    m("h3", "1. Initiative"),
                    m("p", "Both players roll 1d20 + AGI modifier. Winner places cards on odd positions (1, 3, 5...), loser on even positions (2, 4, 6...). " +
                        "Actions resolve left-to-right, so odd positions act first."),
                    m("p", [m("em", "Nat 1 Warning: "), "Rolling a natural 1 triggers a Threat — a hostile encounter drawn from the encounter deck that you must fight immediately."]),

                    m("h3", "2. Placement"),
                    m("p", "Players take turns placing cards from their hand onto the Action Bar. Each card costs 1 AP. " +
                        "A placement consists of a core action (Attack, Channel, Talk, etc.) plus optional modifier cards (weapons, skills, magic) stacked on top."),
                    m("ul", [
                        m("li", "Draw 2 cards at the start of placement."),
                        m("li", "You may only place one Attack action per round."),
                        m("li", "Some actions cost Energy in addition to AP."),
                        m("li", "When both players are out of AP (or pass), placement ends.")
                    ]),

                    m("h3", "3. Resolution"),
                    m("p", "Actions resolve left-to-right across the Action Bar. Each action is resolved with dice rolls:"),
                    m("ul", [
                        m("li", [m("strong", "Attack: "), "1d20 + STR + weapon ATK vs opponent's DEF. Damage based on margin of success."]),
                        m("li", [m("strong", "Channel: "), "1d20 + MAG. Casts a spell from stacked Magic Effect cards. Costs Energy."]),
                        m("li", [m("strong", "Talk: "), "1d20 + CHA vs opponent CHA. Success affects opponent morale. Costs 5 Energy."]),
                        m("li", [m("strong", "Guard: "), "+3 DEF for this round (automatic, no roll needed)."]),
                        m("li", [m("strong", "Rest: "), "Restore +2 HP, +3 Energy, +2 Morale. Skips your attack."]),
                        m("li", [m("strong", "Flee: "), "1d20 + AGI vs difficulty. Success ends combat (forfeit pot). Threat carries to next round on failure."])
                    ]),

                    // Combat outcomes
                    m("h3", "Combat Outcomes"),
                    m("table", { class: "cg2-print-table" }, [
                        m("thead", m("tr", [m("th", "Result"), m("th", "Condition"), m("th", "Effect")])),
                        m("tbody", [
                            m("tr", [m("td", "Critical Hit"),    m("td", "Nat 20"),     m("td", "Double damage + bonus loot card")]),
                            m("tr", [m("td", "Devastating"),     m("td", "Win by 10+"), m("td", "1.5x damage")]),
                            m("tr", [m("td", "Strong Hit"),      m("td", "Win by 5+"),  m("td", "Full damage")]),
                            m("tr", [m("td", "Glancing Hit"),    m("td", "Win by 1-4"), m("td", "Half damage")]),
                            m("tr", [m("td", "Clash"),           m("td", "Tie"),        m("td", "Both take 1 damage")]),
                            m("tr", [m("td", "Deflected"),       m("td", "Lose by 1-4"),m("td", "No damage")]),
                            m("tr", [m("td", "Parried"),         m("td", "Lose by 5+"), m("td", "Defender may counter")]),
                            m("tr", [m("td", "Perfect Parry"),   m("td", "Nat 20 DEF"), m("td", "Counter attack + bonus loot")]),
                            m("tr", [m("td", "Critical Miss"),   m("td", "Nat 1 ATK"),  m("td", "Attacker takes damage + stunned")])
                        ])
                    ]),

                    m("h3", "4. Cleanup"),
                    m("p", "After all actions resolve: discard used consumables, check for broken equipment (durability), " +
                        "draw an end-of-round Scenario card (may trigger a bonus or a Threat). " +
                        "Round winner claims the pot (anted cards). Start next round."),

                    // ── Card Types
                    m("h2", "Card Types"),
                    m("table", { class: "cg2-print-table" }, [
                        m("thead", m("tr", [m("th", "Type"), m("th", "Color"), m("th", "Description")])),
                        m("tbody", [
                            m("tr", [m("td", "Character"),  m("td", { style: { color: "#B8860B" } }, "\u25A0"), m("td", "Your avatar. Has 6 stats, HP, Energy, Morale.")]),
                            m("tr", [m("td", "Action"),     m("td", { style: { color: "#C62828" } }, "\u25A0"), m("td", "Core actions (Attack, Guard, Rest, etc.) placed on the Action Bar.")]),
                            m("tr", [m("td", "Item"),       m("td", { style: { color: "#2E7D32" } }, "\u25A0"), m("td", "Weapons (stack with Attack) and Consumables (one-time use).")]),
                            m("tr", [m("td", "Apparel"),    m("td", { style: { color: "#808080" } }, "\u25A0"), m("td", "Armor and gear. Equip to boost DEF and HP. Has durability.")]),
                            m("tr", [m("td", "Skill"),      m("td", { style: { color: "#E65100" } }, "\u25A0"), m("td", "Passive modifiers. Stack with actions for roll bonuses.")]),
                            m("tr", [m("td", "Magic"),      m("td", { style: { color: "#00695C" } }, "\u25A0"), m("td", "Spells. Stack with Channel action. Costs Energy.")]),
                            m("tr", [m("td", "Talk"),       m("td", { style: { color: "#1565C0" } }, "\u25A0"), m("td", "Social action. Roll CHA vs CHA to affect morale. Costs 5 Energy.")]),
                            m("tr", [m("td", "Encounter"),  m("td", { style: { color: "#6A1B9A" } }, "\u25A0"), m("td", "Hostile threats with ATK/DEF/HP. Must be fought when drawn.")]),
                            m("tr", [m("td", "Scenario"),   m("td", { style: { color: "#1B5E20" } }, "\u25A0"), m("td", "Random events — boons or threats drawn at end of round.")]),
                            m("tr", [m("td", "Loot"),       m("td", { style: { color: "#F57F17" } }, "\u25A0"), m("td", "Rewards from defeating encounters or critical hits.")])
                        ])
                    ]),

                    // ── Status Effects
                    m("h2", "Status Effects"),
                    m("div", { class: "cg2-print-effects" }, [
                        m("span", [m("strong", "Stunned"), " — Skip next action"]),
                        m("span", [m("strong", "Poisoned"), " — -2 HP/turn (3 turns)"]),
                        m("span", [m("strong", "Burning"), " — -3 HP/turn (2 turns)"]),
                        m("span", [m("strong", "Bleeding"), " — -1 HP/turn (4 turns)"]),
                        m("span", [m("strong", "Shielded"), " — +3 DEF (until hit)"]),
                        m("span", [m("strong", "Weakened"), " — -2 to all rolls (2 turns)"]),
                        m("span", [m("strong", "Enraged"), " — +3 ATK, -2 DEF (2 turns)"]),
                        m("span", [m("strong", "Fortified"), " — +2 DEF, +1 ATK (2 turns)"]),
                        m("span", [m("strong", "Inspired"), " — +2 to all rolls (2 turns)"]),
                        m("span", [m("strong", "Regenerating"), " — +2 HP/turn (3 turns)"])
                    ]),

                    // ── Threats
                    m("h2", "Threats"),
                    m("p", "Threats are hostile encounters that interrupt normal play. They trigger from:"),
                    m("ul", [
                        m("li", "Rolling a natural 1 on initiative"),
                        m("li", "End-of-round scenario cards with a \"threat\" effect"),
                        m("li", "Failed flee attempts (threat carries to next round)")
                    ]),
                    m("p", "When a threat appears, the targeted player gets bonus AP to place defensive cards (Block, Dodge, armor) before combat resolves. " +
                        "You can also attempt to Flee (1d20 + AGI vs threat difficulty)."),

                    // ── Offline Dice
                    m("h2", "Playing Offline"),
                    m("p", "To play with physical cards and dice, you need:"),
                    m("ul", [
                        m("li", "This printed card set"),
                        m("li", "1d20 (twenty-sided die) per player"),
                        m("li", "Paper to track HP, Energy, Morale, and AP per round"),
                        m("li", "Tokens or coins to mark the pot (anted cards each round)")
                    ]),
                    m("p", { class: "cg2-print-footer" }, "Printed from Account Manager Card Game")
                ]);
            }
        };
    }

    window.CardGame.UI.DeckView = DeckView;

})();
