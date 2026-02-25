/**
 * CardGame UI - Deck List
 * Deck loading, saving, deleting, viewing, playing, and the DeckList component.
 * Extracted from the monolithic cardGame-v2.js (lines ~1437-1888).
 *
 * Dependencies:
 *   - CardGame.Storage (deckStorage, gameStorage, campaignStorage)
 *   - CardGame.Themes (loadThemeConfig)
 *   - CardGame.Characters (refreshAllCharacters, selectedChars, persistGeneratedCharacters)
 *   - CardGame.ctx (shared mutable context: screen, viewingDeck, buildingDeck, etc.)
 *
 * Exposes: window.CardGame.UI.{deckListState, loadSavedDecks, saveDeck, deleteDeck,
 *   viewDeck, playDeck, resumeGame, refreshAllCharacters, DeckList}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    // ── State ──────────────────────────────────────────────────────────
    let savedDecks = [];
    let decksLoading = false;

    // ── Helpers: resolve shared context ─────────────────────────────────
    function ctx() { return window.CardGame.ctx || {}; }
    function storage() { return window.CardGame.Storage || {}; }
    function themes() { return window.CardGame.Themes || {}; }
    function chars() { return window.CardGame.Characters || {}; }

    // ── Load Saved Decks ───────────────────────────────────────────────
    async function loadSavedDecks() {
        if (decksLoading) return;
        decksLoading = true;
        m.redraw();
        try {
            // Clear cache to pick up newly created decks/groups
            am7client.clearCache("auth.group");
            am7client.clearCache("data.data");
            let deckStorage = storage().deckStorage;
            let gameStorage = storage().gameStorage;
            let campaignStorage = storage().campaignStorage;
            let names = await deckStorage.list();
            console.log("[CardGame v2] deckStorage.list returned:", names);
            let decks = [];
            let seenDeckNames = new Set();
            for (let name of names) {
                let data = await deckStorage.load(name);
                if (data) {
                    // Deduplicate by deckName (server may have duplicate groups)
                    let dn = data.deckName || name;
                    if (seenDeckNames.has(dn)) {
                        console.warn("[CardGame v2] Skipping duplicate deck:", dn, "storageName:", name);
                        continue;
                    }
                    seenDeckNames.add(dn);
                    // Check for saved games and campaign data
                    let savesList = await gameStorage.list(name);
                    let campaign = await campaignStorage.load(name);
                    decks.push({
                        deckName: dn,
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
                else{
                    // console.warn("Null", name);
                }
            }
            savedDecks = decks;
            console.log("[CardGame v2] Loaded " + savedDecks.length + " saved decks");
            let screen = ctx().screen;
            let buildingDeck = ctx().buildingDeck;
            if (savedDecks.length === 0 && screen === "deckList" && !buildingDeck) {
                ctx().screen = "builder";
                ctx().builderStep = 1;
                chars().state.selectedChars = [];
                ctx().builtDeck = null;
            }
        } catch (e) {
            console.error("[CardGame v2] Failed to load decks", e);
        }
        decksLoading = false;
        m.redraw();
    }

    // ── Save Deck ──────────────────────────────────────────────────────
    async function saveDeck(deck) {
        if (!deck) return;
        ctx().buildingDeck = true;
        m.redraw();
        let safeName = (deck.deckName || "deck").replace(/[^a-zA-Z0-9_\-]/g, "_");

        // Persist any generated characters that haven't been saved yet
        let selectedChars = chars().state.selectedChars;
        if (selectedChars && selectedChars.length > 0) {
            const unpersisted = selectedChars.filter(ch => ch._tempId && !ch.objectId);
            if (unpersisted.length > 0) {
                page.toast("info", "Saving characters...");
                const persisted = await chars().persistGeneratedCharacters(unpersisted, safeName);
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

        // If characters were persisted under a different group name, rename the group
        let prevCharGroupName = ctx()._charGroupName;
        if (prevCharGroupName && prevCharGroupName !== safeName) {
            try {
                let oldGroup = await page.findObject("auth.group", "data", "~/CardGame/" + prevCharGroupName);
                if (oldGroup) {
                    // Rename the old deck group to match the new deck name
                    oldGroup.name = safeName;
                    await page.patchObject(oldGroup);
                    console.log("[CardGame] Renamed deck group from", prevCharGroupName, "to", safeName);
                }
            } catch (e) {
                console.warn("[CardGame] Could not rename deck group:", e);
            }
            delete ctx()._charGroupName;
        }

        // Store sanitized name so auto-save paths match the folder structure
        deck.storageName = safeName;
        let deckStorage = storage().deckStorage;
        let result = await deckStorage.save(safeName, deck);
        ctx().buildingDeck = false;
        if (result) {
            page.toast("success", "Deck saved: " + deck.deckName);
            ctx().builtDeck = null;
            chars().state.selectedChars = [];
            m.redraw();
            await viewDeck(safeName);
        } else {
            page.toast("error", "Failed to save deck");
            m.redraw();
        }
    }

    // ── Delete Game Chats ───────────────────────────────────────────────
    // Find and delete all LLM chat requests associated with a deck.
    // Uses am7chat.deleteChat which also deletes the referenced session object.
    async function deleteGameChats(storageName) {
        if (!storageName || typeof am7chat === "undefined") return 0;
        try {
            let dir = await page.findObject("auth.group", "DATA", "~/ChatRequests");
            if (!dir) return 0;
            let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatRequest", am7model.forms.chatRequest));
            q.field("groupId", dir.id);
            q.cache(false);
            q.entity.request.push("session", "sessionType", "chatConfig", "promptConfig", "objectId");
            q.range(0, 100);
            let qr = await page.search(q);
            if (!qr || !qr.results) return 0;
            am7model.updateListModel(qr.results);
            // Match chat requests created for this deck (CG Chat/Narrator/Director + storageName)
            let prefixes = ["CG Chat " + storageName, "CG Narrator " + storageName, "CG Director " + storageName];
            let matches = qr.results.filter(function(r) {
                return r.name && prefixes.indexOf(r.name) >= 0;
            });
            let deleted = 0;
            for (let req of matches) {
                try {
                    await new Promise(function(resolve) {
                        am7chat.deleteChat(req, true, function() { resolve(); });
                    });
                    deleted++;
                } catch (e) {
                    console.warn("[CardGame] Failed to delete chat:", req.name, e);
                }
            }
            // Also delete chatConfig objects from ~/CardGame/Chats
            let cgChatDir = await page.findObject("auth.group", "DATA", "~/CardGame/Chats");
            if (cgChatDir) {
                let cq = am7view.viewQuery(am7model.newInstance("olio.llm.chatConfig"));
                cq.field("groupId", cgChatDir.id);
                cq.cache(false);
                cq.range(0, 100);
                let cqr = await page.search(cq);
                if (cqr && cqr.results) {
                    let cfgMatches = cqr.results.filter(function(r) {
                        return r.name && prefixes.indexOf(r.name) >= 0;
                    });
                    for (let cfg of cfgMatches) {
                        try {
                            await page.deleteObject(cfg[am7model.jsonModelKey], cfg.objectId);
                            deleted++;
                        } catch (e) {
                            console.warn("[CardGame] Failed to delete chatConfig:", cfg.name, e);
                        }
                    }
                }
            }
            if (deleted > 0) {
                console.log("[CardGame] Deleted " + deleted + " game chat(s) for deck:", storageName);
            }
            return deleted;
        } catch (e) {
            console.error("[CardGame] Error deleting game chats:", e);
            return 0;
        }
    }

    // ── Delete Deck ────────────────────────────────────────────────────
    async function deleteDeck(storageName) {
        // Clean up associated LLM chats first
        let chatCount = await deleteGameChats(storageName);
        if (chatCount > 0) {
            page.toast("info", "Deleted " + chatCount + " game chat(s)");
        }
        let deckStorage = storage().deckStorage;
        let ok = await deckStorage.remove(storageName);
        if (ok) {
            page.toast("success", "Deck deleted");
            await loadSavedDecks();
        } else {
            page.toast("error", "Failed to delete deck");
        }
        m.redraw();
    }

    // ── View Deck ──────────────────────────────────────────────────────
    async function viewDeck(storageName) {
        let deckStorage = storage().deckStorage;
        let data = await deckStorage.load(storageName);
        if (data) {
            data.storageName = storageName;  // Ensure save path matches folder
            ctx().viewingDeck = data;
            ctx().cardFrontImageUrl = data.cardFrontImageUrl || null;
            ctx().cardBackImageUrl = data.cardBackImageUrl || null;
            ctx().flippedCards = {};
            ctx().artDir = null;
            // Load the deck's theme so prompts use the correct suffix
            if (data.themeId) {
                await themes().loadThemeConfig(data.themeId);
            }
            // Ensure theme cardPool cards are in the deck (for older saved decks)
            let activeTheme = themes().getActiveTheme?.();
            if (activeTheme?.cardPool && data.cards) {
                let existingNames = new Set(data.cards.map(c => c.name + "|" + c.type));
                let missing = activeTheme.cardPool.filter(c =>
                    (c.type === "skill" || c.type === "magic" || (c.type === "item" && c.subtype === "consumable"))
                    && !existingNames.has(c.name + "|" + c.type)
                );
                if (missing.length > 0) {
                    data.cards.push(...missing);
                    data.cardCount = data.cards.length;
                    console.log("[CardGame v2] Added " + missing.length + " theme cardPool cards to deck");
                }
            }
            // Ensure threat creature cards are in the deck (for older saved decks)
            let getThreatCreatures = window.CardGame.Engine?.getThreatCreatures;
            if (getThreatCreatures && data.cards) {
                let threats = getThreatCreatures();
                let existingEncounters = new Set(data.cards.filter(c => c.type === "encounter").map(c => c.name));
                let missingThreats = threats.filter(t => !existingEncounters.has(t.name));
                if (missingThreats.length > 0) {
                    let threatCards = missingThreats.map(function(t) {
                        return {
                            type: "encounter", subtype: "threat", name: t.name, id: t.id,
                            atk: t.atk, def: t.def, hp: t.hp, imageIcon: t.imageIcon,
                            behavior: t.behavior, artPrompt: t.artPrompt, loot: t.loot,
                            _isThreatDef: true
                        };
                    });
                    data.cards.push(...threatCards);
                    data.cardCount = data.cards.length;
                    console.log("[CardGame v2] Added " + threatCards.length + " threat creature cards to deck");
                }
            }
            // Ensure scenario cards are in the deck (for older saved decks)
            let getScenarioCards = window.CardGame.Engine?.getScenarioCards;
            if (getScenarioCards && data.cards) {
                let scenarios = getScenarioCards();
                let existingScenarios = new Set(data.cards.filter(c => c.type === "scenario").map(c => c.id));
                let missingScenarios = scenarios.filter(s => !existingScenarios.has(s.id));
                if (missingScenarios.length > 0) {
                    let scenarioCards = missingScenarios.map(function(s) {
                        return {
                            type: "scenario", name: s.name, id: s.id,
                            effect: s.effect, description: s.description,
                            icon: s.icon, cardColor: s.cardColor,
                            bonus: s.bonus || null, bonusAmount: s.bonusAmount || null,
                            threatBonus: s.threatBonus != null ? s.threatBonus : null,
                            artPrompt: s.artPrompt, _isScenarioDef: true
                        };
                    });
                    data.cards.push(...scenarioCards);
                    data.cardCount = data.cards.length;
                    console.log("[CardGame v2] Added " + scenarioCards.length + " scenario cards to deck");
                }
            }
            // Ensure loot cards are in the deck (for older saved decks)
            if (getThreatCreatures && data.cards) {
                let threats = getThreatCreatures();
                let existingLoot = new Set(data.cards.filter(c => c.type === "loot").map(c => c.name));
                let seenLoot = new Set();
                let lootCards = [];
                threats.forEach(function(t) {
                    (t.loot || []).forEach(function(lootName) {
                        if (!seenLoot.has(lootName) && !existingLoot.has(lootName)) {
                            seenLoot.add(lootName);
                            lootCards.push({
                                type: "loot", name: lootName, source: t.name,
                                rarity: t.hp >= 18 ? "RARE" : t.hp >= 12 ? "UNCOMMON" : "COMMON",
                                effect: "Spoils from " + t.name,
                                artPrompt: "a " + lootName.toLowerCase() + ", item loot drop, fantasy treasure",
                                _isLootDef: true
                            });
                        }
                    });
                });
                if (lootCards.length > 0) {
                    data.cards.push(...lootCards);
                    data.cardCount = data.cards.length;
                    console.log("[CardGame v2] Added " + lootCards.length + " loot cards to deck");
                }
            }
            ctx().screen = "deckView";
            m.redraw();
            // Auto-refresh character cards from source objects
            await refreshAllCharacters(data);
        } else {
            page.toast("error", "Failed to load deck");
        }
        m.redraw();
    }

    // ── Play Deck (start game directly) ─────────────────────────────────
    async function playDeck(storageName) {
        let deckStorage = storage().deckStorage;
        let gameStorage = storage().gameStorage;
        let data = await deckStorage.load(storageName);
        if (data) {
            data.storageName = storageName;  // Ensure save path matches folder
            ctx().viewingDeck = data;
            // Load the deck's theme
            if (data.themeId) {
                await themes().loadThemeConfig(data.themeId);
            }
            // Clear old saves when starting a new game
            await gameStorage.deleteAll(storageName);
            // Reset game state and character selection for fresh start
            ctx().gameState = null;
            ctx().gameCharSelection = null;
            ctx().activeCampaign = null;
            ctx().screen = "game";
            m.redraw();
        } else {
            page.toast("error", "Failed to load deck");
        }
        m.redraw();
    }

    // ── Resume Game ────────────────────────────────────────────────────
    async function resumeGame(storageName) {
        console.log("[CardGame v2] resumeGame called with storageName:", storageName);
        let gameStorage = storage().gameStorage;
        let deckStorage = storage().deckStorage;
        let campaignStorage = storage().campaignStorage;
        let saveData = await gameStorage.load(storageName);
        console.log("[CardGame v2] resumeGame: saveData loaded:", saveData ? "yes (round " + (saveData.roundNumber || "?") + ", phase " + (saveData.phase || "?") + ")" : "NULL");
        if (!saveData) {
            page.toast("error", "No save found");
            return;
        }
        let data = await deckStorage.load(storageName);
        console.log("[CardGame v2] resumeGame: deck loaded:", data ? "yes (" + (data.deckName || storageName) + ")" : "NULL");
        if (!data) {
            page.toast("error", "Failed to load deck for resume");
            return;
        }
        data.storageName = storageName;  // Ensure save path matches folder
        ctx().viewingDeck = data;
        if (data.themeId) {
            await themes().loadThemeConfig(data.themeId);
        }
        ctx().gameState = storage().deserializeGameState(saveData);
        ctx().gameCharSelection = null;
        ctx().activeCampaign = await campaignStorage.load(storageName);
        ctx().screen = "game";
        // Re-initialize LLM components for resumed game
        if (typeof ctx().initializeLLMComponents === "function") {
            ctx().initializeLLMComponents(ctx().gameState, ctx().viewingDeck);
        }
        m.redraw();
    }

    // ── Refresh All Characters ─────────────────────────────────────────
    async function refreshAllCharacters(deck) {
        if (!deck || !deck.cards) return;
        let charCards = deck.cards.filter(c => c.type === "character" && (c.sourceId || c._sourceChar));
        let refreshed = 0;
        for (let card of charCards) {
            let ok = await chars().refreshCharacterCard(card);
            if (ok) refreshed++;
        }
        if (refreshed > 0) {
            console.log("[CardGame v2] Refreshed " + refreshed + " character cards from source");
            m.redraw();
        }
    }

    // ── Deck List Component ────────────────────────────────────────────
    function DeckList() {
        return {
            oncreate() {
                // Always refresh deck list when component mounts
                loadSavedDecks();
            },
            view() {
                return m("div", [
                    m("div", { class: "cg2-toolbar" }, [
                        m("span", { style: { fontWeight: 700, fontSize: "16px" } }, "Your Decks"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            onclick() {
                                ctx().screen = "builder";
                                ctx().builderStep = 1;
                                chars().state.selectedChars = [];
                                ctx().builtDeck = null;
                                ctx().deckNameInput = "";
                                m.redraw();
                            }
                        }, [m("span", { class: "material-symbols-outlined", style: { fontSize: "16px", verticalAlign: "middle", marginRight: "4px" } }, "add"), "New Deck"]),
                        m("span", { style: { flex: 1 } }),
                        m("button", { class: "cg2-btn cg2-btn-sm", onclick() { themes().loadThemeList(); ctx().screen = "themeEditor"; m.redraw(); } }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "14px", verticalAlign: "middle", marginRight: "2px" } }, "palette"),
                            " Themes"
                        ]),
                        m("button", { class: "cg2-btn cg2-btn-sm", style: { marginLeft: "4px" }, onclick() { ctx().testDeck = null; ctx().testDeckName = null; ctx().screen = "test"; m.redraw(); } }, [
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

    // ── Expose on CardGame namespace ─────────────────────────────────
    Object.assign(window.CardGame.UI, {
        deckListState: {
            get savedDecks() { return savedDecks; },
            set savedDecks(v) { savedDecks = v; },
            get decksLoading() { return decksLoading; },
            set decksLoading(v) { decksLoading = v; }
        },
        loadSavedDecks,
        saveDeck,
        deleteDeck,
        deleteGameChats,
        viewDeck,
        playDeck,
        resumeGame,
        refreshAllCharacters,
        DeckList
    });

    console.log('[CardGame] UI/deckList module loaded');

})();
