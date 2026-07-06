/**
 * CardGame UI - Builder Steps
 * Theme selection, character picker, card editor, and review/build steps.
 * Extracted from the monolithic cardGame-v2.js (lines ~1890-2396).
 *
 * Dependencies:
 *   - CardGame.Constants (CARD_TYPES)
 *   - CardGame.Themes (loadThemeConfig, applyThemeOutfits, state.activeTheme, state.applyingOutfits)
 *   - CardGame.Characters (state.*, getPortraitUrl, mapStats, getCharId, clampStat, str,
 *                          generateCharactersFromTemplates, persistGeneratedCharacters,
 *                          assembleStarterDeck)
 *   - CardGame.Rendering (CardFace)
 *   - CardGame.UI (saveDeck)
 *   - CardGame.ctx (shared mutable context: screen, buildingDeck, builtDeck, deckNameInput, builderStep)
 *
 * Exposes: window.CardGame.UI.{builderState, BuilderThemeStep, BuilderCharacterStep,
 *   BuilderReviewStep, CardEditorFields}
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    const C = window.CardGame.Constants;

    // ── State ──────────────────────────────────────────────────────────
    let editingCard = null;         // card object being edited (null = not editing)
    let editingCardIndex = -1;      // index in builtDeck.cards (-1 = new card)
    let addCardTypeOpen = false;    // dropdown open for "Add Card" type selector

    // ── Helpers: resolve shared context ─────────────────────────────────
    function ctx() { return window.CardGame.ctx || {}; }
    function themes() { return window.CardGame.Themes || {}; }
    function chars() { return window.CardGame.Characters || {}; }

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

    // ── Card Editor Functions ───────────────────────────────────────────

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
        let builtDeck = ctx().builtDeck;
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
        let builtDeck = ctx().builtDeck;
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

    // ── CardEditorFields Component ─────────────────────────────────────
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

    // ── Builder Step 1: Theme Select ───────────────────────────────────
    function BuilderThemeStep() {
        return {
            view() {
                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 1: Select Theme"),
                    m("div", { class: "cg2-theme-grid" }, [
                        themeOption("high-fantasy", "High Fantasy", "Classic high fantasy with vibrant magic and epic adventures", "auto_fix_high"),
                        themeOption("dark-medieval", "Dark Medieval", "Gritty medieval setting with low magic and high mortality", "skull"),
                        themeOption("sci-fi", "Sci-Fi", "Far-future space setting with psionic powers and tech items", "rocket_launch"),
                        themeOption("post-apocalypse", "Post Apocalypse", "Harsh wasteland survival with scavenged gear and mutant threats", "destruction"),
                        themeOption("steampunk", "Steampunk", "Victorian industrial setting with brass gadgets, clockwork and psionic powers", "settings"),
                        themeOption("cyberpunk", "Cyberpunk", "Neon-lit dystopian future with megacorps, cybernetics and hacking", "memory"),
                        themeOption("space-opera", "Space Opera", "Epic galactic adventure with alien civilizations and psionic powers", "globe"),
                        themeOption("horror", "Horror", "Gothic horror with supernatural creatures and eldritch abominations", "nights_stay")
                    ]),
                    m("div", { class: "cg2-builder-nav" }, [
                        m("button", { class: "cg2-btn", onclick: () => { ctx().screen = "deckList"; m.redraw(); } }, "Cancel"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            onclick() { ctx().builderStep = 2; chars().loadAvailableCharacters(); m.redraw(); }
                        }, "Next: Generate Characters \u2192")
                    ])
                ]);
            }
        };
    }

    function themeOption(id, name, desc, icon) {
        let activeTheme = themes().state.activeTheme;
        let selected = activeTheme.themeId === id;
        return m("div", {
            class: "cg2-theme-card" + (selected ? " cg2-theme-selected" : ""),
            onclick() {
                themes().loadThemeConfig(id);
                m.redraw();
            }
        }, [
            m("span", { class: "material-symbols-outlined", style: { fontSize: "32px", color: selected ? "#B8860B" : "#888" } }, icon),
            m("div", { class: "cg2-theme-name" }, name),
            m("div", { class: "cg2-theme-desc" }, desc),
            selected ? m("div", { class: "cg2-theme-badge" }, "\u2713 Selected") : null
        ]);
    }

    // ── Builder Step 2: Character Picker ───────────────────────────────
    function BuilderCharacterStep() {
        return {
            view() {
                let charsState = chars().state;
                let availableCharacters = charsState.availableCharacters;
                let selectedChars = charsState.selectedChars;
                let charsLoading = charsState.charsLoading;
                let generatingCharacters = charsState.generatingCharacters;
                let activeTheme = themes().state.activeTheme;
                let applyingOutfits = themes().state.applyingOutfits;
                let buildingDeck = ctx().buildingDeck;
                let deckNameInput = ctx().deckNameInput;

                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 2: Generate & Select Character"),
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
                                const generated = await chars().generateCharactersFromTemplates(themeId, 8);
                                if (generated.length > 0) {
                                    // Add generated characters to available list and auto-select them
                                    for (const charPerson of generated) {
                                        charsState.availableCharacters.push(charPerson);
                                        if (charsState.selectedChars.length < 8) {
                                            charsState.selectedChars.push(charPerson);
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
                                    const generated = await chars().generateCharactersFromTemplates(themeId, 1);
                                    if (generated.length > 0) {
                                        charsState.availableCharacters.push(generated[0]);
                                        if (charsState.selectedChars.length < 8) {
                                            charsState.selectedChars.push(generated[0]);
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
                                let portrait = chars().getPortraitUrl(ch, "128x128");
                                return m("div", { class: "cg2-selected-chip", key: chars().getCharId(ch) }, [
                                    portrait
                                        ? m("img", { src: portrait })
                                        : m("span", { class: "cg2-chip-icon" }, m("span", { class: "material-symbols-outlined", style: { fontSize: "14px" } }, "person")),
                                    m("span", ch.name || "Unnamed"),
                                    m("span", {
                                        class: "cg2-chip-remove material-symbols-outlined",
                                        onclick(e) {
                                            e.stopPropagation();
                                            charsState.selectedChars = charsState.selectedChars.filter(s => chars().getCharId(s) !== chars().getCharId(ch));
                                            m.redraw();
                                        }
                                    }, "close")
                                ]);
                            })
                        )
                    ] : null,
                    // Deck name input — needed before character persistence
                    m("div", { class: "cg2-deck-name-row", style: { marginTop: "16px" } }, [
                        m("label", { class: "cg2-deck-name-label" }, "Deck Name"),
                        m("input", {
                            class: "cg2-deck-name-input",
                            type: "text",
                            value: deckNameInput,
                            oninput(e) { ctx().deckNameInput = e.target.value; },
                            placeholder: "Enter a name for this deck"
                        })
                    ]),
                    m("div", { class: "cg2-builder-nav" }, [
                        m("button", { class: "cg2-btn", onclick: () => { ctx().builderStep = 1; m.redraw(); } }, "\u2190 Back"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: !selectedChars.length || applyingOutfits || buildingDeck || !deckNameInput.trim(),
                            async onclick() {
                                if (!selectedChars.length || applyingOutfits || buildingDeck || !deckNameInput.trim()) return;

                                // Persist any generated characters first (need real IDs for outfits)
                                const unpersisted = selectedChars.filter(ch => ch._tempId && !ch.objectId);
                                let safeDeckName = deckNameInput.trim().replace(/[^a-zA-Z0-9_\-]/g, "_");
                                if (unpersisted.length > 0) {
                                    ctx().buildingDeck = true;
                                    m.redraw();
                                    page.toast("info", "Saving characters...");
                                    const persisted = await chars().persistGeneratedCharacters(unpersisted, safeDeckName);
                                    // Track the group name used for persistence (for rename if user changes name in step 3)
                                    ctx()._charGroupName = safeDeckName;
                                    // Update selectedChars with persisted versions (have objectId now)
                                    for (const p of persisted) {
                                        const idx = selectedChars.findIndex(ch => ch._tempId && ch.name === p.name);
                                        if (idx >= 0) {
                                            selectedChars[idx] = p;
                                        }
                                    }
                                    ctx().buildingDeck = false;
                                }

                                ctx().builtDeck = chars().assembleStarterDeck(selectedChars, activeTheme);
                                ctx().builtDeck.deckName = safeDeckName || ctx().builtDeck.deckName;
                                if (activeTheme && activeTheme.outfits) {
                                    await themes().applyThemeOutfits(ctx().builtDeck, activeTheme, true);
                                }
                                ctx().builderStep = 3;
                                m.redraw();
                            }
                        }, buildingDeck ? "Saving Characters..." : (applyingOutfits ? "Applying Outfits..." : "Next: Outfit & Review \u2192"))
                    ])
                ]);
            }
        };
    }

    function charPickerCard(ch) {
        let portrait = chars().getPortraitUrl(ch, "128x128");
        let stats = chars().mapStats(ch.statistics);
        let chId = chars().getCharId(ch);
        let selectedChars = chars().state.selectedChars;
        let isSelected = selectedChars.some(s => chars().getCharId(s) === chId);
        return m("div", {
            class: "cg2-char-card" + (isSelected ? " cg2-char-selected" : ""),
            onclick() {
                if (isSelected) {
                    chars().state.selectedChars = selectedChars.filter(s => chars().getCharId(s) !== chId);
                } else if (selectedChars.length < 8) {
                    chars().state.selectedChars = [...selectedChars, ch];
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
                chars().str(ch.gender).charAt(0).toUpperCase(),
                " ",
                chars().str(ch.race).replace(/_/g, " "),
                ch.age ? ", " + ch.age : "",
                ch.trades && ch.trades[0] ? " - " + ch.trades[0] : ""
            ]),
            m("div", { class: "cg2-char-stats-mini" }, [
                ["STR", "AGI", "END"].map(s => m("span", { key: s }, s + ":" + chars().clampStat(stats[s]))),
                m("br"),
                ["INT", "MAG", "CHA"].map(s => m("span", { key: s }, s + ":" + chars().clampStat(stats[s])))
            ]),
            isSelected ? m("div", { class: "cg2-char-badge" }, "\u2713") : null,
            ch._customChar ? m("div", { class: "cg2-char-source-badge" }, "Custom") : null,
            ch._tempId ? m("div", { class: "cg2-char-source-badge", style: { background: "#4a7c59" } }, "Generated") : null
        ]);
    }

    // ── Builder Step 3: Review & Build ─────────────────────────────────
    function BuilderReviewStep() {
        return {
            view() {
                let builtDeck = ctx().builtDeck;
                let buildingDeck = ctx().buildingDeck;
                let deckNameInput = ctx().deckNameInput;
                let CARD_TYPES = C.CARD_TYPES;
                let CardFace = window.CardGame.Rendering.CardFace;

                if (!builtDeck) return m("div", "No deck assembled");
                let cards = builtDeck.cards || [];
                let charCard = cards.find(c => c.type === "character");
                return m("div", [
                    m("div", { class: "cg2-section-title" }, "Step 3: Outfit & Review Deck"),
                    m("div", { class: "cg2-deck-name-row" }, [
                        m("label", { class: "cg2-deck-name-label" }, "Deck Name"),
                        m("input", {
                            class: "cg2-deck-name-input",
                            type: "text",
                            value: deckNameInput,
                            oninput(e) { ctx().deckNameInput = e.target.value; },
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
                        m("button", { class: "cg2-btn", onclick: () => { ctx().builderStep = 2; m.redraw(); } }, "\u2190 Back"),
                        m("button", {
                            class: "cg2-btn cg2-btn-primary",
                            disabled: buildingDeck || !deckNameInput.trim(),
                            onclick() {
                                ctx().builtDeck.deckName = deckNameInput.trim().replace(/[^a-zA-Z0-9_\-]/g, "_");
                                CardGame.UI.saveDeck(ctx().builtDeck);
                            }
                        }, buildingDeck ? "Saving..." : "Build & Save Deck")
                    ])
                ]);
            }
        };
    }

    // ── Expose on CardGame namespace ─────────────────────────────────
    Object.assign(window.CardGame.UI, {
        builderState: {
            get editingCard() { return editingCard; },
            set editingCard(v) { editingCard = v; },
            get editingCardIndex() { return editingCardIndex; },
            set editingCardIndex(v) { editingCardIndex = v; },
            get addCardTypeOpen() { return addCardTypeOpen; },
            set addCardTypeOpen(v) { addCardTypeOpen = v; }
        },
        BuilderThemeStep,
        BuilderCharacterStep,
        BuilderReviewStep,
        CardEditorFields
    });

    console.log('[CardGame] UI/builder module loaded');

})();
