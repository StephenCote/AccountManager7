// Card Game Interface
// Character cards, card stacks, and deck management

(function(){
    // Configuration
    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

    // State
    let deck = [];           // Draw pile - cards to draw from
    let userHand = [];       // User's hand - limited to 1 character
    let gameHand = [];       // Game/AI hand - can have multiple characters
    let discard = [];        // Discard pile - cards that have been discarded
    let selectedCard = null; // Currently selected card for detail view
    let previewCard = null;  // Card being previewed in deck (not drawn)
    let cardView = 'profile'; // profile, stats, traits, narrative, instincts, apparel
    let isLoading = false;
    let fullMode = false;
    let previewIndex = 0;    // Current index in deck for preview/flip through

    // Game rules
    const USER_HAND_LIMIT = 1;   // User can only have 1 character
    const GAME_HAND_LIMIT = 3;   // Game can have up to 3 characters

    // Drag state
    let draggedCard = null;
    let dragSource = null;   // 'deck', 'userHand', 'gameHand', 'discard'
    let dropTarget = null;   // 'userHand', 'gameHand', 'discard', 'selected'

    // Action deck state
    let actionDeck = [];        // Action cards available to draw
    let actionHand = [];        // User's action cards in hand (not yet played)
    let actionHandIndex = 0;    // Index of currently viewed card in action hand
    let systemActionHand = [];  // System's action cards in hand
    let systemActionHandIndex = 0; // Index for system's action hand
    let playedActions = [];     // User's played actions (interaction objects)
    let systemPlayedActions = []; // System's played actions (interaction objects)
    let actionDiscard = [];     // Discarded action cards
    let actionPreview = null;   // Preview card in action deck
    let actionPreviewIndex = 0; // Current preview index
    let selectedAction = null;  // Currently selected action for execution
    let pendingInteraction = null; // Interaction being set up

    // Item deck state
    let itemDeck = [];          // Item cards available to draw
    let itemHand = [];          // User's items in hand (drawn but not played to tray)
    let itemTray = [];          // User's items played to tray
    let systemItems = [];       // System's items in hand
    let systemItemHandIndex = 0; // Index for system's item hand
    let itemDiscard = [];       // Discarded items
    let itemPreviewIndex = 0;   // Current preview index in deck
    let itemHandIndex = 0;      // Current preview index in hand

    // Apparel deck state
    let apparelDeck = [];       // Apparel cards available to draw
    let apparelHand = [];       // User's apparel in hand (drawn but not played to tray)
    let apparelTray = [];       // User's apparel played to tray
    let systemApparel = [];     // System's apparel in hand
    let systemApparelHandIndex = 0; // Index for system's apparel hand
    let apparelDiscard = [];    // Discarded apparel
    let apparelPreviewIndex = 0; // Current preview index in deck
    let apparelHandIndex = 0;   // Current preview index in hand

    // Selected card for detail view (can be action, item, or apparel)
    let selectedDetailCard = null;  // The card being viewed in detail
    let selectedDetailType = null;  // 'action', 'item', or 'apparel'

    // Fully loaded narrative for the selected character
    let loadedNarrative = null;     // Full narrative object loaded from server
    let loadedNarrativeCharId = null; // objectId of character whose narrative is loaded
    let narrativeLoading = false;   // Loading state for narrative

    // Active pile selection for consolidated view
    let activePile = 'character'; // 'character', 'action', 'item', 'apparel'

    // Action card types (these become cards in the action deck)
    // Each action appears twice in the deck
    const ACTION_CARD_TYPES = [
        {type: "TALK", label: "Talk", icon: "forum", description: "Have a conversation"},
        {type: "BEFRIEND", label: "Befriend", icon: "handshake", description: "Attempt to make a friend"},
        {type: "BARTER", label: "Trade", icon: "swap_horiz", description: "Exchange goods or services"},
        {type: "COMBAT", label: "Fight", icon: "swords", description: "Engage in combat"},
        {type: "DEFEND", label: "Defend", icon: "shield", description: "Take a defensive stance"},
        {type: "HELP", label: "Help", icon: "volunteer_activism", description: "Offer assistance"},
        {type: "COOPERATE", label: "Cooperate", icon: "group_work", description: "Work together on a task"},
        {type: "COMPETE", label: "Compete", icon: "trophy", description: "Challenge to a contest"},
        {type: "INVESTIGATE", label: "Investigate", icon: "search", description: "Examine or question"},
        {type: "ENTERTAIN", label: "Entertain", icon: "theater_comedy", description: "Provide amusement"},
        {type: "COERCE", label: "Coerce", icon: "gavel", description: "Force compliance"},
        {type: "THREATEN", label: "Threaten", icon: "warning", description: "Make a threat"},
        {type: "ROMANCE", label: "Romance", icon: "favorite", description: "Show romantic interest"},
        {type: "SOCIALIZE", label: "Socialize", icon: "groups", description: "General social interaction"},
        {type: "MENTOR", label: "Mentor", icon: "school", description: "Teach or guide"},
        {type: "NEGOTIATE", label: "Negotiate", icon: "balance", description: "Reach an agreement"}
    ];

    // Chat dialog state
    let chatDialogOpen = false;
    let chatSession = null;      // Current chat request
    let chatMessages = [];       // Chat message history
    let chatPending = false;     // Waiting for response
    let chatActor = null;        // User's character
    let chatTarget = null;       // Target character

    // Save game state
    let currentSave = null;      // Current save game object
    let savedGames = [];         // List of available saves
    let saveDialogOpen = false;  // Save dialog visibility
    let saveDialogMode = 'list'; // 'list', 'save', 'new'
    let newSaveName = '';        // Name for new save

    // Tag filter state
    let activeTags = [];         // Active tags to filter characters by (e.g., ["nude"])

    // Card dimensions (3x5 aspect ratio)
    const CARD_WIDTH = 240;
    const CARD_HEIGHT = 400;

    // ==========================================
    // Utility Functions
    // ==========================================

    function getAge(char) {
        if (!char) return "?";
        // Use age property directly (game date is event-based, not real time)
        return char.age !== undefined && char.age !== null ? char.age : "?";
    }

    // Get display name for apparel (uses name directly)
    function getApparelDisplayName(apparel) {
        if (!apparel) return "Apparel";
        return apparel.name || apparel.type || apparel.category || "Apparel";
    }

    function getPortraitUrl(char, size) {
        if (!char || !char.profile || !char.profile.portrait) return null;
        let pp = char.profile.portrait;
        if (!pp.groupPath || !pp.name) return null;
        return g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
               "/data.data" + pp.groupPath + "/" + pp.name + "/" + (size || "256x256");
    }

    function shuffleArray(array) {
        let shuffled = [...array];
        for (let i = shuffled.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
        }
        return shuffled;
    }

    function formatStatValue(val) {
        if (val === undefined || val === null) return "-";
        return val;
    }

    function getStatColor(val, max) {
        if (val === undefined || val === null) return "bg-gray-300";
        let pct = (val / max) * 100;
        if (pct >= 80) return "bg-green-500";
        if (pct >= 60) return "bg-blue-500";
        if (pct >= 40) return "bg-yellow-500";
        if (pct >= 20) return "bg-orange-500";
        return "bg-red-500";
    }

    function getInstinctColor(val) {
        if (val === undefined || val === null) return "bg-gray-300";
        if (val > 50) return "bg-red-500";
        if (val > 20) return "bg-orange-400";
        if (val > -20) return "bg-gray-400";
        if (val > -50) return "bg-blue-400";
        return "bg-blue-600";
    }

    // ==========================================
    // Data Loading
    // ==========================================

    async function loadCharacters() {
        if (isLoading) return;
        isLoading = true;

        try {
            let popDir = await page.findObject("auth.group", "data", gridPath + "/Population");
            if (!popDir) {
                page.toast("error", "Population directory not found");
                isLoading = false;
                return;
            }

            let q = am7view.viewQuery("olio.charPerson");
            q.field("groupId", popDir.id);
            q.range(0, 50);
            // Request basic fields needed for card backs
            q.entity.request.push("profile", "name", "gender", "objectId", "tags");

            // Apply tag filters if any are active
            if (activeTags.length > 0) {
                activeTags.forEach(function(tag) {
                    q.field("tags.name", tag);
                });
            }

            let qr = await page.search(q);
            if (qr && qr.results) {
                deck = qr.results;
                userHand = [];
                gameHand = [];
                discard = [];
                previewIndex = 0;
                selectedCard = null;
                previewCard = null;
                let tagMsg = activeTags.length > 0 ? " (filtered by: " + activeTags.join(", ") + ")" : "";
                page.toast("success", "Loaded " + deck.length + " characters into deck" + tagMsg);
            }
        } catch (e) {
            console.error("Failed to load characters", e);
            page.toast("error", "Failed to load characters");
        }

        isLoading = false;
        m.redraw();
    }

    async function loadFullCharacter(char) {
        if (!char || !char.objectId) {
            console.warn("loadFullCharacter: invalid char or missing objectId", char);
            return char;
        }
        try {
            let q = am7view.viewQuery("olio.charPerson");
            q.field("objectId", char.objectId);
            // Request narrative and other needed fields including objectId
            q.entity.request.push("objectId", "narrative", "statistics", "store", "instinct", "personality");
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length > 0) {
                let full = qr.results[0];
                am7model.applyModelNames(full);

                // Load nested statistics model reference if it exists but isn't fully loaded
                if (full.statistics && full.statistics.objectId && !full.statistics.physicalStrength) {
                    let sq = am7view.viewQuery("olio.statistics");
                    sq.field("objectId", full.statistics.objectId);
                    let sqr = await page.search(sq);
                    if (sqr && sqr.results && sqr.results.length > 0) {
                        full.statistics = sqr.results[0];
                    }
                }

                return full;
            }
        } catch (e) {
            console.error("Failed to load full character", e);
        }
        return char;
    }

    // Load a character's store (items and apparel) and return them
    async function loadCharacterStore(char) {
        let result = { items: [], apparel: [] };
        if (!char || !char.store || !char.store.objectId) {
            return result;
        }

        try {
            // Load the full store object with items and apparel lists
            let sq = am7view.viewQuery("olio.store");
            sq.field("objectId", char.store.objectId);
            sq.entity.request.push("items", "apparel", "inventory");
            let sqr = await page.search(sq);

            if (sqr && sqr.results && sqr.results.length > 0) {
                let store = sqr.results[0];

                // Load full item data for each item in store
                if (store.items && store.items.length > 0) {
                    for (let itemRef of store.items) {
                        if (itemRef && itemRef.id) {
                            let iq = am7view.viewQuery("olio.item");
                            iq.field("id", itemRef.id);
                            // Explicitly request display fields including statistics and objectId
                            iq.entity.request.push("objectId", "name", "description", "category", "statistics", "qualities");
                            let iqr = await page.search(iq);
                            if (iqr && iqr.results && iqr.results.length > 0) {
                                let item = iqr.results[0];
                                // Load nested statistics if present
                                if (item.statistics && item.statistics.id && !item.statistics.damage) {
                                    let sq = am7view.viewQuery("olio.itemStatistics");
                                    sq.field("id", item.statistics.id);
                                    let sqr = await page.search(sq);
                                    if (sqr && sqr.results && sqr.results.length > 0) {
                                        item.statistics = sqr.results[0];
                                    }
                                }
                                result.items.push(item);
                            }
                        }
                    }
                }

                // Load full apparel data for each apparel in store
                if (store.apparel && store.apparel.length > 0) {
                    for (let apparelRef of store.apparel) {
                        if (apparelRef && apparelRef.id) {
                            let aq = am7view.viewQuery("olio.apparel");
                            aq.field("id", apparelRef.id);
                            aq.entity.request.push("objectId", "name", "type", "category", "description", "wearables");
                            let aqr = await page.search(aq);
                            if (aqr && aqr.results && aqr.results.length > 0) {
                                let apparel = aqr.results[0];
                                result.apparel.push(apparel);
                            }
                        }
                    }
                }
            }
        } catch (e) {
            console.error("Failed to load character store:", e);
        }

        return result;
    }

    async function loadItems() {
        try {
            let itemDir = await page.findObject("auth.group", "data", gridPath + "/Items");
            if (!itemDir) {
                return;
            }

            let q = am7view.viewQuery("olio.item");
            q.field("groupId", itemDir.id);
            q.range(0, 100);
            q.entity.request.push("name", "type", "category", "objectId", "description", "statistics");

            let qr = await page.search(q);
            if (qr && qr.results) {
                itemDeck = qr.results;
                itemHand = [];
                itemDiscard = [];
                itemPreviewIndex = 0;
            }
        } catch (e) {
            console.error("Failed to load items", e);
        }
    }

    async function loadApparel() {
        try {
            let apparelDir = await page.findObject("auth.group", "data", gridPath + "/Apparel");
            if (!apparelDir) {
                return;
            }

            let q = am7view.viewQuery("olio.apparel");
            q.field("groupId", apparelDir.id);
            q.range(0, 100);
            q.entity.request.push("name", "type", "category", "objectId", "description", "wearables");

            let qr = await page.search(q);
            if (qr && qr.results) {
                apparelDeck = qr.results;
                apparelHand = [];
                apparelDiscard = [];
                apparelPreviewIndex = 0;

                // Generate descriptions for apparel that don't have one
                for (let app of apparelDeck) {
                    if (!app.description && app.wearables && app.wearables.length > 0) {
                        await generateApparelDescription(app);
                    }
                }
            }
        } catch (e) {
            console.error("Failed to load apparel", e);
        }
    }

    // Generate a description for apparel based on its wearables (like setApparelDescription in olio.js)
    async function generateApparelDescription(app) {
        try {
            // Load full wearable data
            let wearOids = app.wearables.map(w => w.objectId).join(",");
            let wq = am7view.viewQuery("olio.wearable");
            wq.field("groupId", app.wearables[0].groupId);
            let fld = wq.field("objectId", wearOids);
            fld.comparator = "in";
            wq.range(0, 50);
            // Request the fields needed for description
            wq.entity.request.push("name", "objectId", "inuse", "color", "fabric", "pattern", "location", "qualities");

            let wqr = await page.search(wq);
            if (!wqr || !wqr.results || !wqr.results.length) {
                return;
            }

            let wears = wqr.results;
            // Filter to only wearables that are in use
            let inuseWears = wears.filter(w => w.inuse);

            if (inuseWears.length === 0) {
                app.description = "No apparel worn.";
            } else {
                let wdesc = inuseWears.map(w => describeWearable(w)).join(", ");
                app.description = "Worn apparel includes " + wdesc + ".";
            }

            // Persist the description using the same pattern as olio.js
            await page.patchObject({
                schema: "olio.apparel",
                id: app.id,
                description: app.description
            });
        } catch (e) {
            console.error("Failed to generate apparel description", e);
        }
    }

    // Describe a single wearable (from olio.js)
    function describeWearable(wear) {
        let qual = wear.qualities ? wear.qualities[0] : null;
        let opac = qual?.opacity || 0.0;
        let shin = qual?.shininess || 0.0;
        let col = wear?.color?.name?.toLowerCase() || "";

        if (col) {
            col = col.replaceAll(/([\^\(\)]*)/g, "");
        }
        let fab = wear?.fabric?.toLowerCase() || "";
        let pat = wear?.pattern?.name?.toLowerCase() || "";
        let loc = wear?.location?.[0]?.toLowerCase() || "";
        let name = wear?.name;
        if (name && name.indexOf("pierc") > -1) {
            name = loc + " piercing";
            pat = "";
        }
        let opacs = "";
        if (opac > 0.0 && opac <= 0.25) {
            opacs = " see-through";
        }
        let shins = "";
        if (shin >= 0.7) {
            shins = " shiny";
        }

        return (shins + opacs + " " + col + " " + pat + " " + fab + " " + name).replaceAll("  ", " ").trim();
    }

    // ==========================================
    // Character Card Component
    // ==========================================

    // Full card - shown when selected
    function CharacterCard() {
        return {
            view: function(vnode) {
                let char = vnode.attrs.character;
                let isSelected = vnode.attrs.selected;
                let onClick = vnode.attrs.onclick;

                if (!char) return "";

                let cardClass = "cg-card-full " + (isSelected ? "cg-card-selected" : "cg-card-unselected");

                return m("div", {
                    class: cardClass,
                    style: "width: " + CARD_WIDTH + "px; height: " + CARD_HEIGHT + "px;",
                    onclick: onClick
                }, [
                    renderCardContent(char)
                ]);
            }
        };
    }

    function renderCardContent(char) {
        switch (cardView) {
            case 'stats':
                return renderStatsView(char);
            case 'traits':
                return renderTraitsView(char);
            case 'narrative':
                return renderNarrativeView(char);
            case 'instincts':
                return renderInstinctsView(char);
            case 'apparel':
                return renderApparelView(char);
            default:
                return renderProfileView(char);
        }
    }

    function renderProfileView(char) {
        let portraitUrl = getPortraitUrl(char, "256x256");
        let age = getAge(char);
        let alignment = char.alignment || "Neutral";
        let trades = (char.trades || []).slice(0, 2).join(", ") || "None";

        return m("div", {class: "h-full flex flex-col"}, [
            // Header with name
            m("div", {class: "cg-card-header"}, [
                m("div", {class: "cg-card-title-truncate"}, char.name || "Unknown"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400"}, char.title || "")
            ]),

            // Portrait
            m("div", {class: "cg-card-body"}, [
                portraitUrl ?
                    m("div", {class: "relative max-h-full max-w-full"}, [
                        m("img", {
                            class: "max-h-full max-w-full object-contain rounded shadow-md cursor-pointer hover:opacity-90",
                            src: portraitUrl,
                            onclick: function(e) { e.stopPropagation(); openPortraitView(char); }
                        }),
                        // Reimage overlay button
                        m("button", {
                            class: "cg-portrait-overlay-btn",
                            onclick: function(e) { e.stopPropagation(); generatePortrait(); },
                            title: "Regenerate portrait"
                        }, m("span", {class: "material-symbols-outlined text-sm"}, "refresh"))
                    ]) :
                    m("div", {class: "flex flex-col items-center"}, [
                        m("span", {class: "material-symbols-outlined text-6xl text-gray-400 dark:text-gray-500 mb-2"}, "person"),
                        m("button", {
                            class: "cg-btn-primary",
                            onclick: function(e) { e.stopPropagation(); generatePortrait(); }
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "add_photo_alternate"),
                            m("span", {}, "Generate Portrait")
                        ])
                    ])
            ]),

            // Info section
            m("div", {class: "p-2 bg-amber-100 dark:bg-gray-800 space-y-1"}, [
                m("div", {class: "flex justify-between text-sm"}, [
                    m("span", {class: "text-gray-600 dark:text-gray-400"}, "Age:"),
                    m("span", {class: "text-gray-800 dark:text-gray-200"}, age + " years")
                ]),
                m("div", {class: "flex justify-between text-sm"}, [
                    m("span", {class: "text-gray-600 dark:text-gray-400"}, "Gender:"),
                    m("span", {class: "text-gray-800 dark:text-gray-200 capitalize"}, char.gender || "Unknown")
                ]),
                m("div", {class: "flex justify-between text-sm"}, [
                    m("span", {class: "text-gray-600 dark:text-gray-400"}, "Alignment:"),
                    m("span", {class: "text-gray-800 dark:text-gray-200"}, alignment)
                ]),
                m("div", {class: "flex justify-between text-sm"}, [
                    m("span", {class: "text-gray-600 dark:text-gray-400"}, "Trade:"),
                    m("span", {class: "text-gray-800 dark:text-gray-200 truncate ml-2"}, trades)
                ]),
                // Appearance
                char.eyeColor || char.hairColor ? m("div", {class: "cg-card-divider-alt"}, [
                    char.eyeColor ? m("span", {}, (char.eyeColor.name || "") + " eyes") : "",
                    char.eyeColor && char.hairColor ? " / " : "",
                    char.hairColor ? m("span", {}, (char.hairColor.name || "") + " hair") : ""
                ]) : ""
            ])
        ]);
    }

    function renderStatsView(char) {
        let stats = char.statistics || {};
        let statFields = [
            {name: "physicalStrength", label: "STR", icon: "fitness_center"},
            {name: "physicalEndurance", label: "END", icon: "favorite"},
            {name: "agility", label: "AGI", icon: "directions_run"},
            {name: "speed", label: "SPD", icon: "speed"},
            {name: "manualDexterity", label: "DEX", icon: "touch_app"},
            {name: "mentalStrength", label: "MEN", icon: "psychology"},
            {name: "mentalEndurance", label: "WIL", icon: "self_improvement"},
            {name: "intelligence", label: "INT", icon: "lightbulb"},
            {name: "wisdom", label: "WIS", icon: "school"},
            {name: "charisma", label: "CHA", icon: "groups"}
        ];

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "cg-card-header"}, [
                m("div", {class: "cg-card-title"}, "Statistics"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 truncate"}, char.name)
            ]),

            // Stats list
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-2"},
                statFields.map(function(sf) {
                    let val = stats[sf.name];
                    let pct = val !== undefined ? (val / 20) * 100 : 0;

                    return m("div", {class: "flex items-center space-x-2"}, [
                        m("span", {class: "material-symbols-outlined cg-stat-icon"}, sf.icon),
                        m("span", {class: "w-8 text-xs text-gray-700 dark:text-gray-300"}, sf.label),
                        m("div", {class: "cg-stat-bar-container"}, [
                            m("div", {
                                class: "h-full " + getStatColor(val, 20),
                                style: "width: " + pct + "%"
                            })
                        ]),
                        m("span", {class: "cg-stat-label"}, formatStatValue(val))
                    ]);
                })
            ),

            // Needs/State bars from game state
            m("div", {class: "cg-card-footer-section text-xs space-y-1"}, (function() {
                let gs = char._gameState || {};
                let needFields = [
                    {name: "health", label: "HP", color: "bg-green-500", invert: false},
                    {name: "energy", label: "NRG", color: "bg-blue-500", invert: false},
                    {name: "hunger", label: "HNG", color: "bg-orange-500", invert: true},
                    {name: "thirst", label: "THR", color: "bg-cyan-500", invert: true},
                    {name: "fatigue", label: "FTG", color: "bg-purple-500", invert: true}
                ];
                return needFields.map(function(nf) {
                    let val = gs[nf.name];
                    if (val === undefined || val === null) val = nf.invert ? 0 : 1;
                    let pct = val * 100;
                    let barColor = nf.color;
                    // For inverted needs (hunger, thirst, fatigue), high = bad
                    if (nf.invert && pct > 70) barColor = "bg-red-500";
                    else if (!nf.invert && pct < 30) barColor = "bg-red-500";
                    return m("div", {class: "flex items-center space-x-1"}, [
                        m("span", {class: "w-7 text-gray-600 dark:text-gray-400"}, nf.label),
                        m("div", {class: "flex-1 h-2 bg-gray-200 dark:bg-gray-700 rounded"}, [
                            m("div", {
                                class: "h-full rounded " + barColor,
                                style: "width:" + pct + "%"
                            })
                        ]),
                        m("span", {class: "w-8 text-right text-gray-600 dark:text-gray-400"}, Math.round(pct) + "%")
                    ]);
                });
            })())
        ]);
    }

    function renderTraitsView(char) {
        let traits = char.traits || [];

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "cg-card-header"}, [
                m("div", {class: "cg-card-title"}, "Traits"),
                m("div", {class: "cg-nav-label truncate"}, char.name)
            ]),

            // Traits list
            m("div", {class: "flex-1 overflow-y-auto p-2"}, [
                traits.length === 0 ?
                    m("div", {class: "text-center text-gray-500 dark:text-gray-400 py-4"}, "No traits defined") :
                    m("div", {class: "flex flex-wrap gap-2"},
                        traits.map(function(trait) {
                            let tname = trait.name || trait;
                            return m("span", {class: "cg-trait-tag"}, tname);
                        })
                    )
            ]),

            // Alignment section
            m("div", {class: "cg-card-footer-section"}, [
                m("div", {class: "cg-nav-label mb-1"}, "Alignment"),
                m("div", {class: "text-sm text-gray-800 dark:text-gray-200"}, char.alignment || "Neutral")
            ])
        ]);
    }

    // Load full narrative object from server
    async function loadFullNarrative(char) {
        if (!char || !char.narrative || !char.narrative.objectId) {
            loadedNarrative = null;
            loadedNarrativeCharId = null;
            return;
        }

        // Skip if already loaded for this character
        if (loadedNarrativeCharId === char.objectId && loadedNarrative) {
            return;
        }

        narrativeLoading = true;
        m.redraw();

        try {
            let q = am7view.viewQuery("olio.narrative");
            q.field("objectId", char.narrative.objectId);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length > 0) {
                loadedNarrative = qr.results[0];
                loadedNarrativeCharId = char.objectId;
            }
        } catch (e) {
            console.error("Failed to load full narrative:", e);
        }

        narrativeLoading = false;
        m.redraw();
    }

    function renderNarrativeView(char) {
        // Use loaded narrative if available and matches current character, otherwise use char.narrative
        let narrative = (loadedNarrativeCharId === char.objectId && loadedNarrative)
            ? loadedNarrative
            : (char.narrative || {});
        let hasNarrative = narrative.physicalDescription || narrative.outfitDescription || narrative.statisticsDescription;

        // Trigger load of full narrative if not loaded yet
        if (char.narrative && char.narrative.objectId && loadedNarrativeCharId !== char.objectId && !narrativeLoading) {
            loadFullNarrative(char);
        }

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "cg-card-header"}, [
                m("div", {class: "cg-card-title"}, "Narrative"),
                m("div", {class: "cg-nav-label truncate"}, char.name)
            ]),

            // Narrative content
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-3 text-sm"}, [
                // Loading indicator
                narrativeLoading ? m("div", {class: "flex items-center justify-center py-4"}, [
                    m("span", {class: "material-symbols-outlined text-2xl text-gray-400 animate-spin"}, "progress_activity"),
                    m("span", {class: "ml-2 text-gray-500"}, "Loading narrative...")
                ]) : "",

                !narrativeLoading && narrative.physicalDescription ? m("div", {}, [
                    m("div", {class: "cg-nav-label mb-1"}, "Physical"),
                    m("div", {class: "text-gray-800 dark:text-gray-200"}, narrative.physicalDescription)
                ]) : "",

                !narrativeLoading && narrative.outfitDescription ? m("div", {}, [
                    m("div", {class: "cg-nav-label mb-1"}, "Outfit"),
                    m("div", {class: "text-gray-800 dark:text-gray-200"}, narrative.outfitDescription)
                ]) : "",

                !narrativeLoading && narrative.statisticsDescription ? m("div", {}, [
                    m("div", {class: "cg-nav-label mb-1"}, "Abilities"),
                    m("div", {class: "text-gray-800 dark:text-gray-200"}, narrative.statisticsDescription)
                ]) : "",

                !narrativeLoading && !hasNarrative ?
                    m("div", {class: "flex flex-col items-center justify-center py-8"}, [
                        m("span", {class: "material-symbols-outlined text-5xl text-gray-400 dark:text-gray-500 mb-3"}, "auto_stories"),
                        m("div", {class: "text-gray-500 dark:text-gray-400 mb-3"}, "No narrative generated"),
                        m("button", {
                            class: "cg-btn-primary text-sm flex items-center space-x-1",
                            onclick: function(e) { e.stopPropagation(); generateNarrative(); }
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "edit_note"),
                            m("span", {}, "Generate Narrative")
                        ])
                    ]) : ""
            ])
        ]);
    }

    function renderInstinctsView(char) {
        let instinct = char.instinct || {};
        let instinctFields = [
            {name: "fight", label: "Fight", icon: "swords"},
            {name: "flight", label: "Flight", icon: "directions_run"},
            {name: "feed", label: "Feed", icon: "restaurant"},
            {name: "drink", label: "Drink", icon: "water_drop"},
            {name: "sleep", label: "Sleep", icon: "bedtime"},
            {name: "hide", label: "Hide", icon: "visibility_off"},
            {name: "mate", label: "Mate", icon: "favorite"},
            {name: "herd", label: "Herd", icon: "groups"},
            {name: "protect", label: "Protect", icon: "shield"},
            {name: "cooperate", label: "Cooperate", icon: "handshake"}
        ];

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "cg-card-header"}, [
                m("div", {class: "cg-card-title"}, "Instincts"),
                m("div", {class: "cg-nav-label truncate"}, char.name)
            ]),

            // Instincts list
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-2"},
                instinctFields.map(function(inst) {
                    let val = instinct[inst.name];
                    // Instincts range from -100 to 100, center at 0
                    let displayVal = val !== undefined ? val : 0;
                    let leftPct = displayVal < 0 ? 50 + (displayVal / 2) : 50;
                    let widthPct = Math.abs(displayVal) / 2;

                    return m("div", {class: "flex items-center space-x-2"}, [
                        m("span", {class: "material-symbols-outlined cg-stat-icon"}, inst.icon),
                        m("span", {class: "w-16 text-xs text-gray-700 dark:text-gray-300"}, inst.label),
                        m("div", {class: "cg-stat-bar-thin"}, [
                            // Center line
                            m("div", {class: "absolute left-1/2 top-0 bottom-0 w-px bg-gray-400 dark:bg-gray-500"}),
                            // Value bar
                            m("div", {
                                class: "absolute h-full " + getInstinctColor(displayVal),
                                style: "left: " + leftPct + "%; width: " + widthPct + "%"
                            })
                        ]),
                        m("span", {class: "cg-stat-label-wide"}, val !== undefined ? Math.round(val) : "-")
                    ]);
                })
            )
        ]);
    }

    function renderApparelView(char) {
        // Try to find apparel from different sources - apparelHand/apparelTray have full data
        let apparel = null;
        if (apparelHand.length > 0) {
            apparel = apparelHand[0];
        } else if (apparelTray.length > 0) {
            apparel = apparelTray[0];
        } else if (char.store && char.store.apparel && char.store.apparel.length > 0) {
            apparel = char.store.apparel[0];
        }
        let outfitDesc = char.narrative ? char.narrative.outfitDescription : null;

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "cg-card-header"}, [
                m("div", {class: "cg-card-title"}, "Apparel"),
                m("div", {class: "cg-nav-label truncate"}, char.name)
            ]),

            // Apparel content
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-3 text-sm"}, [
                // Outfit description from narrative
                outfitDesc ? m("div", {}, [
                    m("div", {class: "cg-nav-label mb-1"}, "Current Outfit"),
                    m("div", {class: "text-gray-800 dark:text-gray-200 text-xs leading-relaxed"}, outfitDesc)
                ]) : m("div", {class: "text-gray-500 dark:text-gray-400 text-center py-2"}, "No outfit description"),

                // Apparel info
                apparel ? m("div", {class: "cg-card-divider"}, [
                    m("div", {class: "cg-nav-label mb-1"}, "Apparel Details"),
                    apparel.description ?
                        m("div", {class: "text-gray-800 dark:text-gray-200 text-xs"}, apparel.description) :
                        m("div", {class: "text-gray-500 dark:text-gray-400 text-xs italic"}, "No description set")
                ]) : ""
            ]),

            // Dress up/down buttons
            m("div", {class: "cg-card-footer-section"}, [
                m("div", {class: "cg-nav-label mb-2 text-center"}, "Change Clothing Level"),
                m("div", {class: "flex justify-center space-x-2"}, [
                    m("button", {
                        class: "cg-btn-primary text-xs flex items-center space-x-1",
                        onclick: function(e) { e.stopPropagation(); dressCharacterUp(); },
                        title: "Add more clothing"
                    }, [
                        m("span", {class: "material-symbols-outlined text-sm"}, "add"),
                        m("span", {}, "Dress Up")
                    ]),
                    m("button", {
                        class: "cg-btn-orange text-xs flex items-center space-x-1",
                        onclick: function(e) { e.stopPropagation(); dressCharacterDown(); },
                        title: "Remove clothing"
                    }, [
                        m("span", {class: "material-symbols-outlined text-sm"}, "remove"),
                        m("span", {}, "Dress Down")
                    ])
                ])
            ])
        ]);
    }

    // ==========================================
    // Card Stack Components
    // ==========================================

    // Drag/drop handlers
    function handleDragStart(e, card, source) {
        draggedCard = card;
        dragSource = source;
        e.dataTransfer.effectAllowed = 'move';
        // Use objectId for items/apparel, id for action cards
        e.dataTransfer.setData('text/plain', card.objectId || card.id || '');
        e.target.style.opacity = '0.5';
    }

    function handleDragEnd(e) {
        e.target.style.opacity = '1';
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDragOver(e, target) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        if (dropTarget !== target) {
            dropTarget = target;
            m.redraw();
        }
    }

    function handleDragLeave(e) {
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnUserHand(e) {
        e.preventDefault();
        if (draggedCard && (dragSource === 'deck' || dragSource === 'characterDeck')) {
            // Check user hand limit
            if (userHand.length >= USER_HAND_LIMIT) {
                page.toast("warn", "Your hand is full (limit: " + USER_HAND_LIMIT + ")");
            } else {
                // Draw the dragged card from deck to user hand
                let idx = deck.findIndex(c => c.objectId === draggedCard.objectId);
                if (idx > -1) {
                    let drawn = deck.splice(idx, 1)[0];
                    userHand.push(drawn);
                    claimCharacter(drawn.objectId);
                    refreshCharacterState(drawn);
                    if (previewIndex >= deck.length && deck.length > 0) {
                        previewIndex = 0;
                    }
                    previewCard = deck.length > 0 ? deck[previewIndex] : null;
                    selectCard(drawn);
                    page.toast("info", "Drew: " + drawn.name);
                }
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnGameHand(e) {
        e.preventDefault();
        if (draggedCard && (dragSource === 'deck' || dragSource === 'characterDeck')) {
            // Check game hand limit
            if (gameHand.length >= GAME_HAND_LIMIT) {
                page.toast("warn", "Game hand is full (limit: " + GAME_HAND_LIMIT + ")");
            } else {
                // Draw the dragged card from deck to game hand
                let idx = deck.findIndex(c => c.objectId === draggedCard.objectId);
                if (idx > -1) {
                    let drawn = deck.splice(idx, 1)[0];
                    gameHand.push(drawn);
                    if (previewIndex >= deck.length && deck.length > 0) {
                        previewIndex = 0;
                    }
                    previewCard = deck.length > 0 ? deck[previewIndex] : null;
                    page.toast("info", "Game drew: " + drawn.name);
                }
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnDiscard(e) {
        e.preventDefault();
        if (draggedCard && (dragSource === 'userHand' || dragSource === 'gameHand')) {
            discardCard(draggedCard, dragSource);
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnSelected(e) {
        e.preventDefault();
        if (draggedCard && (dragSource === 'userHand' || dragSource === 'gameHand')) {
            selectCard(draggedCard);
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnActionHand(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'actionDeck') {
            // Draw from deck to hand
            let idx = actionDeck.findIndex(a => a.id === draggedCard.id);
            if (idx > -1) {
                let action = actionDeck.splice(idx, 1)[0];
                actionHand.push(action);
                updateActionPreview();
                page.toast("info", "Drew: " + action.label);
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnPlayedActions(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'actionHand') {
            // Play action from hand - creates an interaction object
            let idx = actionHand.findIndex(a => a.id === draggedCard.id);
            if (idx > -1) {
                let action = actionHand.splice(idx, 1)[0];
                let interaction = createInteractionFromAction(action);
                playedActions.push(interaction);
                page.toast("info", "Played: " + action.label);
                // Clear detail view after playing
                selectedDetailCard = null;
                selectedDetailType = null;
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnItemHand(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'itemDeck') {
            // Find and move from item deck to item hand
            let idx = itemDeck.findIndex(i => i.objectId === draggedCard.objectId);
            if (idx > -1) {
                let item = itemDeck.splice(idx, 1)[0];
                itemHand.push(item);
                page.toast("info", "Drew item: " + (item.name || "Unknown"));
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnApparelHand(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'apparelDeck') {
            // Find and move from apparel deck to apparel hand
            let idx = apparelDeck.findIndex(a => a.objectId === draggedCard.objectId);
            if (idx > -1) {
                let apparel = apparelDeck.splice(idx, 1)[0];
                apparelHand.push(apparel);
                page.toast("info", "Drew apparel: " + (apparel.name || "Unknown"));
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnItemTray(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'itemHand') {
            // Move from item hand to item tray
            let idx = itemHand.findIndex(i => i.objectId === draggedCard.objectId);
            if (idx > -1) {
                let item = itemHand.splice(idx, 1)[0];
                itemTray.push(item);
                page.toast("info", "Played item: " + (item.name || "Unknown"));
                // Clear detail view after playing
                selectedDetailCard = null;
                selectedDetailType = null;
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnApparelTray(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'apparelHand') {
            // Move from apparel hand to apparel tray
            let idx = apparelHand.findIndex(a => a.objectId === draggedCard.objectId);
            if (idx > -1) {
                let apparel = apparelHand.splice(idx, 1)[0];
                apparelTray.push(apparel);
                page.toast("info", "Played apparel: " + (apparel.name || "Unknown"));
                // Clear detail view after playing
                selectedDetailCard = null;
                selectedDetailType = null;
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    // System hand drop handlers
    function handleDropOnSystemActionHand(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'actionDeck') {
            let idx = actionDeck.findIndex(a => a.id === draggedCard.id);
            if (idx > -1) {
                let action = actionDeck.splice(idx, 1)[0];
                systemActionHand.push(action);
                page.toast("info", "System drew: " + action.label);
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnSystemItemHand(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'itemDeck') {
            let idx = itemDeck.findIndex(i => i.objectId === draggedCard.objectId);
            if (idx > -1) {
                let item = itemDeck.splice(idx, 1)[0];
                systemItems.push(item);
                page.toast("info", "System drew item: " + (item.name || "Unknown"));
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    function handleDropOnSystemApparelHand(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'apparelDeck') {
            let idx = apparelDeck.findIndex(a => a.objectId === draggedCard.objectId);
            if (idx > -1) {
                let apparel = apparelDeck.splice(idx, 1)[0];
                systemApparel.push(apparel);
                page.toast("info", "System drew apparel: " + (apparel.name || "Unknown"));
            }
        }
        draggedCard = null;
        dragSource = null;
        dropTarget = null;
        m.redraw();
    }

    // Universal discard handler - discards any card type and shuffles back into appropriate deck
    function handleUniversalDiscard() {
        if (!draggedCard || !dragSource) return;

        let cardName = "";

        // Determine card type from dragSource and handle accordingly
        if (dragSource === 'userHand' || dragSource === 'gameHand') {
            // Character card - find and remove from hand, shuffle into deck
            let sourceHand = dragSource === 'userHand' ? userHand : gameHand;
            let idx = sourceHand.findIndex(c => c.objectId === draggedCard.objectId);
            if (idx > -1) {
                let card = sourceHand.splice(idx, 1)[0];
                cardName = card.name || "Character";
                deck.push(card);
                deck = shuffleArray(deck);
                if (selectedCard && selectedCard.objectId === card.objectId) {
                    selectedCard = null;
                }
            }
        } else if (dragSource === 'actionHand' || dragSource === 'playedActions') {
            // Action card from user
            let sourceArray = dragSource === 'actionHand' ? actionHand : playedActions;
            let idx = sourceArray.findIndex(a => a.id === draggedCard.id);
            if (idx > -1) {
                let card = sourceArray.splice(idx, 1)[0];
                cardName = card.label || "Action";
                // Convert interaction back to action card if from playedActions
                let actionCard = dragSource === 'playedActions' ?
                    ACTION_CARD_TYPES.find(a => a.type === card.type) || card : card;
                actionDeck.push({...actionCard, id: Date.now() + Math.random()});
                actionDeck = shuffleArray(actionDeck);
            }
        } else if (dragSource === 'systemActionHand' || dragSource === 'systemPlayedActions') {
            // Action card from system
            let sourceArray = dragSource === 'systemActionHand' ? systemActionHand : systemPlayedActions;
            let idx = sourceArray.findIndex(a => a.id === draggedCard.id);
            if (idx > -1) {
                let card = sourceArray.splice(idx, 1)[0];
                cardName = card.label || "Action";
                let actionCard = dragSource === 'systemPlayedActions' ?
                    ACTION_CARD_TYPES.find(a => a.type === card.type) || card : card;
                actionDeck.push({...actionCard, id: Date.now() + Math.random()});
                actionDeck = shuffleArray(actionDeck);
            }
        } else if (dragSource === 'itemHand' || dragSource === 'itemTray') {
            // Item card from user
            let sourceArray = dragSource === 'itemHand' ? itemHand : itemTray;
            let idx = sourceArray.findIndex(i => i.objectId === draggedCard.objectId || i.id === draggedCard.id);
            if (idx > -1) {
                let card = sourceArray.splice(idx, 1)[0];
                cardName = card.name || "Item";
                itemDeck.push(card);
                itemDeck = shuffleArray(itemDeck);
            }
        } else if (dragSource === 'systemItems') {
            // Item card from system
            let idx = systemItems.findIndex(i => i.objectId === draggedCard.objectId || i.id === draggedCard.id);
            if (idx > -1) {
                let card = systemItems.splice(idx, 1)[0];
                cardName = card.name || "Item";
                itemDeck.push(card);
                itemDeck = shuffleArray(itemDeck);
            }
        } else if (dragSource === 'apparelHand' || dragSource === 'apparelTray') {
            // Apparel card from user
            let sourceArray = dragSource === 'apparelHand' ? apparelHand : apparelTray;
            let idx = sourceArray.findIndex(a => a.objectId === draggedCard.objectId || a.id === draggedCard.id);
            if (idx > -1) {
                let card = sourceArray.splice(idx, 1)[0];
                cardName = getApparelDisplayName(card);
                apparelDeck.push(card);
                apparelDeck = shuffleArray(apparelDeck);
            }
        } else if (dragSource === 'systemApparel') {
            // Apparel card from system
            let idx = systemApparel.findIndex(a => a.objectId === draggedCard.objectId || a.id === draggedCard.id);
            if (idx > -1) {
                let card = systemApparel.splice(idx, 1)[0];
                cardName = getApparelDisplayName(card);
                apparelDeck.push(card);
                apparelDeck = shuffleArray(apparelDeck);
            }
        }

        // Clear detail view if discarded card was selected
        if (selectedDetailCard && (selectedDetailCard.objectId === draggedCard.objectId || selectedDetailCard.id === draggedCard.id)) {
            selectedDetailCard = null;
            selectedDetailType = null;
        }

        if (cardName) {
            page.toast("info", "Discarded " + cardName + " and shuffled into deck");
        }

        draggedCard = null;
        dragSource = null;
    }

    // Card selection functions - opens detail view for action/item/apparel cards
    function selectActionCard(card) {
        selectedDetailCard = card;
        selectedDetailType = 'action';
        // Clear character selection when selecting action/item/apparel
        selectedCard = null;
        m.redraw();
    }

    async function selectItemCard(card) {
        selectedDetailType = 'item';
        selectedCard = null;
        // Load full item data including statistics
        if (card && (card.objectId || card.id)) {
            try {
                let q = am7view.viewQuery("olio.item");
                // Query by objectId if available, otherwise by id
                if (card.objectId) {
                    q.field("objectId", card.objectId);
                } else {
                    q.field("id", card.id);
                }
                // Explicitly request display fields including objectId
                q.entity.request.push("objectId", "name", "description", "category", "statistics", "qualities");
                let qr = await page.search(q);
                if (qr && qr.results && qr.results.length > 0) {
                    let full = qr.results[0];
                    am7model.applyModelNames(full);

                    // Load nested itemStatistics model reference if it exists but isn't fully loaded
                    if (full.statistics && full.statistics.objectId && full.statistics.damage === undefined) {
                        let sq = am7view.viewQuery("olio.itemStatistics");
                        sq.field("objectId", full.statistics.objectId);
                        let sqr = await page.search(sq);
                        if (sqr && sqr.results && sqr.results.length > 0) {
                            full.statistics = sqr.results[0];
                        }
                    }

                    selectedDetailCard = full;
                } else {
                    selectedDetailCard = card;
                }
            } catch (e) {
                console.error("Failed to load full item:", e);
                selectedDetailCard = card;
            }
        } else {
            selectedDetailCard = card;
        }
        m.redraw();
    }

    async function selectApparelCard(card) {
        selectedDetailType = 'apparel';
        selectedCard = null;
        // Load full apparel data
        if (card && card.id) {
            try {
                let q = am7view.viewQuery("olio.apparel");
                q.field("id", card.id);
                // Explicitly request display fields including objectId
                q.entity.request.push("objectId", "name", "type", "category", "description", "wearables");
                let qr = await page.search(q);
                if (qr && qr.results && qr.results.length > 0) {
                    let full = qr.results[0];
                    am7model.applyModelNames(full);
                    selectedDetailCard = full;
                } else {
                    selectedDetailCard = card;
                }
            } catch (e) {
                console.error("Failed to load full apparel:", e);
                selectedDetailCard = card;
            }
        } else {
            selectedDetailCard = card;
        }
        m.redraw();
    }

    // Mini card for stack display - responsive scaling
    // cardType: 'character', 'item', 'location', 'event' (for future use)
    // useResponsive: true for cards in hand areas that should scale with container
    function renderMiniCard(card, onClick, isHighlight, source, cardType, useResponsive) {
        let portraitUrl = card ? getPortraitUrl(card, "128x128") : null;
        let borderClass = isHighlight ? "cg-card-selected" : "border-gray-400 dark:border-gray-500";
        let isDragging = draggedCard && draggedCard.objectId === card?.objectId;
        let typeIcon = cardType === 'character' ? 'person' : cardType === 'item' ? 'inventory_2' : cardType === 'location' ? 'location_on' : 'event';
        let cardClass = useResponsive ? "cg-card-mini-responsive" : "cg-card-mini";

        return m("div", {
            class: cardClass + " " + borderClass + (isDragging ? " opacity-50" : ""),
            style: useResponsive ? "" : "width: 60px; height: 85px;",
            onclick: onClick,
            draggable: source ? true : false,
            ondragstart: source ? function(e) { handleDragStart(e, card, source); } : null,
            ondragend: source ? handleDragEnd : null
        }, [
            m("div", {class: "cg-mini-portrait flex-1"}, [
                portraitUrl ?
                    m("img", {src: portraitUrl, class: "w-full h-full object-cover", draggable: false}) :
                    m("span", {class: "material-symbols-outlined cg-mini-portrait-icon"}, "person"),
                // Card type icon overlay
                m("div", {class: "cg-mini-badge"}, [
                    m("span", {class: "material-symbols-outlined", style: "font-size: 10px"}, typeIcon)
                ])
            ]),
            m("div", {class: "cg-mini-name"}, [
                m("div", {class: "cg-mini-name-text text-xs md:text-sm"},
                    card ? card.name.split(" ")[0] : "?")
            ])
        ]);
    }

    // Card back for deck stacks (no portrait, just type icon)
    // Responsive: smaller on mobile (60x85), larger on desktop (80x110)
    function renderCardBack(cardType) {
        let typeIcon = cardType === 'character' ? 'person' : cardType === 'item' ? 'inventory_2' : cardType === 'location' ? 'location_on' : 'event';
        let typeName = cardType.charAt(0).toUpperCase() + cardType.slice(1);

        return m("div", {
            class: "cg-card-back",
            style: "width: 60px; height: 85px;"
        }, [
            m("div", {class: "h-full flex flex-col items-center justify-center"}, [
                m("span", {class: "material-symbols-outlined text-3xl text-blue-300"}, typeIcon),
                m("div", {class: "font-bold text-blue-300 mt-0.5", style: "font-size: 9px"}, typeName)
            ])
        ]);
    }

    // Draw Pile (Deck)
    function DrawPile() {
        return {
            view: function() {
                let topCard = previewCard || (deck.length > 0 ? deck[previewIndex] : null);

                return m("div", {class: "cg-panel"}, [
                    // Header
                    m("div", {class: "cg-header-blue"}, [
                        m("div", {class: "flex items-center space-x-1"}, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "person"),
                            m("span", {class: "text-sm font-medium"}, "Characters"),
                            m("span", {class: "text-xs opacity-75"}, "(" + deck.length + ")")
                        ]),
                        m("button", {
                            class: "p-1 rounded hover:bg-blue-500",
                            onclick: shuffleDeck,
                            title: "Shuffle"
                        }, m("span", {class: "material-symbols-outlined text-sm"}, "shuffle"))
                    ]),

                    // Stack display
                    m("div", {class: "flex-1 flex items-center justify-center p-2"}, [
                        deck.length === 0 ?
                            m("div", {class: "text-center text-gray-400 text-xs"}, [
                                m("span", {class: "material-symbols-outlined text-3xl block mb-1"}, "person_off"),
                                "Empty"
                            ]) :
                            m("div", {class: "relative"}, [
                                // Stack shadows with card backs
                                deck.length > 2 ? m("div", {
                                    class: "cg-stack-shadow-blue",
                                    style: "width: 60px; height: 85px; top: 3px; left: 3px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-xl text-blue-400/50"}, "person")
                                    ])
                                ]) : "",
                                deck.length > 1 ? m("div", {
                                    class: "cg-stack-shadow-blue",
                                    style: "width: 60px; height: 85px; top: 1.5px; left: 1.5px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-xl text-blue-400/50"}, "person")
                                    ])
                                ]) : "",
                                // Top card (preview card) - draggable
                                renderMiniCard(topCard, drawToUserHand, false, 'deck', 'character')
                            ])
                    ]),

                    // Navigation and actions
                    m("div", {class: "cg-footer"}, [
                        // Flip through controls
                        m("div", {class: "flex items-center justify-center space-x-1 mb-1"}, [
                            m("button", {
                                class: "cg-nav-btn cg-nav-btn-text",
                                onclick: previewPrevCard,
                                disabled: deck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-xs"}, "chevron_left")),
                            m("span", {class: "cg-nav-label min-w-12 text-center"},
                                deck.length > 0 ? (previewIndex + 1) + "/" + deck.length : "-"),
                            m("button", {
                                class: "cg-nav-btn cg-nav-btn-text",
                                onclick: previewNextCard,
                                disabled: deck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-xs"}, "chevron_right"))
                        ]),
                        // Draw buttons
                        m("div", {class: "flex space-x-1"}, [
                            m("button", {
                                class: "cg-btn-action-green",
                                onclick: drawToUserHand,
                                disabled: deck.length === 0 || userHand.length >= USER_HAND_LIMIT,
                                title: "Draw to your hand"
                            }, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "person"),
                                m("span", {}, "You")
                            ]),
                            m("button", {
                                class: "cg-btn-action-red",
                                onclick: drawToGameHand,
                                disabled: deck.length === 0 || gameHand.length >= GAME_HAND_LIMIT,
                                title: "Draw to game hand"
                            }, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "smart_toy"),
                                m("span", {}, "Game")
                            ])
                        ])
                    ])
                ]);
            }
        };
    }

    // User's Hand (limited to 1 character)
    function UserHandPile() {
        return {
            view: function() {
                let isCharDropTarget = dropTarget === 'userHand' && dragSource === 'deck';
                let isActionDropTarget = dropTarget === 'userActionHand' && dragSource === 'actionDeck';
                let isItemDropTarget = dropTarget === 'userItemHand' && dragSource === 'itemDeck';
                let isApparelDropTarget = dropTarget === 'userApparelHand' && dragSource === 'apparelDeck';
                let isPlayedDrop = (dropTarget === 'playedActions' && dragSource === 'actionHand') || (dragSource === 'actionHand' && dropTarget === 'playedActions');
                let isDraggingAction = dragSource === 'actionHand';

                return m("div", {class: "cg-panel-overflow"}, [
                    // Main content - character card with surrounding card stacks
                    // Responsive: smaller cards and tighter gaps on mobile
                    m("div", {class: "cg-hand-content"}, [
                        // Left side - Action cards stack
                        m("div", {
                            class: "cg-card-section " + (isActionDropTarget ? "bg-purple-200 dark:bg-purple-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'userActionHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnActionHand
                        }, [
                            m("div", {class: "cg-label-action"}, [
                                m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "bolt"),
                                m("span", {class: "cg-label-count"}, actionHand.length)
                            ]),
                            actionHand.length === 0 ?
                                m("div", {
                                    class: "cg-dropzone " + (isActionDropTarget ? "cg-dropzone-action-active" : "cg-dropzone-action-inactive"),
                                    style: "min-width: 80px; min-height: 112px;"
                                }, m("span", {class: "material-symbols-outlined text-purple-400 text-2xl md:text-3xl"}, "add")) :
                                m("div", {class: "relative cursor-pointer flex-1 flex items-center justify-center h-full", onclick: function() { selectActionCard(actionHand[actionHandIndex]); }}, [
                                    // Top card - responsive
                                    m("div", {
                                        class: "cg-mini-action",
                                        draggable: true,
                                        ondragstart: function(e) { handleDragStart(e, actionHand[actionHandIndex], 'actionHand'); },
                                        ondragend: handleDragEnd
                                    }, [
                                        m("span", {class: "material-symbols-outlined cg-mini-icon-purple text-2xl md:text-3xl"}, actionHand[actionHandIndex].icon),
                                        m("span", {class: "cg-mini-text-purple text-xs md:text-sm"}, actionHand[actionHandIndex].label),
                                        actionHand.length > 1 ? m("div", {class: "absolute -top-1 -right-1 bg-purple-600 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"}, actionHand.length) : ""
                                    ])
                                ])
                        ]),

                        // Center - Character card (drop zone)
                        m("div", {
                            class: "cg-card-section " + (isCharDropTarget ? "bg-green-200 dark:bg-green-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'userHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnUserHand
                        }, [
                            userHand.length === 0 ?
                                m("div", {class: "text-center text-gray-400 flex-1 flex items-center justify-center h-full"}, [
                                    m("div", {
                                        class: "cg-dropzone-char w-24 h-32 md:w-32 md:h-44 lg:w-40 lg:h-56 " + (isCharDropTarget ? "cg-dropzone-green-active" : "border-gray-400")
                                    }, [
                                        m("span", {class: "material-symbols-outlined text-3xl md:text-4xl lg:text-5xl"}, "person_add"),
                                        m("span", {class: "text-xs md:text-sm"}, "Drop")
                                    ])
                                ]) :
                                m("div", {class: "flex-1 flex items-center justify-center h-full"},
                                    userHand.map(function(card) {
                                        let isSelected = selectedCard && selectedCard.objectId === card.objectId;
                                        return renderMiniCard(card, function() { selectCard(card); }, isSelected, 'userHand', 'character', true);
                                    })
                                )
                        ]),

                        // Right side - Item cards stack
                        m("div", {
                            class: "cg-card-section " + (isItemDropTarget ? "bg-emerald-200 dark:bg-emerald-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'userItemHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnItemHand
                        }, [
                            m("div", {class: "cg-label-item"}, [
                                m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "inventory_2"),
                                m("span", {class: "cg-label-count"}, itemHand.length)
                            ]),
                            itemHand.length === 0 ?
                                m("div", {
                                    class: "cg-dropzone " + (isItemDropTarget ? "cg-dropzone-item-active" : "cg-dropzone-item-inactive"),
                                    style: "min-width: 80px; min-height: 112px;"
                                }, m("span", {class: "material-symbols-outlined text-emerald-400 text-2xl md:text-3xl"}, "add")) :
                                m("div", {class: "relative cursor-pointer flex-1 flex items-center justify-center h-full", onclick: function() { selectItemCard(itemHand[itemHandIndex]); }}, [
                                    m("div", {
                                        class: "cg-mini-item",
                                        draggable: true,
                                        ondragstart: function(e) { handleDragStart(e, itemHand[itemHandIndex], 'itemHand'); },
                                        ondragend: handleDragEnd
                                    }, [
                                        m("span", {class: "material-symbols-outlined cg-mini-icon-emerald text-2xl md:text-3xl"}, "inventory_2"),
                                        m("span", {class: "cg-mini-text-emerald text-xs md:text-sm"}, itemHand[itemHandIndex].name ? itemHand[itemHandIndex].name.substring(0, 8) : "Item"),
                                        itemHand.length > 1 ? m("div", {class: "absolute -top-1 -right-1 bg-emerald-600 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"}, itemHand.length) : ""
                                    ])
                                ])
                        ]),

                        // Far right - Apparel cards stack
                        m("div", {
                            class: "cg-card-section " + (isApparelDropTarget ? "bg-pink-200 dark:bg-pink-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'userApparelHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnApparelHand
                        }, [
                            m("div", {class: "cg-label-apparel"}, [
                                m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "checkroom"),
                                m("span", {class: "cg-label-count"}, apparelHand.length)
                            ]),
                            apparelHand.length === 0 ?
                                m("div", {
                                    class: "cg-dropzone " + (isApparelDropTarget ? "cg-dropzone-apparel-active" : "cg-dropzone-apparel-inactive"),
                                    style: "min-width: 80px; min-height: 112px;"
                                }, m("span", {class: "material-symbols-outlined text-pink-400 text-2xl md:text-3xl"}, "add")) :
                                m("div", {class: "relative cursor-pointer flex-1 flex items-center justify-center h-full", onclick: function() { selectApparelCard(apparelHand[apparelHandIndex]); }}, [
                                    m("div", {
                                        class: "cg-mini-apparel",
                                        draggable: true,
                                        ondragstart: function(e) { handleDragStart(e, apparelHand[apparelHandIndex], 'apparelHand'); },
                                        ondragend: handleDragEnd
                                    }, [
                                        m("span", {class: "material-symbols-outlined cg-mini-icon-pink text-2xl md:text-3xl"}, "checkroom"),
                                        m("span", {class: "cg-mini-text-pink text-xs md:text-sm"}, getApparelDisplayName(apparelHand[apparelHandIndex]).substring(0, 8)),
                                        apparelHand.length > 1 ? m("div", {class: "absolute -top-1 -right-1 bg-pink-600 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"}, apparelHand.length) : ""
                                    ])
                                ])
                        ])
                    ]),

                    // Footer with action tray - green header with played actions
                    m("div", {class: "cg-header-green"}, [
                        // Pending interaction display (if active)
                        pendingInteraction ? m("div", {class: "cg-pending-bar"}, [
                            m("div", {class: "flex items-center space-x-2"}, [
                                m("span", {class: "font-medium"}, userHand.length > 0 ? userHand[0].name.split(" ")[0] : "?"),
                                m("span", {class: "material-symbols-outlined text-sm"}, pendingInteraction.icon),
                                m("span", {class: "font-medium"}, pendingInteraction.label),
                                m("span", {class: "material-symbols-outlined text-sm"}, "arrow_forward"),
                                m("span", {class: "text-purple-200"},
                                    selectedCard && gameHand.find(c => c.objectId === selectedCard.objectId)
                                        ? selectedCard.name.split(" ")[0]
                                        : "Select target...")
                            ]),
                            m("div", {class: "flex space-x-1"}, [
                                m("button", {
                                    class: "cg-btn-sm-green",
                                    onclick: executeAction,
                                    disabled: !selectedCard || gameHand.findIndex(c => c.objectId === selectedCard?.objectId) === -1
                                }, "Go"),
                                m("button", {
                                    class: "cg-btn-sm-red",
                                    onclick: cancelAction
                                }, "X")
                            ])
                        ]) : "",
                        // Header row with title and played actions - whole bar is drop target
                        m("div", {
                            class: "flex items-center px-2 py-1 text-white transition-all " +
                                (isPlayedDrop ? "bg-green-500 ring-2 ring-white ring-inset" :
                                 isDraggingAction ? "bg-green-500/80" : ""),
                            ondragover: function(e) { e.preventDefault(); e.stopPropagation(); handleDragOver(e, 'playedActions'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnPlayedActions
                        }, [
                            m("div", {class: "flex items-center space-x-1 flex-shrink-0"}, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "person"),
                                m("span", {class: "text-sm font-medium"}, "Your Hand")
                            ]),
                            // Played actions area
                            m("div", {class: "flex-1 flex items-center ml-3 space-x-1 overflow-x-auto"}, [
                                // Played action cards (compact)
                                playedActions.map(function(action) {
                                    let isSelected = selectedAction && selectedAction.id === action.id;
                                    return m("div", {
                                        class: "cg-played-action " + (isSelected ? "cg-played-action-selected" : "cg-played-action-unselected"),
                                        onclick: function() { selectPlayedAction(action); }
                                    }, [
                                        m("span", {class: "material-symbols-outlined text-sm"}, action.icon),
                                        m("span", {class: "text-xs ml-1"}, action.label),
                                        m("button", {
                                            class: "ml-1 hover:text-red-300",
                                            onclick: function(e) { e.stopPropagation(); discardPlayedAction(action); },
                                            title: "Discard"
                                        }, m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "close"))
                                    ]);
                                }),
                                // Drop target guide/placeholder at the end
                                isDraggingAction ? m("div", {
                                    class: "cg-played-drop-zone " +
                                        (isPlayedDrop ? "border-white bg-green-400" : "border-green-300 bg-green-500/50 animate-pulse"),
                                    style: "width: 40px; height: 28px;"
                                }, [
                                    m("span", {class: "material-symbols-outlined text-sm text-white"}, "add")
                                ]) : ""
                            ])
                        ])
                    ])
                ]);
            }
        };
    }

    // Game's Hand (can have multiple characters)
    function GameHandPile() {
        return {
            view: function() {
                let isCharDropTarget = dropTarget === 'gameHand' && dragSource === 'deck';
                let isActionDropTarget = dropTarget === 'systemActionHand' && dragSource === 'actionDeck';
                let isItemDropTarget = dropTarget === 'systemItemHand' && dragSource === 'itemDeck';
                let isApparelDropTarget = dropTarget === 'systemApparelHand' && dragSource === 'apparelDeck';

                // Index for system hands
                let sysActionIdx = Math.min(systemActionHandIndex, Math.max(0, systemActionHand.length - 1));
                let sysItemIdx = Math.min(systemItemHandIndex, Math.max(0, systemItems.length - 1));
                let sysApparelIdx = Math.min(systemApparelHandIndex, Math.max(0, systemApparel.length - 1));

                return m("div", {class: "cg-panel-overflow"}, [
                    // Header with action tray - red header with system played actions
                    m("div", {class: "cg-header-red"}, [
                        m("div", {class: "flex items-center px-2 py-1 text-white"}, [
                            m("div", {class: "flex items-center space-x-1 flex-shrink-0"}, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "smart_toy"),
                                m("span", {class: "text-sm font-medium"}, "System Hand")
                            ]),
                            // System played actions area
                            m("div", {class: "flex-1 flex items-center ml-3 space-x-1 overflow-x-auto"}, [
                                systemPlayedActions.length === 0 ?
                                    m("span", {class: "text-red-200 text-xs"}, "No actions") :
                                    systemPlayedActions.map(function(action) {
                                        return m("div", {class: "cg-played-action-system"}, [
                                            m("span", {class: "material-symbols-outlined text-sm"}, action.icon),
                                            m("span", {class: "text-xs ml-1"}, action.label)
                                        ]);
                                    })
                            ])
                        ])
                    ]),

                    // Main content - character card with surrounding card stacks
                    // Responsive: smaller gaps on mobile
                    m("div", {class: "cg-hand-content"}, [
                        // Left side - Action cards stack (system)
                        m("div", {
                            class: "cg-card-section " + (isActionDropTarget ? "bg-purple-200 dark:bg-purple-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'systemActionHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnSystemActionHand
                        }, [
                            m("div", {class: "cg-label-action"}, [
                                m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "bolt"),
                                m("span", {class: "cg-label-count"}, systemActionHand.length)
                            ]),
                            systemActionHand.length === 0 ?
                                m("div", {
                                    class: "cg-dropzone " + (isActionDropTarget ? "cg-dropzone-action-active" : "cg-dropzone-action-inactive"),
                                    style: "min-width: 80px; min-height: 112px;"
                                }, m("span", {class: "material-symbols-outlined text-purple-400 text-2xl md:text-3xl"}, "add")) :
                                m("div", {class: "relative cursor-pointer flex-1 flex items-center justify-center h-full", onclick: function() { selectActionCard(systemActionHand[sysActionIdx]); }}, [
                                    m("div", {class: "cg-mini-action"}, [
                                        m("span", {class: "material-symbols-outlined cg-mini-icon-purple text-2xl md:text-3xl"}, systemActionHand[sysActionIdx].icon),
                                        m("span", {class: "cg-mini-text-purple text-xs md:text-sm"}, systemActionHand[sysActionIdx].label),
                                        systemActionHand.length > 1 ? m("div", {class: "absolute -top-1 -right-1 bg-purple-600 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"}, systemActionHand.length) : ""
                                    ])
                                ])
                        ]),

                        // Center - Character cards (drop zone)
                        m("div", {
                            class: "cg-card-section " + (isCharDropTarget ? "bg-red-200 dark:bg-red-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'gameHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnGameHand
                        }, [
                            gameHand.length === 0 ?
                                m("div", {class: "text-center text-gray-400 flex-1 flex items-center justify-center h-full"}, [
                                    m("div", {
                                        class: "cg-dropzone-char w-24 h-32 md:w-32 md:h-44 lg:w-40 lg:h-56 " + (isCharDropTarget ? "cg-dropzone-red-active" : "border-gray-400")
                                    }, [
                                        m("span", {class: "material-symbols-outlined text-3xl md:text-4xl lg:text-5xl"}, "smart_toy"),
                                        m("span", {class: "text-xs md:text-sm"}, "Drop")
                                    ])
                                ]) :
                                m("div", {class: "flex space-x-1 flex-1 items-center justify-center h-full"},
                                    gameHand.map(function(card) {
                                        let isSelected = selectedCard && selectedCard.objectId === card.objectId;
                                        return renderMiniCard(card, function() { selectCard(card); }, isSelected, 'gameHand', 'character', true);
                                    })
                                )
                        ]),

                        // Right side - Item cards stack (system)
                        m("div", {
                            class: "cg-card-section " + (isItemDropTarget ? "bg-emerald-200 dark:bg-emerald-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'systemItemHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnSystemItemHand
                        }, [
                            m("div", {class: "cg-label-item"}, [
                                m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "inventory_2"),
                                m("span", {class: "cg-label-count"}, systemItems.length)
                            ]),
                            systemItems.length === 0 ?
                                m("div", {
                                    class: "cg-dropzone " + (isItemDropTarget ? "cg-dropzone-item-active" : "cg-dropzone-item-inactive"),
                                    style: "min-width: 80px; min-height: 112px;"
                                }, m("span", {class: "material-symbols-outlined text-emerald-400 text-2xl md:text-3xl"}, "add")) :
                                m("div", {class: "relative cursor-pointer flex-1 flex items-center justify-center h-full", onclick: function() { selectItemCard(systemItems[sysItemIdx]); }}, [
                                    m("div", {class: "cg-mini-item"}, [
                                        m("span", {class: "material-symbols-outlined cg-mini-icon-emerald text-2xl md:text-3xl"}, "inventory_2"),
                                        m("span", {class: "cg-mini-text-emerald text-xs md:text-sm"}, systemItems[sysItemIdx].name ? systemItems[sysItemIdx].name.substring(0, 8) : "Item"),
                                        systemItems.length > 1 ? m("div", {class: "absolute -top-1 -right-1 bg-emerald-600 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"}, systemItems.length) : ""
                                    ])
                                ])
                        ]),

                        // Far right - Apparel cards stack (system)
                        m("div", {
                            class: "cg-card-section " + (isApparelDropTarget ? "bg-pink-200 dark:bg-pink-800" : ""),
                            ondragover: function(e) { e.preventDefault(); handleDragOver(e, 'systemApparelHand'); },
                            ondragleave: handleDragLeave,
                            ondrop: handleDropOnSystemApparelHand
                        }, [
                            m("div", {class: "cg-label-apparel"}, [
                                m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "checkroom"),
                                m("span", {class: "cg-label-count"}, systemApparel.length)
                            ]),
                            systemApparel.length === 0 ?
                                m("div", {
                                    class: "cg-dropzone " + (isApparelDropTarget ? "cg-dropzone-apparel-active" : "cg-dropzone-apparel-inactive"),
                                    style: "min-width: 80px; min-height: 112px;"
                                }, m("span", {class: "material-symbols-outlined text-pink-400 text-2xl md:text-3xl"}, "add")) :
                                m("div", {class: "relative cursor-pointer flex-1 flex items-center justify-center h-full", onclick: function() { selectApparelCard(systemApparel[sysApparelIdx]); }}, [
                                    m("div", {class: "cg-mini-apparel"}, [
                                        m("span", {class: "material-symbols-outlined cg-mini-icon-pink text-2xl md:text-3xl"}, "checkroom"),
                                        m("span", {class: "cg-mini-text-pink text-xs md:text-sm"}, getApparelDisplayName(systemApparel[sysApparelIdx]).substring(0, 8)),
                                        systemApparel.length > 1 ? m("div", {class: "absolute -top-1 -right-1 bg-pink-600 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center"}, systemApparel.length) : ""
                                    ])
                                ])
                        ])
                    ])
                ]);
            }
        };
    }

    // Action Deck Pile - goes in left panel with character piles
    function ActionDeckPile() {
        return {
            view: function() {
                let topCard = actionPreview;

                return m("div", {class: "cg-panel"}, [
                    // Header
                    m("div", {class: "cg-header-purple"}, [
                        m("div", {class: "flex items-center space-x-1"}, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "bolt"),
                            m("span", {class: "text-sm font-medium"}, "Actions"),
                            m("span", {class: "text-xs opacity-75"}, "(" + actionDeck.length + ")")
                        ]),
                        m("div", {class: "flex items-center space-x-1"}, [
                            m("button", {
                                class: "p-0.5 rounded hover:bg-purple-500",
                                onclick: shuffleActionDeck,
                                title: "Shuffle"
                            }, m("span", {class: "material-symbols-outlined text-sm"}, "shuffle")),
                            m("button", {
                                class: "p-0.5 rounded hover:bg-purple-500",
                                onclick: reshuffleActionDeck,
                                disabled: actionDiscard.length === 0,
                                title: "Reshuffle discard (" + actionDiscard.length + ")"
                            }, m("span", {class: "material-symbols-outlined text-sm"}, "replay"))
                        ])
                    ]),

                    // Deck display
                    m("div", {class: "flex-1 flex flex-col items-center justify-center p-2"}, [
                        // Stacked deck visual
                        m("div", {class: "relative mb-2"}, [
                            // Shadow cards
                            actionDeck.length > 2 ? m("div", {
                                class: "cg-stack-shadow-purple",
                                style: "width: 70px; height: 90px; top: 4px; left: 4px;"
                            }) : "",
                            actionDeck.length > 1 ? m("div", {
                                class: "cg-stack-shadow-purple",
                                style: "width: 70px; height: 90px; top: 2px; left: 2px;"
                            }) : "",
                            // Top card (preview or back)
                            actionDeck.length > 0 ?
                                m("div", {
                                    class: "cg-action-deck-card",
                                    style: "width: 70px; height: 90px;"
                                }, [
                                    topCard ? [
                                        m("span", {class: "material-symbols-outlined text-xl text-purple-200"}, topCard.icon),
                                        m("span", {class: "text-xs mt-1 text-purple-200 text-center"}, topCard.label)
                                    ] : [
                                        m("span", {class: "material-symbols-outlined text-2xl text-purple-300"}, "bolt"),
                                        m("span", {class: "text-xs mt-1 text-purple-300"}, "Action")
                                    ]
                                ]) :
                                m("div", {
                                    class: "cg-action-deck-empty",
                                    style: "width: 70px; height: 90px;"
                                }, "Empty")
                        ]),

                        // Navigation buttons
                        m("div", {class: "flex items-center space-x-2 mb-2"}, [
                            m("button", {
                                class: "cg-nav-btn",
                                onclick: prevActionPreview,
                                disabled: actionDeck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-sm text-gray-700 dark:text-gray-300"}, "chevron_left")),
                            m("span", {class: "cg-nav-label"},
                                actionDeck.length > 0 ? (actionPreviewIndex + 1) + "/" + actionDeck.length : "-"),
                            m("button", {
                                class: "cg-nav-btn",
                                onclick: nextActionPreview,
                                disabled: actionDeck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-sm text-gray-700 dark:text-gray-300"}, "chevron_right"))
                        ]),

                        // Draw button
                        m("button", {
                            class: "cg-action-deck-btn",
                            onclick: drawActionToTray,
                            disabled: actionDeck.length === 0
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "play_arrow"),
                            m("span", {}, "Play to Tray")
                        ])
                    ]),

                    // Discard info
                    m("div", {class: "cg-footer text-center"}, [
                        m("span", {class: "text-xs text-orange-600 dark:text-orange-400"}, "Discard: " + actionDiscard.length)
                    ])
                ]);
            }
        };
    }

    // Render a mini item card
    function renderItemCard(item) {
        return m("div", {
            class: "cg-render-item-card",
            style: "width: 50px; height: 60px;",
            title: item.name || "Item"
        }, [
            m("span", {class: "material-symbols-outlined cg-item-icon"}, "inventory_2"),
            m("span", {class: "cg-item-text"}, item.name ? item.name.substring(0, 6) : "?")
        ]);
    }

    // Render a mini apparel card
    function renderApparelCard(apparel) {
        let displayName = getApparelDisplayName(apparel);
        return m("div", {
            class: "cg-render-apparel-card",
            style: "width: 50px; height: 60px;",
            title: displayName
        }, [
            m("span", {class: "material-symbols-outlined cg-apparel-icon"}, "checkroom"),
            m("span", {class: "cg-apparel-text"}, displayName.substring(0, 6))
        ]);
    }

    // Render item statistics (itemStatistics model - damage, protection, range, etc.)
    function renderItemStatistics(item) {
        // Get statistics - it's a single object, not an array
        let stats = item.statistics;

        if (!stats) {
            return m("div", {class: "mt-2 text-xs text-gray-500 text-center"}, "No statistics");
        }

        // Item statistics properties from olio.itemStatistics model
        let statProps = [
            {key: 'damage', label: 'Damage', icon: 'swords', max: 20},
            {key: 'protection', label: 'Protection', icon: 'shield', max: 20},
            {key: 'entrapment', label: 'Entrapment', icon: 'trap', max: 20},
            {key: 'range', label: 'Range', icon: 'radar', unit: 'm'},
            {key: 'consumption', label: 'Consumption', icon: 'local_fire_department'},
            {key: 'replenishment', label: 'Replenish', icon: 'add_circle'}
        ];

        // Filter to only show non-zero/non-null values
        let activeProps = statProps.filter(function(p) {
            return stats[p.key] !== undefined && stats[p.key] !== null && stats[p.key] !== 0;
        });

        // Also check for type fields
        let hasConsumptionType = stats.consumptionType && stats.consumptionType.length > 0;
        let hasReplenishmentType = stats.replenishmentType && stats.replenishmentType.length > 0;

        if (activeProps.length === 0 && !hasConsumptionType && !hasReplenishmentType) {
            return m("div", {class: "mt-2 text-xs text-gray-500 text-center"}, "No stat data");
        }

        return m("div", {class: "mt-2 text-xs text-emerald-700 dark:text-emerald-300 overflow-y-auto", style: "max-height: 80px;"}, [
            // Show stat bars for numeric values
            activeProps.map(function(p) {
                let val = stats[p.key];
                let displayVal = val;
                if (p.unit) {
                    displayVal = val + p.unit;
                }

                // For stats with max values, show a bar
                if (p.max) {
                    let pct = Math.min(100, (val / p.max) * 100);
                    return m("div", {class: "px-1 py-0.5"}, [
                        m("div", {class: "flex items-center justify-between mb-0.5"}, [
                            m("span", {class: "flex items-center"}, [
                                m("span", {class: "material-symbols-outlined mr-1", style: "font-size: 10px"}, p.icon),
                                m("span", {}, p.label)
                            ]),
                            m("span", {class: "font-medium"}, val + "/" + p.max)
                        ]),
                        m("div", {class: "w-full h-1.5 bg-gray-300 dark:bg-gray-600 rounded"}, [
                            m("div", {
                                class: "h-full rounded " + (p.key === 'damage' ? "bg-red-500" : p.key === 'protection' ? "bg-blue-500" : "bg-yellow-500"),
                                style: "width: " + pct + "%"
                            })
                        ])
                    ]);
                }

                return m("div", {class: "flex items-center justify-between px-1 py-0.5"}, [
                    m("span", {class: "flex items-center"}, [
                        m("span", {class: "material-symbols-outlined mr-1", style: "font-size: 10px"}, p.icon),
                        m("span", {}, p.label)
                    ]),
                    m("span", {class: "font-medium"}, displayVal)
                ]);
            }),
            // Show consumption/replenishment types if present
            hasConsumptionType ? m("div", {class: "flex items-center justify-between px-1 py-0.5 border-t border-emerald-300 dark:border-emerald-700 mt-1 pt-1"}, [
                m("span", {}, "Consumes"),
                m("span", {class: "font-medium"}, stats.consumptionType)
            ]) : "",
            hasReplenishmentType ? m("div", {class: "flex items-center justify-between px-1 py-0.5"}, [
                m("span", {}, "Replenishes"),
                m("span", {class: "font-medium"}, stats.replenishmentType)
            ]) : ""
        ]);
    }

    // Discard Pile
    function DiscardPile() {
        return {
            view: function() {
                let topCard = discard.length > 0 ? discard[discard.length - 1] : null;
                let isDropTarget = dropTarget === 'discard' && (dragSource === 'userHand' || dragSource === 'gameHand');

                return m("div", {
                    class: "cg-panel-transition " + (isDropTarget ? "bg-orange-100 dark:bg-orange-900/30" : ""),
                    ondragover: function(e) { handleDragOver(e, 'discard'); },
                    ondragleave: handleDragLeave,
                    ondrop: handleDropOnDiscard
                }, [
                    // Header
                    m("div", {class: "flex items-center justify-between px-2 py-1 bg-orange-600 text-white"}, [
                        m("div", {class: "flex items-center space-x-1"}, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "delete"),
                            m("span", {class: "text-sm font-medium"}, "Discard"),
                            m("span", {class: "text-xs opacity-75"}, "(" + discard.length + ")")
                        ]),
                        m("button", {
                            class: "p-1 rounded hover:bg-orange-500 disabled:opacity-50",
                            onclick: reshuffleDiscard,
                            disabled: discard.length === 0,
                            title: "Reshuffle into deck"
                        }, m("span", {class: "material-symbols-outlined text-sm"}, "replay"))
                    ]),

                    // Stack display
                    m("div", {class: "flex-1 flex items-center justify-center p-2"}, [
                        discard.length === 0 ?
                            m("div", {class: "text-center text-gray-400 text-xs"}, [
                                m("span", {class: "material-symbols-outlined text-3xl block mb-1"}, "delete_outline"),
                                isDropTarget ? "Drop to discard" : "Empty"
                            ]) :
                            m("div", {class: "relative"}, [
                                // Stack shadows with card backs
                                discard.length > 2 ? m("div", {
                                    class: "cg-stack-shadow-orange",
                                    style: "width: 60px; height: 85px; top: 3px; left: 3px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-xl text-orange-400/50"}, "person")
                                    ])
                                ]) : "",
                                discard.length > 1 ? m("div", {
                                    class: "cg-stack-shadow-orange",
                                    style: "width: 60px; height: 85px; top: 1.5px; left: 1.5px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-xl text-orange-400/50"}, "person")
                                    ])
                                ]) : "",
                                // Top card (not draggable from discard)
                                renderMiniCard(topCard, null, false, null, 'character')
                            ])
                    ]),

                    // Reshuffle button
                    m("div", {class: "cg-footer"}, [
                        m("button", {
                            class: "w-full cg-btn-action-orange",
                            onclick: reshuffleDiscard,
                            disabled: discard.length === 0
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "replay"),
                            m("span", {}, "Reshuffle")
                        ])
                    ])
                ]);
            }
        };
    }

    // Consolidated Draw Piles - Simple grid of all card type stacks
    function ConsolidatedDecks() {
        return {
            view: function() {
                // Horizontal on mobile, vertical on desktop
                return m("div", {class: "cg-decks-panel"}, [
                    // Horizontal row on mobile / Vertical stack on desktop of draw piles
                    m("div", {class: "cg-decks-row"}, [
                        // Character pile
                        renderDrawPile("Characters", "person", "blue", deck, discard, "characterDeck",
                            function() { drawToUserHand(); }, shuffleDeck, reshuffleDiscard),

                        // Action pile
                        renderDrawPile("Actions", "bolt", "purple", actionDeck, actionDiscard, "actionDeck",
                            drawActionToHand, shuffleActionDeck, reshuffleActionDeck),

                        // Item pile
                        renderDrawPile("Items", "inventory_2", "emerald", itemDeck, itemDiscard, "itemDeck",
                            drawItemToHand, shuffleItemDeck, reshuffleItemDeck),

                        // Apparel pile
                        renderDrawPile("Apparel", "checkroom", "pink", apparelDeck, apparelDiscard, "apparelDeck",
                            drawApparelToHand, shuffleApparelDeck, reshuffleApparelDeck)
                    ]),

                    // Universal discard drop zone
                    m("div", {
                        class: "cg-discard-zone " + (dropTarget === 'universalDiscard' ? "cg-discard-active" : "cg-discard-inactive"),
                        style: "min-width: 50px; min-height: 40px;",
                        ondragover: function(e) {
                            e.preventDefault();
                            dropTarget = 'universalDiscard';
                            m.redraw();
                        },
                        ondragleave: function() {
                            if (dropTarget === 'universalDiscard') dropTarget = null;
                            m.redraw();
                        },
                        ondrop: function(e) {
                            e.preventDefault();
                            handleUniversalDiscard();
                            dropTarget = null;
                            m.redraw();
                        }
                    }, [
                        m("span", {class: "material-symbols-outlined text-lg " + (dropTarget === 'universalDiscard' ? "text-red-500" : "text-gray-500 dark:text-gray-400")}, "delete")
                    ])
                ]);
            }
        };
    }

    function renderDrawPile(label, icon, color, deckArray, discardArray, dragSourceType, onDraw, onShuffle, onReshuffle) {
        const colorMap = {
            blue: {bg: 'bg-blue-600', hover: 'hover:bg-blue-500', card: 'from-blue-700 to-blue-900', border: 'border-blue-500'},
            purple: {bg: 'bg-purple-600', hover: 'hover:bg-purple-500', card: 'from-purple-700 to-purple-900', border: 'border-purple-500'},
            emerald: {bg: 'bg-emerald-600', hover: 'hover:bg-emerald-500', card: 'from-emerald-700 to-emerald-900', border: 'border-emerald-500'},
            pink: {bg: 'bg-pink-600', hover: 'hover:bg-pink-500', card: 'from-pink-700 to-pink-900', border: 'border-pink-500'}
        };
        let c = colorMap[color] || colorMap.blue;
        let deckCount = deckArray.length;
        let discardCount = discardArray.length;
        let topCard = deckCount > 0 ? deckArray[0] : null;

        return m("div", {class: "cg-draw-pile"}, [
            // Label
            m("div", {class: "cg-draw-pile-label"}, label),

            // Card stack visual - draggable
            m("div", {class: "relative mb-1", style: "width: 50px; height: 65px;"}, [
                deckCount > 2 ? m("div", {
                    class: "absolute rounded bg-gradient-to-b " + c.card + " border " + c.border,
                    style: "width: 50px; height: 65px; top: 3px; left: 3px;"
                }) : "",
                deckCount > 1 ? m("div", {
                    class: "absolute rounded bg-gradient-to-b " + c.card + " border " + c.border,
                    style: "width: 50px; height: 65px; top: 1.5px; left: 1.5px;"
                }) : "",
                m("div", {
                    class: "relative flex flex-col items-center justify-center rounded bg-gradient-to-b " + c.card + " border " + c.border + " cursor-grab " + c.hover,
                    style: "width: 50px; height: 65px;",
                    onclick: deckCount > 0 ? onDraw : null,
                    draggable: deckCount > 0,
                    ondragstart: deckCount > 0 ? function(e) {
                        handleDragStart(e, topCard, dragSourceType);
                    } : null,
                    ondragend: handleDragEnd
                }, [
                    m("span", {class: "material-symbols-outlined text-xl text-white/80"}, icon),
                    m("span", {class: "text-xs text-white/80 font-bold"}, deckCount)
                ])
            ]),

            // Buttons row
            m("div", {class: "flex space-x-1"}, [
                m("button", {
                    class: "cg-btn-icon",
                    onclick: onShuffle,
                    disabled: deckCount === 0,
                    title: "Shuffle"
                }, m("span", {class: "material-symbols-outlined text-xs text-gray-700 dark:text-gray-300"}, "shuffle")),
                m("button", {
                    class: "cg-btn-icon-orange",
                    onclick: onReshuffle,
                    disabled: discardCount === 0,
                    title: "Reshuffle discard (" + discardCount + ")"
                }, m("span", {class: "material-symbols-outlined text-xs"}, "replay"))
            ]),

            // Discard count
            discardCount > 0 ? m("div", {class: "text-xs text-orange-600 dark:text-orange-400 mt-0.5"}, "(" + discardCount + ")") : ""
        ]);
    }

    // Item deck functions
    function shuffleItemDeck() {
        itemDeck = shuffleArray(itemDeck);
        itemPreviewIndex = 0;
        m.redraw();
    }

    function nextItemPreview() {
        if (itemDeck.length === 0) return;
        itemPreviewIndex = (itemPreviewIndex + 1) % itemDeck.length;
        m.redraw();
    }

    function prevItemPreview() {
        if (itemDeck.length === 0) return;
        itemPreviewIndex = (itemPreviewIndex - 1 + itemDeck.length) % itemDeck.length;
        m.redraw();
    }

    function drawItemToHand() {
        if (itemDeck.length === 0) return;
        let item = itemDeck.splice(itemPreviewIndex, 1)[0];
        itemHand.push(item);
        if (itemPreviewIndex >= itemDeck.length) itemPreviewIndex = 0;
        page.toast("info", "Drew item: " + (item.name || "Unknown"));
        m.redraw();
    }

    function reshuffleItemDeck() {
        itemDeck.push(...itemDiscard);
        itemDiscard = [];
        shuffleItemDeck();
        page.toast("info", "Reshuffled item deck");
    }

    // Apparel deck functions
    function shuffleApparelDeck() {
        apparelDeck = shuffleArray(apparelDeck);
        apparelPreviewIndex = 0;
        m.redraw();
    }

    function nextApparelPreview() {
        if (apparelDeck.length === 0) return;
        apparelPreviewIndex = (apparelPreviewIndex + 1) % apparelDeck.length;
        m.redraw();
    }

    function prevApparelPreview() {
        if (apparelDeck.length === 0) return;
        apparelPreviewIndex = (apparelPreviewIndex - 1 + apparelDeck.length) % apparelDeck.length;
        m.redraw();
    }

    function drawApparelToHand() {
        if (apparelDeck.length === 0) return;
        let apparel = apparelDeck.splice(apparelPreviewIndex, 1)[0];
        apparelHand.push(apparel);
        if (apparelPreviewIndex >= apparelDeck.length) apparelPreviewIndex = 0;
        page.toast("info", "Drew apparel: " + (apparel.name || "Unknown"));
        m.redraw();
    }

    function reshuffleApparelDeck() {
        apparelDeck.push(...apparelDiscard);
        apparelDiscard = [];
        shuffleApparelDeck();
        page.toast("info", "Reshuffled apparel deck");
    }

    // ==========================================
    // Card View Tabs
    // ==========================================

    function CardViewTabs() {
        return {
            view: function() {
                let tabs = [
                    {id: 'profile', label: 'Profile', icon: 'person'},
                    {id: 'stats', label: 'Stats', icon: 'bar_chart'},
                    {id: 'traits', label: 'Traits', icon: 'psychology'},
                    {id: 'narrative', label: 'Story', icon: 'auto_stories'},
                    {id: 'instincts', label: 'Instincts', icon: 'pets'},
                    {id: 'apparel', label: 'Apparel', icon: 'checkroom'}
                ];

                return m("div", {class: "cg-tabs-container"}, [
                    tabs.map(function(tab) {
                        let isActive = cardView === tab.id;
                        return m("button", {
                            class: isActive ? "cg-tab-active" : "cg-tab-inactive",
                            onclick: function() { cardView = tab.id; m.redraw(); },
                            title: tab.label
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, tab.icon),
                            m("span", {class: "text-xs"}, tab.label)
                        ]);
                    }),
                    // Open Object Page link at bottom of tabs
                    selectedCard ? m("a", {
                        class: "cg-tab-link",
                        href: "/#!/view/olio.charPerson/" + (selectedCard.objectId || selectedCard.id || ""),
                        target: "_blank",
                        title: "Open full object page in new tab"
                    }, [
                        m("span", {class: "material-symbols-outlined text-sm"}, "open_in_new"),
                        m("span", {class: "text-xs"}, "Object")
                    ]) : ""
                ]);
            }
        };
    }

    // ==========================================
    // Selected Card Detail View
    // ==========================================

    function SelectedCardView() {
        return {
            view: function() {
                let isDropTarget = dropTarget === 'selected' && dragSource === 'hand';

                // Check if we have a detail card (action/item/apparel) selected
                if (selectedDetailCard && selectedDetailType) {
                    return renderDetailCardView();
                }

                if (!selectedCard) {
                    return m("div", {
                        class: "cg-empty-state " + (isDropTarget ? "bg-purple-100 dark:bg-purple-900/30" : ""),
                        ondragover: function(e) { handleDragOver(e, 'selected'); },
                        ondragleave: handleDragLeave,
                        ondrop: handleDropOnSelected
                    }, [
                        m("span", {class: "material-symbols-outlined text-6xl mb-2"}, "touch_app"),
                        m("div", {}, isDropTarget ? "Drop to view card" : "Click a card to view")
                    ]);
                }
                return m("div", {
                    class: "cg-panel-transition " + (isDropTarget ? "bg-purple-100 dark:bg-purple-900/30" : ""),
                    ondragover: function(e) { handleDragOver(e, 'selected'); },
                    ondragleave: handleDragLeave,
                    ondrop: handleDropOnSelected
                }, [
                    // Main content area - tabs on left, card on right
                    m("div", {class: "flex-1 flex overflow-hidden"}, [
                        // Vertical tabs on left
                        m("div", {class: "cg-tabs-panel"}, [
                            m(CardViewTabs)
                        ]),

                        // Card display
                        m("div", {class: "cg-card-display"}, [
                            m(CharacterCard, {
                                character: selectedCard,
                                selected: true
                            })
                        ])
                    ]),

                    // Action buttons - flex-shrink-0 prevents clipping
                    m("div", {class: "cg-footer-actions"}, [
                        m("button", {
                            class: "cg-btn-primary",
                            onclick: function() { openCharacterView(selectedCard); }
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "open_in_full"),
                            m("span", {}, "Full View")
                        ]),
                        m("button", {
                            class: "cg-btn-secondary",
                            onclick: function() { refreshSelectedCard(); }
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "refresh")
                        ])
                    ])
                ]);
            }
        };
    }

    // Render detail view for action/item/apparel cards
    // Uses the same layout as character card view (tabs on left, card on right)
    function renderDetailCardView() {
        let card = selectedDetailCard;
        let type = selectedDetailType;

        // Determine colors based on type
        let colors = {
            action: { bg: "purple", icon: "bolt" },
            item: { bg: "emerald", icon: "inventory_2" },
            apparel: { bg: "pink", icon: "checkroom" }
        }[type] || { bg: "gray", icon: "help" };

        // Check if this card is in the user's hand (draggable to tray)
        let isInUserHand = false;
        let handArray = null;
        let handIndex = null;
        if (type === 'action') {
            isInUserHand = actionHand.some(a => a.id === card.id);
            handArray = actionHand;
            handIndex = actionHand.findIndex(a => a.id === card.id);
        } else if (type === 'item') {
            isInUserHand = itemHand.some(i => i.objectId === card.objectId);
            handArray = itemHand;
            handIndex = itemHand.findIndex(i => i.objectId === card.objectId);
        } else if (type === 'apparel') {
            isInUserHand = apparelHand.some(a => a.objectId === card.objectId);
            handArray = apparelHand;
            handIndex = apparelHand.findIndex(a => a.objectId === card.objectId);
        }

        // Build tabs for this card type - similar to CardViewTabs for character
        let detailTabs = [
            {id: 'info', label: 'Info', icon: 'info'}
        ];
        // Add type-specific tabs
        if (type === 'item') {
            // Items use itemStatistics model (damage, protection, range, etc.)
            detailTabs.push({id: 'itemStats', label: 'Stats', icon: 'bar_chart'});
        } else {
            // Actions and apparel use generic stats tab
            detailTabs.push({id: 'stats', label: 'Stats', icon: 'bar_chart'});
        }

        // State for detail card view tab (default to 'info')
        if (!window._detailCardView) window._detailCardView = 'info';

        return m("div", {class: "cg-panel-transition"}, [
            // Main content area - tabs on left, card on right (same as character view)
            m("div", {class: "flex-1 flex overflow-hidden"}, [
                // Vertical tabs on left (matching character view layout)
                m("div", {class: "cg-tabs-panel"}, [
                    m("div", {class: "cg-tabs-container"}, [
                        // Type header
                        m("div", {class: "flex items-center space-x-1 px-2 py-1 text-" + colors.bg + "-600 dark:text-" + colors.bg + "-400 border-b border-gray-300 dark:border-gray-600 mb-1"}, [
                            m("span", {class: "material-symbols-outlined text-sm"}, colors.icon),
                            m("span", {class: "text-xs font-medium"}, type.charAt(0).toUpperCase() + type.slice(1))
                        ]),
                        // Tab buttons
                        detailTabs.map(function(tab) {
                            let isActive = window._detailCardView === tab.id;
                            return m("button", {
                                class: isActive ? "cg-tab-active" : "cg-tab-inactive",
                                onclick: function() { window._detailCardView = tab.id; m.redraw(); },
                                title: tab.label
                            }, [
                                m("span", {class: "material-symbols-outlined text-sm"}, tab.icon),
                                m("span", {class: "text-xs"}, tab.label)
                            ]);
                        }),
                        // Navigation if in hand with multiple cards
                        isInUserHand && handArray && handArray.length > 1 ? [
                            m("div", {class: "border-t border-gray-300 dark:border-gray-600 mt-2 pt-2"}, [
                                m("div", {class: "cg-nav-label text-center mb-1"}, (handIndex + 1) + "/" + handArray.length),
                                m("div", {class: "flex justify-center space-x-1"}, [
                                    m("button", {
                                        class: "cg-nav-btn-alt",
                                        onclick: function() {
                                            if (handIndex > 0) {
                                                if (type === 'action') { actionHandIndex = handIndex - 1; selectActionCard(actionHand[actionHandIndex]); }
                                                else if (type === 'item') { itemHandIndex = handIndex - 1; selectItemCard(itemHand[itemHandIndex]); }
                                                else if (type === 'apparel') { apparelHandIndex = handIndex - 1; selectApparelCard(apparelHand[apparelHandIndex]); }
                                            }
                                        },
                                        disabled: handIndex <= 0
                                    }, m("span", {class: "material-symbols-outlined text-sm"}, "chevron_left")),
                                    m("button", {
                                        class: "cg-nav-btn-alt",
                                        onclick: function() {
                                            if (handIndex < handArray.length - 1) {
                                                if (type === 'action') { actionHandIndex = handIndex + 1; selectActionCard(actionHand[actionHandIndex]); }
                                                else if (type === 'item') { itemHandIndex = handIndex + 1; selectItemCard(itemHand[itemHandIndex]); }
                                                else if (type === 'apparel') { apparelHandIndex = handIndex + 1; selectApparelCard(apparelHand[apparelHandIndex]); }
                                            }
                                        },
                                        disabled: handIndex >= handArray.length - 1
                                    }, m("span", {class: "material-symbols-outlined text-sm"}, "chevron_right"))
                                ])
                            ])
                        ] : "",
                        // Open Object Page link at bottom of tabs (for items/apparel with objectId)
                        (card.objectId || card.id) ? m("a", {
                            class: "cg-tab-link",
                            href: "/#!/view/olio." + type + "/" + (card.objectId || card.id),
                            target: "_blank",
                            title: "Open full object page in new tab"
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "open_in_new"),
                            m("span", {class: "text-xs"}, "Object")
                        ]) : ""
                    ])
                ]),

                // Card display area (matching character view layout)
                m("div", {class: "cg-card-display"}, [
                    // Large draggable card
                    m("div", {
                        class: "flex flex-col items-center justify-center p-4 rounded-xl border-4 bg-gradient-to-b from-" + colors.bg + "-100 to-" + colors.bg + "-200 dark:from-" + colors.bg + "-800 dark:to-" + colors.bg + "-900 border-" + colors.bg + "-400 dark:border-" + colors.bg + "-600 shadow-lg " + (isInUserHand ? "cursor-grab" : ""),
                        style: "width: 180px; height: 240px;",
                        draggable: isInUserHand,
                        ondragstart: isInUserHand ? function(e) { handleDragStart(e, card, type + 'Hand'); } : null,
                        ondragend: isInUserHand ? handleDragEnd : null
                    }, [
                        m("span", {class: "material-symbols-outlined text-5xl text-" + colors.bg + "-600 dark:text-" + colors.bg + "-400 mb-2"}, card.icon || colors.icon),
                        // For apparel use type, for items use name, for actions use label
                        m("span", {class: "text-lg font-bold text-" + colors.bg + "-800 dark:text-" + colors.bg + "-200 text-center"},
                            type === 'action' ? (card.label || "Unknown") :
                            type === 'apparel' ? getApparelDisplayName(card) :
                            (card.name || "Unknown")),
                        card.description ? m("p", {class: "text-xs text-" + colors.bg + "-700 dark:text-" + colors.bg + "-300 text-center mt-2 px-2 overflow-y-auto", style: "max-height: 100px;"}, card.description) : "",
                        // Show additional info based on selected tab
                        window._detailCardView === 'stats' && type === 'item' && card.type ? m("div", {class: "mt-2 text-xs text-" + colors.bg + "-600 dark:text-" + colors.bg + "-400"}, [
                            m("span", {class: "font-medium"}, "Type: "),
                            m("span", {}, card.type)
                        ]) : "",
                        window._detailCardView === 'stats' && type === 'apparel' ? m("div", {class: "mt-2 text-xs text-" + colors.bg + "-600 dark:text-" + colors.bg + "-400"}, [
                            card.type ? m("div", {}, [m("span", {class: "font-medium"}, "Type: "), m("span", {}, card.type)]) : "",
                            card.category ? m("div", {}, [m("span", {class: "font-medium"}, "Category: "), m("span", {}, card.category)]) : ""
                        ]) : "",
                        // Item statistics tab (damage, protection, range, etc.)
                        window._detailCardView === 'itemStats' && type === 'item' ? renderItemStatistics(card) : ""
                    ]),
                    // Drag hint
                    isInUserHand ? m("div", {class: "mt-2 text-xs text-gray-500 flex items-center space-x-1"}, [
                        m("span", {class: "material-symbols-outlined", style: "font-size: 14px"}, "drag_indicator"),
                        m("span", {}, "Drag to tray to play")
                    ]) : ""
                ])
            ]),

            // Action buttons (same position as character view)
            m("div", {class: "cg-footer-actions"}, [
                isInUserHand ? m("button", {
                    class: "cg-btn-warning",
                    onclick: function() {
                        if (type === 'action') discardActionFromHand(card);
                        else if (type === 'item') discardItemFromHand(card);
                        else if (type === 'apparel') discardApparelFromHand(card);
                        selectedDetailCard = null;
                        selectedDetailType = null;
                    }
                }, [
                    m("span", {class: "material-symbols-outlined text-sm"}, "delete"),
                    m("span", {}, "Discard")
                ]) : "",
                m("button", {
                    class: "cg-btn-secondary",
                    onclick: function() {
                        selectedDetailCard = null;
                        selectedDetailType = null;
                        m.redraw();
                    }
                }, [
                    m("span", {class: "material-symbols-outlined text-sm"}, "close"),
                    m("span", {}, "Close")
                ])
            ])
        ]);
    }

    // ==========================================
    // Actions
    // ==========================================

    function shuffleDeck() {
        deck = shuffleArray(deck);
        previewIndex = 0;
        previewCard = deck.length > 0 ? deck[0] : null;
        page.toast("info", "Deck shuffled!");
        m.redraw();
    }

    function reshuffleDiscard() {
        if (discard.length === 0) {
            page.toast("warn", "No cards in discard pile");
            return;
        }
        deck = deck.concat(shuffleArray(discard));
        discard = [];
        previewIndex = 0;
        previewCard = deck.length > 0 ? deck[0] : null;
        page.toast("info", "Discard pile reshuffled into deck!");
        m.redraw();
    }

    function previewNextCard() {
        if (deck.length === 0) return;
        previewIndex = (previewIndex + 1) % deck.length;
        previewCard = deck[previewIndex];
        m.redraw();
    }

    function previewPrevCard() {
        if (deck.length === 0) return;
        previewIndex = (previewIndex - 1 + deck.length) % deck.length;
        previewCard = deck[previewIndex];
        m.redraw();
    }

    async function drawToUserHand() {
        if (deck.length === 0) {
            page.toast("warn", "No cards in deck");
            return;
        }
        if (userHand.length >= USER_HAND_LIMIT) {
            page.toast("warn", "Your hand is full (limit: " + USER_HAND_LIMIT + ")");
            return;
        }
        // Draw from current preview position (or top if not previewing)
        let drawn = deck.splice(previewIndex, 1)[0];
        // Load full character to get store reference
        drawn = await loadFullCharacter(drawn);
        userHand.push(drawn);
        // Adjust preview index if needed
        if (previewIndex >= deck.length && deck.length > 0) {
            previewIndex = 0;
        }
        previewCard = deck.length > 0 ? deck[previewIndex] : null;

        // Load character's store items and apparel
        let store = await loadCharacterStore(drawn);
        if (store.items.length > 0) {
            itemHand.push(...store.items);
        }
        if (store.apparel.length > 0) {
            apparelHand.push(...store.apparel);
        }

        // Select the drawn card
        await selectCard(drawn);
        page.toast("info", "Drew: " + drawn.name);
    }

    async function drawToGameHand() {
        if (deck.length === 0) {
            page.toast("warn", "No cards in deck");
            return;
        }
        if (gameHand.length >= GAME_HAND_LIMIT) {
            page.toast("warn", "Game hand is full (limit: " + GAME_HAND_LIMIT + ")");
            return;
        }
        // Draw random card for game
        let randIdx = Math.floor(Math.random() * deck.length);
        let drawn = deck.splice(randIdx, 1)[0];
        // Load full character to get store reference
        drawn = await loadFullCharacter(drawn);
        gameHand.push(drawn);
        // Adjust preview index if needed
        if (previewIndex >= deck.length && deck.length > 0) {
            previewIndex = 0;
        }
        previewCard = deck.length > 0 ? deck[previewIndex] : null;

        // Load character's store items and apparel
        let store = await loadCharacterStore(drawn);
        if (store.items.length > 0) {
            systemItems.push(...store.items);
        }
        if (store.apparel.length > 0) {
            systemApparel.push(...store.apparel);
        }

        page.toast("info", "Game drew: " + drawn.name);
        m.redraw();
    }

    function discardCard(card, source) {
        if (!card) return;
        // Determine which hand to remove from
        let hand = source === 'gameHand' ? gameHand : userHand;
        let idx = hand.findIndex(c => c.objectId === card.objectId);
        if (idx > -1) {
            hand.splice(idx, 1);
            discard.push(card);
            // Release player control when discarding from user hand
            if (source === 'userHand') {
                releaseCharacter(card.objectId);
            }
            // Clear selection if discarded card was selected
            if (selectedCard && selectedCard.objectId === card.objectId) {
                selectedCard = null;
            }
            page.toast("info", "Discarded: " + card.name);
            m.redraw();
        }
    }

    function discardFromUserHand() {
        if (!selectedCard) {
            page.toast("warn", "No card selected");
            return;
        }
        let idx = userHand.findIndex(c => c.objectId === selectedCard.objectId);
        if (idx > -1) {
            discardCard(selectedCard, 'userHand');
        } else {
            page.toast("warn", "Selected card is not in your hand");
        }
    }

    function discardFromGameHand() {
        if (!selectedCard) {
            page.toast("warn", "No card selected");
            return;
        }
        let idx = gameHand.findIndex(c => c.objectId === selectedCard.objectId);
        if (idx > -1) {
            discardCard(selectedCard, 'gameHand');
        } else {
            page.toast("warn", "Selected card is not in game hand");
        }
    }

    function discardSelectedCard() {
        if (!selectedCard) {
            page.toast("warn", "No card selected");
            return;
        }
        discardCard(selectedCard);
    }

    async function selectCard(card) {
        // Clear detail card view when selecting a character
        selectedDetailCard = null;
        selectedDetailType = null;
        // Clear cached narrative if selecting a different character
        if (!card || !selectedCard || card.objectId !== selectedCard.objectId) {
            loadedNarrative = null;
            loadedNarrativeCharId = null;
        }
        selectedCard = await loadFullCharacter(card);
        m.redraw();
    }

    function selectHandCard(card) {
        selectCard(card);
    }

    async function refreshSelectedCard() {
        if (!selectedCard) return;
        page.toast("info", "Refreshing card data...");
        am7client.clearCache("olio.charPerson");
        selectedCard = await loadFullCharacter(selectedCard);
        page.toast("success", "Card refreshed");
        m.redraw();
    }

    function openCharacterView(char) {
        if (!char || !char.objectId) return;
        m.route.set("/view/olio.charPerson/" + char.objectId);
    }

    function openPortraitView(char) {
        if (!char || !char.profile || !char.profile.portrait) {
            page.toast("warn", "No portrait available");
            return;
        }
        let pp = char.profile.portrait;
        page.imageView(pp);
    }

    function toggleFullMode() {
        fullMode = !fullMode;
        m.redraw();
    }

    async function generatePortrait() {
        if (!selectedCard || !selectedCard.objectId) {
            page.toast("warn", "No card selected");
            return;
        }
        // First generate narrative if not present (reimage needs it)
        let narrative = selectedCard.narrative;
        if (!narrative || (!narrative.physicalDescription && !narrative.outfitDescription)) {
            page.toast("info", "Generating narrative first...");
            let inst = am7model.prepareInstance(selectedCard);
            await am7model.forms.commands.narrate(undefined, inst);
            // Reload character to get the narrative
            am7client.clearCache("olio.charPerson");
            selectedCard = await loadFullCharacter(selectedCard);
            m.redraw();
        }
        // Now open reimage dialog
        let inst = am7model.prepareInstance(selectedCard);
        page.components.dialog.reimage(selectedCard, inst);
    }

    async function generateNarrative() {
        if (!selectedCard || !selectedCard.objectId) {
            page.toast("warn", "No card selected");
            return;
        }
        page.toast("info", "Generating narrative...");
        try {
            // Use the same approach as formDef.js
            let inst = am7model.prepareInstance(selectedCard);
            await am7model.forms.commands.narrate(undefined, inst);
            // Clear cache and reload the character
            am7client.clearCache("olio.charPerson");
            am7client.clearCache("olio.narrative");
            selectedCard = await loadFullCharacter(selectedCard);
            page.toast("success", "Narrative generated");
        } catch (e) {
            console.error("Narrative generation failed:", e);
            page.toast("error", "Narrative generation failed");
        }
        m.redraw();
    }

    async function dressCharacterUp() {
        if (!selectedCard || !selectedCard.objectId) {
            page.toast("warn", "No card selected");
            return;
        }
        page.toast("info", "Dressing up...");
        let inst = am7model.prepareInstance(selectedCard);
        await am7olio.dressUp(selectedCard, inst, selectedCard.name);
        // Clear cache and reload the character to get updated narrative
        am7client.clearCache("olio.charPerson");
        am7client.clearCache("olio.apparel");
        am7client.clearCache("olio.store");
        selectedCard = await loadFullCharacter(selectedCard);
        m.redraw();
    }

    async function dressCharacterDown() {
        if (!selectedCard || !selectedCard.objectId) {
            page.toast("warn", "No card selected");
            return;
        }
        page.toast("info", "Dressing down...");
        let inst = am7model.prepareInstance(selectedCard);
        await am7olio.dressDown(selectedCard, inst, selectedCard.name);
        // Clear cache and reload the character to get updated narrative
        am7client.clearCache("olio.charPerson");
        am7client.clearCache("olio.apparel");
        am7client.clearCache("olio.store");
        selectedCard = await loadFullCharacter(selectedCard);
        m.redraw();
    }

    // ==========================================
    // Action Card Functions
    // ==========================================

    // Initialize the action deck with 2 copies of each action
    function initActionDeck() {
        actionDeck = [];
        // Add 2 copies of each action type
        ACTION_CARD_TYPES.forEach(a => {
            actionDeck.push({...a, id: a.type + "_1"});
            actionDeck.push({...a, id: a.type + "_2"});
        });
        shuffleActionDeck();
        actionPreviewIndex = 0;
        updateActionPreview();
    }

    function shuffleActionDeck() {
        // Fisher-Yates shuffle
        for (let i = actionDeck.length - 1; i > 0; i--) {
            let j = Math.floor(Math.random() * (i + 1));
            [actionDeck[i], actionDeck[j]] = [actionDeck[j], actionDeck[i]];
        }
        actionPreviewIndex = 0;
        updateActionPreview();
        m.redraw();
    }

    function updateActionPreview() {
        if (actionDeck.length > 0 && actionPreviewIndex < actionDeck.length) {
            actionPreview = actionDeck[actionPreviewIndex];
        } else {
            actionPreview = null;
        }
    }

    function nextActionPreview() {
        if (actionDeck.length === 0) return;
        actionPreviewIndex = (actionPreviewIndex + 1) % actionDeck.length;
        updateActionPreview();
        m.redraw();
    }

    function prevActionPreview() {
        if (actionDeck.length === 0) return;
        actionPreviewIndex = (actionPreviewIndex - 1 + actionDeck.length) % actionDeck.length;
        updateActionPreview();
        m.redraw();
    }

    function reshuffleActionDeck() {
        // Move all discarded actions back to deck and shuffle
        actionDeck.push(...actionDiscard);
        actionDiscard = [];
        shuffleActionDeck();
        page.toast("info", "Reshuffled " + actionDeck.length + " action cards");
    }

    function drawActionToHand() {
        if (actionDeck.length === 0) {
            page.toast("warn", "No actions in deck");
            return;
        }
        // Draw the previewed card (or top card)
        let card;
        if (actionPreviewIndex < actionDeck.length) {
            card = actionDeck.splice(actionPreviewIndex, 1)[0];
        } else {
            card = actionDeck.pop();
        }
        actionHand.push(card);
        actionPreviewIndex = Math.min(actionPreviewIndex, actionDeck.length - 1);
        if (actionPreviewIndex < 0) actionPreviewIndex = 0;
        updateActionPreview();
        page.toast("info", "Drew " + card.label + " to hand");
        m.redraw();
    }

    function selectPlayedAction(action) {
        if (userHand.length === 0) {
            page.toast("warn", "You need a character in your hand first");
            return;
        }
        if (gameHand.length === 0) {
            page.toast("warn", "Game needs a character in hand first");
            return;
        }

        selectedAction = action;
        pendingInteraction = {
            type: action.type,
            label: action.label,
            icon: action.icon,
            description: action.description,
            actor: userHand[0],
            interactor: null,
            actionId: action.id
        };

        // Auto-select first game hand character as target
        if (gameHand.length >= 1) {
            pendingInteraction.interactor = gameHand[0];
        }

        // For Talk action, auto-execute if both hands have characters
        if (action.type === 'TALK' && userHand.length >= 1 && gameHand.length >= 1) {
            executeAction();
            return;
        }

        page.toast("info", "Select a target character from the game hand");
        m.redraw();
    }

    function discardActionFromHand(action) {
        let idx = actionHand.findIndex(a => a.id === action.id);
        if (idx !== -1) {
            actionHand.splice(idx, 1);
            actionDiscard.push(action);
            page.toast("info", "Discarded " + action.label);
        }
        m.redraw();
    }

    function discardPlayedAction(action) {
        let idx = playedActions.findIndex(a => a.id === action.id);
        if (idx !== -1) {
            playedActions.splice(idx, 1);
            // Played actions are interaction objects, don't return the card to discard
            // The card is already consumed when creating the interaction
            page.toast("info", "Discarded " + action.label);
        }
        m.redraw();
    }

    function discardUsedAction() {
        // Discard the action that was just used
        if (selectedAction && selectedAction.id) {
            let idx = playedActions.findIndex(a => a.id === selectedAction.id);
            if (idx !== -1) {
                playedActions.splice(idx, 1);
                actionDiscard.push(selectedAction);
            }
        }
    }

    function discardItemFromHand(item) {
        let idx = itemHand.findIndex(i => i.objectId === item.objectId);
        if (idx !== -1) {
            itemHand.splice(idx, 1);
            itemDiscard.push(item);
            page.toast("info", "Discarded " + (item.name || "item"));
        }
        m.redraw();
    }

    function discardItem(item) {
        // Discard from tray
        let idx = itemTray.findIndex(i => i.objectId === item.objectId);
        if (idx !== -1) {
            itemTray.splice(idx, 1);
            itemDiscard.push(item);
            page.toast("info", "Discarded " + (item.name || "item"));
        }
        m.redraw();
    }

    function discardApparelFromHand(apparel) {
        let idx = apparelHand.findIndex(a => a.objectId === apparel.objectId);
        if (idx !== -1) {
            apparelHand.splice(idx, 1);
            apparelDiscard.push(apparel);
            page.toast("info", "Discarded " + (apparel.name || "apparel"));
        }
        m.redraw();
    }

    function discardApparel(apparel) {
        // Discard from tray
        let idx = apparelTray.findIndex(a => a.objectId === apparel.objectId);
        if (idx !== -1) {
            apparelTray.splice(idx, 1);
            apparelDiscard.push(apparel);
            page.toast("info", "Discarded " + (apparel.name || "apparel"));
        }
        m.redraw();
    }

    function cancelAction() {
        selectedAction = null;
        pendingInteraction = null;
        m.redraw();
    }

    async function executeAction() {
        if (!pendingInteraction) {
            page.toast("warn", "No action selected");
            return;
        }

        // Use already-set interactor, or selectedCard, or first game hand character
        let target = pendingInteraction.interactor || selectedCard;
        if (!target && gameHand.length > 0) {
            target = gameHand[0];
        }
        if (!target || gameHand.findIndex(c => c.objectId === target.objectId) === -1) {
            page.toast("warn", "Select a target from the game hand");
            return;
        }

        pendingInteraction.interactor = target;

        let actor = pendingInteraction.actor;
        let interactor = pendingInteraction.interactor;

        // Handle TALK action specially - opens dialog instead of creating interaction
        if (pendingInteraction.type === "TALK") {
            // Keep TALK action - either leave in tray or move back to hand if other actions played
            if (playedActions.length > 1 && selectedAction) {
                // Move talk action back to hand since there are other actions in play
                let idx = playedActions.findIndex(a => a.id === selectedAction.id);
                if (idx !== -1) {
                    let talkAction = playedActions.splice(idx, 1)[0];
                    actionHand.push(talkAction);
                }
            }
            // If only TALK is in tray, leave it there for reuse

            // Reset action state before opening dialog
            selectedAction = null;
            pendingInteraction = null;

            // Open the chat dialog
            openChatDialog(actor, interactor);
            return;
        }

        page.toast("info", actor.name.split(" ")[0] + " uses " + pendingInteraction.label + " on " + interactor.name.split(" ")[0] + "...");

        try {
            // Create interaction object
            let interaction = await createInteraction(pendingInteraction);

            if (interaction) {
                page.toast("success", "Interaction created: " + pendingInteraction.label);
            }
        } catch (e) {
            console.error("Failed to create interaction:", e);
            page.toast("error", "Failed to execute action");
        }

        // Discard the used action card from tray
        discardUsedAction();

        // Reset action state
        selectedAction = null;
        pendingInteraction = null;
        m.redraw();
    }

    async function createInteraction(pending) {
        let actor = pending.actor;
        let interactor = pending.interactor;

        try {
            // Resolve interaction via server-side action pipeline
            let interaction = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/interact",
                withCredentials: true,
                body: {
                    actorId: actor.objectId,
                    interactorId: interactor.objectId,
                    interactionType: pending.type
                }
            });

            if (interaction) {
                let outcomes = {
                    actorOutcome: interaction.actorOutcome || "equilibrium",
                    interactorOutcome: interaction.interactorOutcome || "equilibrium",
                    description: formatOutcomeLabel(interaction.actorOutcome)
                };
                page.toast("info", formatOutcome(outcomes));
                return interaction;
            }
        } catch(e) {
            console.warn("Server interaction failed, using local fallback:", e);
        }

        // Fallback to local resolution if server is unavailable
        let interaction = {
            schema: "olio.interaction",
            name: pending.label + ": " + actor.name + " -> " + interactor.name,
            description: actor.name + " " + pending.label.toLowerCase() + "s " + interactor.name,
            type: pending.type.toLowerCase(),
            actorType: "olio.charPerson",
            actor: {objectId: actor.objectId, name: actor.name},
            interactorType: "olio.charPerson",
            interactor: {objectId: interactor.objectId, name: interactor.name},
            actorOutcome: "unknown",
            interactorOutcome: "unknown"
        };

        let outcomes = determineOutcome(pending.type, actor, interactor);
        interaction.actorOutcome = outcomes.actorOutcome;
        interaction.interactorOutcome = outcomes.interactorOutcome;
        interaction.description += " - " + formatOutcome(outcomes);
        page.toast("info", formatOutcome(outcomes));
        return interaction;
    }

    function formatOutcomeLabel(outcome) {
        if (!outcome) return "Unknown";
        switch(outcome.toUpperCase()) {
            case "VERY_FAVORABLE": return "Great success!";
            case "FAVORABLE": return "Success";
            case "EQUILIBRIUM": return "Neutral outcome";
            case "UNFAVORABLE": return "Failed";
            case "VERY_UNFAVORABLE": return "Critical failure!";
            default: return outcome.replace(/_/g, " ");
        }
    }

    async function claimCharacter(objectId) {
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/claim/" + objectId,
                withCredentials: true
            });
        } catch(e) {
            console.warn("Failed to claim character:", e);
        }
    }

    async function releaseCharacter(objectId) {
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/release/" + objectId,
                withCredentials: true
            });
        } catch(e) {
            console.warn("Failed to release character:", e);
        }
    }

    async function advanceTurn() {
        try {
            let result = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/advance",
                withCredentials: true
            });
            page.toast("info", "Turn advanced (" + (result.updated || 0) + " characters updated)");
            // Refresh state for characters in hand
            for (let card of userHand) {
                await refreshCharacterState(card);
            }
            m.redraw();
        } catch(e) {
            console.warn("Failed to advance turn:", e);
            page.toast("error", "Failed to advance turn");
        }
    }

    async function refreshCharacterState(card) {
        try {
            let state = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/game/state/" + card.objectId,
                withCredentials: true
            });
            if (state) {
                card._gameState = state;
            }
        } catch(e) {
            // Silently fail for state refresh
        }
    }

    function determineOutcome(actionType, actor, interactor) {
        // Simple outcome determination based on action type and random chance
        // Could be expanded to use character stats, traits, etc.
        let roll = Math.random();
        let actorStats = actor.statistics || {};
        let interactorStats = interactor.statistics || {};

        let outcomes = {
            actorOutcome: "equilibrium",
            interactorOutcome: "equilibrium",
            description: ""
        };

        // Use relevant stats based on action type
        let actorStat = 10;
        let interactorStat = 10;

        switch (actionType) {
            case "COMBAT":
            case "DEFEND":
                actorStat = (actorStats.physicalStrength || 10) + (actorStats.agility || 10);
                interactorStat = (interactorStats.physicalStrength || 10) + (interactorStats.agility || 10);
                break;
            case "COMMUNICATE":
            case "BEFRIEND":
            case "SOCIALIZE":
            case "ROMANCE":
                actorStat = (actorStats.charisma || 10) + (actorStats.wisdom || 10);
                interactorStat = (interactorStats.charisma || 10) + (interactorStats.wisdom || 10);
                break;
            case "COERCE":
            case "THREATEN":
                actorStat = (actorStats.mentalStrength || 10) + (actorStats.charisma || 10);
                interactorStat = (interactorStats.mentalEndurance || 10) + (interactorStats.wisdom || 10);
                break;
            case "BARTER":
            case "NEGOTIATE":
                actorStat = (actorStats.intelligence || 10) + (actorStats.charisma || 10);
                interactorStat = (interactorStats.intelligence || 10) + (interactorStats.wisdom || 10);
                break;
            case "INVESTIGATE":
                actorStat = (actorStats.intelligence || 10) + (actorStats.wisdom || 10);
                interactorStat = 10; // Target doesn't actively resist
                break;
            case "MENTOR":
                actorStat = (actorStats.wisdom || 10) + (actorStats.intelligence || 10);
                interactorStat = (interactorStats.intelligence || 10);
                break;
            default:
                actorStat = actorStats.charisma || 10;
                interactorStat = interactorStats.charisma || 10;
        }

        // Calculate outcome based on stat comparison and roll
        let statDiff = actorStat - interactorStat;
        let threshold = 0.5 + (statDiff / 100); // Adjust threshold by stat difference

        if (roll > threshold + 0.2) {
            // Very favorable for actor
            outcomes.actorOutcome = "very_favorable";
            outcomes.interactorOutcome = "unfavorable";
            outcomes.description = "Great success!";
        } else if (roll > threshold) {
            // Favorable for actor
            outcomes.actorOutcome = "favorable";
            outcomes.interactorOutcome = "equilibrium";
            outcomes.description = "Success";
        } else if (roll > threshold - 0.2) {
            // Equilibrium
            outcomes.actorOutcome = "equilibrium";
            outcomes.interactorOutcome = "equilibrium";
            outcomes.description = "Neutral outcome";
        } else if (roll > threshold - 0.4) {
            // Unfavorable for actor
            outcomes.actorOutcome = "unfavorable";
            outcomes.interactorOutcome = "favorable";
            outcomes.description = "Failed";
        } else {
            // Very unfavorable for actor
            outcomes.actorOutcome = "very_unfavorable";
            outcomes.interactorOutcome = "very_favorable";
            outcomes.description = "Critical failure!";
        }

        return outcomes;
    }

    function formatOutcome(outcomes) {
        return outcomes.description + " (" + outcomes.actorOutcome.replace(/_/g, " ") + ")";
    }

    // ==========================================
    // Chat Dialog Functions
    // ==========================================

    async function openChatDialog(actor, target) {
        if (!actor || !target) {
            page.toast("warn", "Need both characters for chat");
            return;
        }

        chatActor = actor;
        chatTarget = target;
        chatMessages = [];
        chatPending = false;
        chatDialogOpen = true;

        page.toast("info", "Setting up chat between " + actor.name + " and " + target.name + "...");

        try {
            // Load available chat and prompt configs from ~/Chat
            let chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
            let chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 0);
            let promptConfigs = await am7client.list("olio.llm.promptConfig", chatDir.objectId, null, 0, 0);

            // Find template config "Open Chat" and prompt "Chat Prompt"
            let templateCfg = chatConfigs.find(c => c.name === "Open Chat");
            let promptCfg = promptConfigs.find(c => c.name === "Quick Chat Prompt");

            if (!templateCfg) {
                page.toast("error", "Template 'Open Chat' not found in ~/Chat");
                chatDialogOpen = false;
                return;
            }
            if (!promptCfg) {
                page.toast("error", "Prompt 'Chat Prompt' not found in ~/Chat");
                chatDialogOpen = false;
                return;
            }

            // Create unique chat config name for this character pair
            // systemCharacter = target (AI), userCharacter = actor (player)
            let actorFirst = actor.firstName || actor.name.split(" ")[0];
            let targetFirst = target.firstName || target.name.split(" ")[0];
            let chatConfigName = "CardGame - " + actorFirst + " x " + targetFirst;

            // Search for existing chatConfig by name (more reliable than cached list)
            let chatCfg;
            let q = am7view.viewQuery("olio.llm.chatConfig");
            q.field("groupId", chatDir.id);
            q.field("name", chatConfigName);
            q.cache(false);
            let qr = await page.search(q);

            if (qr && qr.results && qr.results.length > 0) {
                // Use existing config
                chatCfg = qr.results[0];
            } else {
                // Load full template to get all fields
                let fullTemplate = await am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);

                // Copy template like doCopy in object.js - copy all fields, clear identity
                let newChatCfg = JSON.parse(JSON.stringify(fullTemplate));

                // Clear identity fields (like doCopy)
                newChatCfg.objectId = undefined;
                newChatCfg.id = undefined;
                newChatCfg.urn = undefined;
                delete newChatCfg.vaultId;
                delete newChatCfg.vaulted;
                delete newChatCfg.keyId;

                // Set new location and name
                newChatCfg.groupId = chatDir.id;
                newChatCfg.groupPath = chatDir.path;
                newChatCfg.name = chatConfigName;

                // Assign characters: target = system (AI), actor = user (player)
                newChatCfg.systemCharacter = { objectId: target.objectId };
                newChatCfg.userCharacter = { objectId: actor.objectId };

                // Create the new chatConfig
                chatCfg = await page.createObject(newChatCfg);
                if (!chatCfg) {
                    page.toast("error", "Failed to create chat config");
                    chatDialogOpen = false;
                    return;
                }
                page.toast("success", "Created chat config: " + chatConfigName);
            }

            // Create chat request name
            let requestName = "CardGame Chat: " + actorFirst + " x " + targetFirst;

            // Search for existing chat request first
            let reqDir = await page.findObject("auth.group", "data", "~/ChatRequests");
            let reqQuery = am7view.viewQuery("olio.llm.chatRequest");
            reqQuery.field("groupId", reqDir.id);
            reqQuery.field("name", requestName);
            reqQuery.cache(false);
            reqQuery.entity.request.push("session", "sessionType", "chatConfig", "promptConfig", "objectId");
            let reqResult = await page.search(reqQuery);

            if (reqResult && reqResult.results && reqResult.results.length > 0) {
                // Use existing chat request
                chatSession = reqResult.results[0];
            } else {
                // Create new chat request
                let chatReq = {
                    schema: "olio.llm.chatRequest",
                    name: requestName,
                    chatConfig: { objectId: chatCfg.objectId },
                    promptConfig: { objectId: promptCfg.objectId },
                    uid: page.uid()
                };

                chatSession = await m.request({
                    method: 'POST',
                    url: g_application_path + "/rest/chat/new",
                    withCredentials: true,
                    body: chatReq
                });
            }

            if (chatSession) {
                // Get chat history
                let historyReq = {
                    schema: chatSession[am7model.jsonModelKey],
                    objectId: chatSession.objectId,
                    uid: page.uid()
                };
                let history = await m.request({
                    method: 'POST',
                    url: g_application_path + "/rest/chat/history",
                    withCredentials: true,
                    body: historyReq
                });
                chatMessages = history?.messages || [];
            }

            m.redraw();
        } catch (e) {
            console.error("Failed to setup chat:", e);
            page.toast("error", "Failed to setup chat");
            chatDialogOpen = false;
        }
    }

    function closeChatDialog() {
        chatDialogOpen = false;
        chatSession = null;
        chatMessages = [];
        chatActor = null;
        chatTarget = null;
        m.redraw();
    }

    async function sendChatMessage(message) {
        if (!chatSession || chatPending) {
            return;
        }

        chatPending = true;
        // Only add user message to display if there's actual content
        if (message.trim()) {
            chatMessages.push({ role: "user", content: message });
        }
        m.redraw();

        try {
            let chatReq = {
                schema: chatSession[am7model.jsonModelKey],
                objectId: chatSession.objectId,
                uid: page.uid(),
                message: message
            };

            let response = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/chat/text",
                withCredentials: true,
                body: chatReq
            });

            if (response && response.messages) {
                chatMessages = response.messages;
            }
        } catch (e) {
            console.error("Chat error:", e);
            page.toast("error", "Chat request failed");
        }

        chatPending = false;
        m.redraw();
    }

    // ==========================================
    // Image Token Functions (delegates to window.am7imageTokens from dialog.js)
    // ==========================================

    // Convenience accessor for shared image token parser
    function parseImageTokens(content) { return window.am7imageTokens ? window.am7imageTokens.parse(content) : []; }

    // Patch a chat message to replace tag-only tokens with id+tag tokens
    async function patchChatMessage(msgIndex, newContent) {
        if (!chatSession) return;

        try {
            // Query for the chat session
            let q = am7view.viewQuery(am7model.newInstance("olio.llm.chatRequest"));
            q.field("id", chatSession.id);
            let qr = await page.search(q);

            if (qr && qr.results && qr.results.length > 0) {
                let req = qr.results[0];
                if (req.messages && req.messages[msgIndex]) {
                    req.messages[msgIndex].content = newContent;
                    await page.patchObject(req);
                }
            }

            // Update local array
            if (chatMessages[msgIndex]) {
                chatMessages[msgIndex].content = newContent;
            }
        } catch (e) {
            console.error("patchChatMessage error:", e);
        }
    }

    // Resolve all image tokens in a message and patch with IDs
    async function resolveMessageImages(msgIndex) {
        let msg = chatMessages[msgIndex];
        if (!msg || !msg.content) return;

        let tokens = parseImageTokens(msg.content);
        if (!tokens.length) return;

        // Determine which character sent this message
        let character = msg.role === "user" ? chatActor : chatTarget;
        if (!character) return;

        let newContent = msg.content;
        let changed = false;

        for (let token of tokens) {
            if (token.id && window.am7imageTokens.cache[token.id]) continue; // Already resolved and cached

            let resolved = await window.am7imageTokens.resolve(token, character);
            if (resolved && resolved.image) {
                if (!token.id) {
                    // Replace tag-only token with id+tag token
                    let newToken = "${image." + resolved.image.objectId + "." + token.tags.join(",") + "}";
                    newContent = newContent.replace(token.match, newToken);
                    changed = true;
                }
                // Image is now in window.am7imageTokens.cache via resolve()
            }
        }

        if (changed) {
            await patchChatMessage(msgIndex, newContent);
        }
        m.redraw();
    }

    // Chat Panel Component - inline in center column (not a modal)
    function ChatPanel() {
        let inputMessage = "";
        let autoStartTimeout = null;
        let userStartedTyping = false;
        let lastMessageCount = -1;
        let showTagSelector = false;
        let selectedImageTags = [];
        let resolvingImages = {}; // msgIndex -> true while resolving

        // Render message content, replacing image tokens with img elements or placeholders
        function renderMessageContent(msg) {
            if (!msg.content) return "";
            let tokens = parseImageTokens(msg.content);
            if (!tokens.length) return msg.content;

            let nodes = [];
            let lastEnd = 0;

            for (let token of tokens) {
                // Add text before this token
                if (token.start > lastEnd) {
                    nodes.push(msg.content.substring(lastEnd, token.start));
                }

                // Render image or placeholder
                if (token.id && window.am7imageTokens.cache[token.id]) {
                    let cached = window.am7imageTokens.cache[token.id];
                    nodes.push(m("img", {
                        src: cached.url,
                        class: "max-w-[200px] rounded-lg my-1 cursor-pointer block",
                        onclick: function(e) {
                            e.stopPropagation();
                            if (cached.image) page.imageView(cached.image);
                        }
                    }));
                } else if (token.id) {
                    // Has ID but not yet in cache - show loading placeholder
                    nodes.push(m("div", {
                        class: "inline-flex items-center gap-1 px-2 py-1 rounded bg-black/20 text-xs my-1"
                    }, [
                        m("span", {class: "material-symbols-outlined text-xs animate-pulse"}, "image"),
                        token.tags.join(", ")
                    ]));
                } else {
                    // No ID yet - show resolving placeholder
                    nodes.push(m("div", {
                        class: "inline-flex items-center gap-1 px-2 py-1 rounded bg-black/20 text-xs my-1"
                    }, [
                        m("span", {class: "material-symbols-outlined text-xs animate-pulse"}, "photo_camera"),
                        "loading " + token.tags.join(", ") + "..."
                    ]));
                }

                lastEnd = token.end;
            }

            // Add remaining text after last token
            if (lastEnd < msg.content.length) {
                nodes.push(msg.content.substring(lastEnd));
            }

            return nodes;
        }

        function toggleImageTag(tag) {
            let idx = selectedImageTags.indexOf(tag);
            if (idx > -1) {
                selectedImageTags.splice(idx, 1);
            } else {
                selectedImageTags.push(tag);
            }
        }

        function insertImageToken() {
            if (!selectedImageTags.length) return;
            let token = "${image." + selectedImageTags.join(",") + "}";
            inputMessage = (inputMessage ? inputMessage + " " : "") + token;
            selectedImageTags = [];
            showTagSelector = false;
        }

        function scrollToLastMessage() {
            let container = document.getElementById("chatMessagesContainer");
            if (!container) return;
            let msgs = container.querySelectorAll(":scope > div");
            if (msgs.length > 0 && lastMessageCount !== msgs.length) {
                lastMessageCount = msgs.length;
                msgs[msgs.length - 1].scrollIntoView({ behavior: "smooth" });
            }
        }

        function clearAutoStartTimeout() {
            if (autoStartTimeout) {
                clearTimeout(autoStartTimeout);
                autoStartTimeout = null;
            }
        }

        function setupAutoStartTimeout() {
            // Only set timeout if no chat history and user hasn't started typing

            if (chatMessages.length === 0 && !userStartedTyping && !chatPending) {
                // Random timeout between 1-15 seconds
                let delay = Math.floor(Math.random() * 14000) + 1000;
                page.toast("info", "chat in " + delay, delay);
                autoStartTimeout = setTimeout(function() {
                    if (!userStartedTyping && chatMessages.length === 0 && !chatPending) {
                        // Submit empty string to let assistant start
                        sendChatMessage("");
                    }
                }, delay);
            }
        }

        return {
            oninit: function() {
                userStartedTyping = false;
                setupAutoStartTimeout();
            },
            onremove: function() {
                clearAutoStartTimeout();
            },
            onupdate: function() {
                scrollToLastMessage();
            },
            view: function() {
                let actorUrl = getPortraitUrl(chatActor, "96x96");
                let targetUrl = getPortraitUrl(chatTarget, "96x96");

                return m("div", {class: "flex flex-col h-full bg-white dark:bg-gray-800"}, [
                    // Header with character portraits - clickable to select character
                    m("div", {class: "cg-chat-header"}, [
                        m("div", {class: "flex items-center space-x-3"}, [
                            // Actor (user's character) - clickable
                            m("div", {
                                class: "cg-chat-character-btn -ml-2",
                                onclick: function() {
                                    if (chatActor) {
                                        selectCard(chatActor);
                                    }
                                },
                                title: "View " + (chatActor?.name || "actor")
                            }, [
                                actorUrl ?
                                    m("img", {src: actorUrl, class: "w-8 h-8 rounded-full border-2 border-white"}) :
                                    m("span", {class: "material-symbols-outlined text-xl"}, "person"),
                                m("span", {class: "text-sm font-medium"}, chatActor?.name?.split(" ")[0] || "Actor")
                            ]),
                            m("span", {class: "material-symbols-outlined text-sm"}, "swap_horiz"),
                            // Target (system's character) - clickable
                            m("div", {
                                class: "cg-chat-character-btn",
                                onclick: function() {
                                    if (chatTarget) {
                                        selectCard(chatTarget);
                                    }
                                },
                                title: "View " + (chatTarget?.name || "target")
                            }, [
                                targetUrl ?
                                    m("img", {src: targetUrl, class: "w-8 h-8 rounded-full border-2 border-white"}) :
                                    m("span", {class: "material-symbols-outlined text-xl"}, "person"),
                                m("span", {class: "text-sm font-medium"}, chatTarget?.name?.split(" ")[0] || "Target")
                            ])
                        ]),
                        m("button", {
                            class: "p-1 rounded hover:bg-purple-500",
                            onclick: closeChatDialog,
                            title: "Close chat"
                        }, m("span", {class: "material-symbols-outlined"}, "close"))
                    ]),

                    // Messages area
                    m("div", {id: "chatMessagesContainer", class: "cg-chat-messages"}, [
                        chatMessages.length === 0 ?
                            m("div", {class: "text-center text-gray-500 dark:text-gray-400 py-8"}, [
                                m("span", {class: "material-symbols-outlined text-4xl block mb-2"}, "forum"),
                                m("div", {}, "Start a conversation...")
                            ]) :
                            chatMessages.map(function(msg, idx) {
                                let isUser = msg.role === "user";
                                let isAssistant = msg.role === "assistant";
                                if (!isUser && !isAssistant) return ""; // Skip system messages

                                // Render message content with image token support
                                let contentNodes = renderMessageContent(msg);

                                // Trigger image resolution for unresolved tokens or uncached IDs
                                if (!resolvingImages[idx]) {
                                    let tokens = parseImageTokens(msg.content);
                                    let needsResolution = tokens.some(t => !t.id || !window.am7imageTokens.cache[t.id]);
                                    if (needsResolution) {
                                        resolvingImages[idx] = true;
                                        resolveMessageImages(idx).then(function() {
                                            resolvingImages[idx] = false;
                                        });
                                    }
                                }

                                return m("div", {
                                    class: "flex " + (isUser ? "justify-end" : "justify-start"),
                                    key: idx
                                }, [
                                    m("div", {
                                        class: "max-w-[85%] px-3 py-2 rounded-lg text-sm " +
                                            (isUser ?
                                                "bg-purple-600 text-white rounded-br-none" :
                                                "bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-gray-200 rounded-bl-none")
                                    }, [
                                        m("div", {class: "text-xs opacity-70 mb-0.5"},
                                            isUser ? (chatActor?.name?.split(" ")[0] || "You") : (chatTarget?.name?.split(" ")[0] || "AI")),
                                        m("div", {class: "whitespace-pre-wrap"}, contentNodes)
                                    ])
                                ]);
                            })
                    ]),

                    // Pending indicator
                    chatPending ? m("div", {class: "cg-chat-pending"}, [
                        m("div", {class: "flex items-center space-x-2 text-gray-500 dark:text-gray-400 text-sm"}, [
                            m("div", {class: "animate-pulse"}, "..."),
                            m("span", {}, chatTarget?.name?.split(" ")[0] + " is typing...")
                        ])
                    ]) : "",

                    // Tag selector row (shown when camera button toggled)
                    (showTagSelector && window.am7imageTokens) ? m("div", {class: "px-3 py-2 border-t border-gray-200 dark:border-gray-600"}, [
                        m("div", {class: "flex flex-wrap gap-1 mb-1"}, [
                            window.am7imageTokens.tags.map(function(tag) {
                                let isSelected = selectedImageTags.indexOf(tag) > -1;
                                return m("button", {
                                    class: "px-2 py-0.5 rounded-full text-xs " +
                                        (isSelected ?
                                            "bg-purple-600 text-white" :
                                            "bg-gray-200 dark:bg-gray-600 text-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-500"),
                                    onclick: function() { toggleImageTag(tag); }
                                }, tag);
                            })
                        ]),
                        selectedImageTags.length > 0 ?
                            m("button", {
                                class: "mt-1 px-3 py-1 rounded bg-purple-600 text-white text-xs hover:bg-purple-700",
                                onclick: function() { insertImageToken(); }
                            }, "Insert " + selectedImageTags.join(",") + " pic") : ""
                    ]) : "",

                    // Input area
                    m("div", {class: "cg-chat-input-area"}, [
                        m("div", {class: "flex space-x-2"}, [
                            // Camera/image button to toggle tag selector
                            m("button", {
                                class: "px-2 py-2 rounded flex-shrink-0 " + (showTagSelector ? "text-purple-400 bg-purple-900" : "text-gray-400 hover:text-purple-400"),
                                onclick: function() {
                                    showTagSelector = !showTagSelector;
                                    if (!showTagSelector) selectedImageTags = [];
                                },
                                title: "Send a pic"
                            }, m("span", {class: "material-symbols-outlined"}, "add_photo_alternate")),
                            m("input", {
                                type: "text",
                                class: "cg-chat-input text-sm focus:ring-2 focus:ring-purple-500",
                                placeholder: "Type a message...",
                                value: inputMessage,
                                disabled: chatPending,
                                oninput: function(e) {
                                    inputMessage = e.target.value;
                                    // Cancel auto-start timeout when user starts typing
                                    if (!userStartedTyping && inputMessage.length > 0) {
                                        userStartedTyping = true;
                                        clearAutoStartTimeout();
                                    }
                                },
                                onkeydown: function(e) {
                                    if (e.key === "Enter" && !e.shiftKey && inputMessage.trim()) {
                                        sendChatMessage(inputMessage);
                                        inputMessage = "";
                                    }
                                }
                            }),
                            m("button", {
                                class: "cg-chat-send-btn",
                                disabled: chatPending || !inputMessage.trim(),
                                onclick: function() {
                                    if (inputMessage.trim()) {
                                        sendChatMessage(inputMessage);
                                        inputMessage = "";
                                    }
                                }
                            }, m("span", {class: "material-symbols-outlined text-sm"}, "send"))
                        ])
                    ])
                ]);
            }
        };
    }

    // Keep ChatDialog for backwards compatibility but it now returns empty (ChatPanel is used inline)
    function ChatDialog() {
        return {
            view: function() {
                return ""; // Chat is now inline in the center panel
            }
        };
    }

    // ==========================================
    // Save Game Functions
    // ==========================================

    function getGameState() {
        // Capture current game state for saving
        return {
            version: 2,
            timestamp: new Date().toISOString(),
            // Character cards - store objectIds for reference
            deck: deck.map(c => c.objectId),
            userHand: userHand.map(c => c.objectId),
            gameHand: gameHand.map(c => c.objectId),
            discard: discard.map(c => c.objectId),
            // Action cards - store full action data since they're local
            actionDeck: actionDeck.map(a => ({type: a.type, id: a.id})),
            playedActions: playedActions.map(a => ({type: a.type, id: a.id})),
            actionDiscard: actionDiscard.map(a => ({type: a.type, id: a.id})),
            // Item cards - store objectIds
            itemDeck: itemDeck.map(i => i.objectId),
            itemHand: itemHand.map(i => i.objectId),
            itemDiscard: itemDiscard.map(i => i.objectId),
            // Apparel cards - store objectIds
            apparelDeck: apparelDeck.map(a => a.objectId),
            apparelHand: apparelHand.map(a => a.objectId),
            apparelDiscard: apparelDiscard.map(a => a.objectId),
            // Selected state
            selectedCardId: selectedCard ? selectedCard.objectId : null,
            previewIndex: previewIndex,
            actionPreviewIndex: actionPreviewIndex,
            itemPreviewIndex: itemPreviewIndex,
            apparelPreviewIndex: apparelPreviewIndex,
            activePile: activePile
        };
    }

    async function loadGameState(state) {
        if (!state || (state.version !== 1 && state.version !== 2)) {
            page.toast("error", "Invalid save game format");
            return false;
        }

        try {
            // Load all data first
            await loadCharacters();
            await loadItems();
            await loadApparel();

            // Helper to find character by objectId
            function findChar(objectId) {
                return deck.find(c => c.objectId === objectId) ||
                       userHand.find(c => c.objectId === objectId) ||
                       gameHand.find(c => c.objectId === objectId) ||
                       discard.find(c => c.objectId === objectId);
            }

            // Rebuild character piles from saved objectIds
            let allChars = [...deck, ...userHand, ...gameHand, ...discard];
            deck = [];
            userHand = [];
            gameHand = [];
            discard = [];

            // Restore deck
            state.deck.forEach(id => {
                let char = allChars.find(c => c.objectId === id);
                if (char) deck.push(char);
            });

            // Restore hands
            state.userHand.forEach(id => {
                let char = allChars.find(c => c.objectId === id);
                if (char) userHand.push(char);
            });
            state.gameHand.forEach(id => {
                let char = allChars.find(c => c.objectId === id);
                if (char) gameHand.push(char);
            });

            // Restore discard
            state.discard.forEach(id => {
                let char = allChars.find(c => c.objectId === id);
                if (char) discard.push(char);
            });

            // Helper to rebuild action card from saved data
            function rebuildAction(saved) {
                let template = ACTION_CARD_TYPES.find(a => a.type === saved.type);
                if (template) {
                    return {...template, id: saved.id};
                }
                return null;
            }

            // Restore action piles
            actionDeck = state.actionDeck.map(rebuildAction).filter(a => a);
            playedActions = state.playedActions.map(rebuildAction).filter(a => a);
            actionDiscard = state.actionDiscard.map(rebuildAction).filter(a => a);

            // Restore item piles (version 2+)
            if (state.version >= 2 && state.itemDeck) {
                let allItems = [...itemDeck, ...itemHand, ...itemDiscard];
                itemDeck = [];
                itemHand = [];
                itemDiscard = [];

                state.itemDeck.forEach(id => {
                    let item = allItems.find(i => i.objectId === id);
                    if (item) itemDeck.push(item);
                });
                state.itemHand.forEach(id => {
                    let item = allItems.find(i => i.objectId === id);
                    if (item) itemHand.push(item);
                });
                state.itemDiscard.forEach(id => {
                    let item = allItems.find(i => i.objectId === id);
                    if (item) itemDiscard.push(item);
                });
            }

            // Restore apparel piles (version 2+)
            if (state.version >= 2 && state.apparelDeck) {
                let allApparel = [...apparelDeck, ...apparelHand, ...apparelDiscard];
                apparelDeck = [];
                apparelHand = [];
                apparelDiscard = [];

                state.apparelDeck.forEach(id => {
                    let app = allApparel.find(a => a.objectId === id);
                    if (app) apparelDeck.push(app);
                });
                state.apparelHand.forEach(id => {
                    let app = allApparel.find(a => a.objectId === id);
                    if (app) apparelHand.push(app);
                });
                state.apparelDiscard.forEach(id => {
                    let app = allApparel.find(a => a.objectId === id);
                    if (app) apparelDiscard.push(app);
                });
            }

            // Restore selection state
            previewIndex = state.previewIndex || 0;
            actionPreviewIndex = state.actionPreviewIndex || 0;
            itemPreviewIndex = state.itemPreviewIndex || 0;
            apparelPreviewIndex = state.apparelPreviewIndex || 0;
            activePile = state.activePile || 'character';
            updateActionPreview();

            if (state.selectedCardId) {
                let char = findChar(state.selectedCardId);
                if (char) {
                    // Load full character data including statistics
                    selectedCard = await loadFullCharacter(char);
                } else {
                    selectedCard = null;
                }
            } else {
                selectedCard = null;
            }

            // Update preview
            previewCard = deck.length > 0 ? deck[previewIndex] : null;

            page.toast("success", "Game loaded");
            m.redraw();
            return true;
        } catch (e) {
            console.error("Failed to load game state:", e);
            page.toast("error", "Failed to load game");
            return false;
        }
    }

    async function loadSavedGames() {
        try {
            let saveDir = await page.makePath("auth.group", "data", "~/Saved Games");
            let saves = await am7client.list("data.data", saveDir.objectId, null, 0, 0);
            savedGames = saves || [];
            m.redraw();
        } catch (e) {
            console.error("Failed to load saved games:", e);
            savedGames = [];
        }
    }

    async function saveGame(name) {
        if (!name || !name.trim()) {
            page.toast("warn", "Please enter a save name");
            return;
        }

        try {
            let saveDir = await page.makePath("auth.group", "data", "~/Saved Games");
            let state = getGameState();
            let stateJson = JSON.stringify(state);

            // Check if save with this name exists
            let existing = savedGames.find(s => s.name === name.trim());

            if (existing) {
                // Update existing save
                existing.dataBytesStore = btoa(stateJson);
                await page.patchObject({
                    schema: "data.data",
                    id: existing.id,
                    dataBytesStore: existing.dataBytesStore
                });
                currentSave = existing;
                page.toast("success", "Game saved: " + name);
            } else {
                // Create new save
                let saveInst = am7model.newInstance("data.data");
                saveInst.api.groupId(saveDir.id);
                saveInst.api.groupPath(saveDir.path);
                saveInst.api.name(name.trim());
                saveInst.api.contentType("application/json");
                saveInst.entity.dataBytesStore = btoa(stateJson);

                let saved = await page.createObject(saveInst.entity);
                if (saved) {
                    currentSave = saved;
                    page.toast("success", "Game saved: " + name);
                    await loadSavedGames();
                } else {
                    page.toast("error", "Failed to create save");
                }
            }

            saveDialogOpen = false;
            m.redraw();
        } catch (e) {
            console.error("Failed to save game:", e);
            page.toast("error", "Failed to save game");
        }
    }

    async function loadGame(save) {
        if (!save) return;

        try {
            // Get full save data
            let fullSave = await am7client.getFull("data.data", save.objectId);
            if (!fullSave || !fullSave.dataBytesStore) {
                page.toast("error", "Save data not found");
                return;
            }

            let stateJson = atob(fullSave.dataBytesStore);
            let state = JSON.parse(stateJson);

            let success = await loadGameState(state);
            if (success) {
                currentSave = save;
                saveDialogOpen = false;
            }
        } catch (e) {
            console.error("Failed to load game:", e);
            page.toast("error", "Failed to load game");
        }
    }

    async function deleteGame(save) {
        if (!save) return;

        try {
            await page.deleteObject("data.data", save.objectId);
            page.toast("success", "Deleted: " + save.name);

            if (currentSave && currentSave.objectId === save.objectId) {
                currentSave = null;
            }

            await loadSavedGames();
        } catch (e) {
            console.error("Failed to delete save:", e);
            page.toast("error", "Failed to delete save");
        }
    }

    async function newGame() {
        // Reset all game state
        currentSave = null;
        deck = [];
        userHand = [];
        gameHand = [];
        discard = [];
        selectedCard = null;
        previewCard = null;
        previewIndex = 0;

        // Reset action state
        playedActions = [];
        actionDiscard = [];
        selectedAction = null;
        pendingInteraction = null;

        // Reset item state
        itemDeck = [];
        itemHand = [];
        itemDiscard = [];
        itemPreviewIndex = 0;

        // Reset apparel state
        apparelDeck = [];
        apparelHand = [];
        apparelDiscard = [];
        apparelPreviewIndex = 0;

        // Reload all decks
        await loadCharacters();
        await loadItems();
        await loadApparel();
        initActionDeck();

        // Deal initial cards
        await newDeal();

        saveDialogOpen = false;
        page.toast("info", "New game started with initial deal");
        m.redraw();
    }

    // Create an interaction object from an action card
    function createInteractionFromAction(action) {
        return {
            id: action.id + '_' + Date.now() + '_' + Math.random().toString(36).substring(2, 11),
            type: action.type,
            label: action.label,
            icon: action.icon,
            description: action.description,
            cardId: action.id,
            timestamp: Date.now(),
            isDefault: action.isDefault || false  // Track if this is an auto-played default action
        };
    }

    // Auto-play a Talk card to both trays (special rule: Talk is always available)
    function autoPlayTalkCards() {
        // Find and play a Talk card for user
        let userTalkIdx = actionDeck.findIndex(a => a.type === 'TALK');
        if (userTalkIdx > -1) {
            let talkCard = actionDeck.splice(userTalkIdx, 1)[0];
            talkCard.isDefault = true;  // Mark as default/permanent
            let interaction = createInteractionFromAction(talkCard);
            playedActions.push(interaction);
        }

        // Find and play a Talk card for system
        let sysTalkIdx = actionDeck.findIndex(a => a.type === 'TALK');
        if (sysTalkIdx > -1) {
            let talkCard = actionDeck.splice(sysTalkIdx, 1)[0];
            talkCard.isDefault = true;
            let interaction = createInteractionFromAction(talkCard);
            systemPlayedActions.push(interaction);
        }
    }

    async function newDeal() {
        // New deal for BOTH user and game:
        // Each gets: 1 character, 1 auto-played Talk, 3 random actions
        // Items and apparel come from the character's store
        page.toast("info", "Dealing hands...");

        // Shuffle all decks first
        deck = shuffleArray(deck);
        actionDeck = shuffleArray(actionDeck);
        itemDeck = shuffleArray(itemDeck);
        apparelDeck = shuffleArray(apparelDeck);

        // SPECIAL RULE: Auto-play Talk cards to both trays first
        autoPlayTalkCards();

        // Deal 1 character to user hand and load their store items/apparel
        let userChar = null;
        if (deck.length > 0 && userHand.length < USER_HAND_LIMIT) {
            userChar = deck.splice(0, 1)[0];
            // Load full character to get store reference
            userChar = await loadFullCharacter(userChar);
            userHand.push(userChar);
            claimCharacter(userChar.objectId);
            await refreshCharacterState(userChar);
            await selectCard(userChar);

            // Load character's store items and apparel
            let userStore = await loadCharacterStore(userChar);
            if (userStore.items.length > 0) {
                itemHand.push(...userStore.items);
            }
            if (userStore.apparel.length > 0) {
                apparelHand.push(...userStore.apparel);
            }
        }

        // Deal 1 character to game hand and load their store items/apparel
        let gameChar = null;
        if (deck.length > 0 && gameHand.length < GAME_HAND_LIMIT) {
            gameChar = deck.splice(0, 1)[0];
            // Load full character to get store reference
            gameChar = await loadFullCharacter(gameChar);
            gameHand.push(gameChar);

            // Load character's store items and apparel
            let gameStore = await loadCharacterStore(gameChar);
            if (gameStore.items.length > 0) {
                systemItems.push(...gameStore.items);
            }
            if (gameStore.apparel.length > 0) {
                systemApparel.push(...gameStore.apparel);
            }
        }

        // Deal 3 random action cards to user's hand (excluding Talk since it's auto-played)
        for (let i = 0; i < 3 && actionDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * actionDeck.length);
            let action = actionDeck.splice(randIdx, 1)[0];
            actionHand.push(action);
        }

        // System only gets Talk auto-played to tray, no cards in hand

        // Update preview indices
        previewIndex = 0;
        previewCard = deck.length > 0 ? deck[0] : null;
        actionPreviewIndex = 0;
        updateActionPreview();
        itemPreviewIndex = 0;
        apparelPreviewIndex = 0;

        m.redraw();
    }

    async function openSaveDialog(mode) {
        saveDialogMode = mode || 'list';
        await loadSavedGames();
        // Default name: current save name if exists, otherwise "Save #(count+1)"
        if (currentSave) {
            newSaveName = currentSave.name;
        } else {
            newSaveName = "Save #" + (savedGames.length + 1);
        }
        saveDialogOpen = true;
        m.redraw();
    }

    function closeSaveDialog() {
        saveDialogOpen = false;
        m.redraw();
    }

    // Save Game Dialog Component
    function SaveGameDialog() {
        return {
            view: function() {
                if (!saveDialogOpen) return "";

                return m("div", {
                    class: "cg-modal-overlay",
                    onclick: function(e) { if (e.target === e.currentTarget) closeSaveDialog(); }
                }, [
                    m("div", {
                        class: "cg-modal-content",
                        style: "height: 400px;",
                        onclick: function(e) { e.stopPropagation(); }
                    }, [
                        // Header
                        m("div", {class: "cg-modal-header"}, [
                            m("div", {class: "flex items-center space-x-2"}, [
                                m("span", {class: "material-symbols-outlined"}, "save"),
                                m("span", {class: "font-medium"},
                                    saveDialogMode === 'save' ? "Save Game" :
                                    saveDialogMode === 'new' ? "New Game" : "Load Game")
                            ]),
                            m("button", {
                                class: "p-1 rounded hover:bg-blue-500",
                                onclick: closeSaveDialog
                            }, m("span", {class: "material-symbols-outlined"}, "close"))
                        ]),

                        // Mode buttons (fixed, not scrollable)
                        m("div", {class: "flex-shrink-0 flex space-x-2 p-4 pb-2"}, [
                            m("button", {
                                class: "flex-1 px-3 py-2 rounded text-sm " +
                                    (saveDialogMode === 'list' ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-gray-200"),
                                onclick: function() { saveDialogMode = 'list'; }
                            }, "Load"),
                            m("button", {
                                class: "flex-1 px-3 py-2 rounded text-sm " +
                                    (saveDialogMode === 'save' ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-gray-200"),
                                onclick: function() { saveDialogMode = 'save'; }
                            }, "Save"),
                            m("button", {
                                class: "flex-1 px-3 py-2 rounded text-sm " +
                                    (saveDialogMode === 'new' ? "bg-blue-600 text-white" : "bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-gray-200"),
                                onclick: function() { saveDialogMode = 'new'; }
                            }, "New")
                        ]),

                        // Content area (scrollable)
                        m("div", {class: "flex-1 overflow-y-auto px-4 pb-4"}, [
                            // Save mode - input for save name
                            saveDialogMode === 'save' ? [
                                m("div", {class: "mb-4"}, [
                                    m("label", {class: "cg-input-label"}, "Save Name"),
                                    m("input", {
                                        type: "text",
                                        class: "cg-input-field",
                                        placeholder: "Enter save name...",
                                        value: newSaveName,
                                        oninput: function(e) { newSaveName = e.target.value; }
                                    })
                                ]),
                                m("button", {
                                    class: "cg-btn-submit-green",
                                    disabled: !newSaveName.trim(),
                                    onclick: function() { saveGame(newSaveName); }
                                }, "Save Game")
                            ] : "",

                            // New game mode - confirmation
                            saveDialogMode === 'new' ? [
                                m("div", {class: "text-center py-4"}, [
                                    m("span", {class: "material-symbols-outlined text-4xl text-yellow-500 mb-2"}, "warning"),
                                    m("p", {class: "text-gray-700 dark:text-gray-300 mb-4"}, "Start a new game? Current progress will be lost unless saved."),
                                    m("button", {
                                        class: "cg-btn-submit-red",
                                        onclick: newGame
                                    }, "Start New Game")
                                ])
                            ] : "",

                            // List mode - show saved games (this is the scrollable part)
                            saveDialogMode === 'list' ? [
                                savedGames.length === 0 ?
                                    m("div", {class: "text-center py-8 text-gray-500 dark:text-gray-400"}, [
                                        m("span", {class: "material-symbols-outlined text-4xl mb-2"}, "folder_open"),
                                        m("p", {}, "No saved games found")
                                    ]) :
                                    m("div", {class: "space-y-2"}, savedGames.map(function(save) {
                                        let isCurrent = currentSave && currentSave.objectId === save.objectId;
                                        return m("div", {
                                            class: "cg-save-list-item " +
                                                (isCurrent ? "cg-save-list-item-active" : "cg-save-list-item-inactive")
                                        }, [
                                            m("div", {class: "flex-1"}, [
                                                m("div", {class: "font-medium text-gray-800 dark:text-gray-200"}, save.name),
                                                m("div", {class: "cg-nav-label"},
                                                    save.modifiedDate ? new Date(save.modifiedDate).toLocaleString() : "")
                                            ]),
                                            m("div", {class: "flex space-x-1"}, [
                                                m("button", {
                                                    class: "cg-save-btn-load",
                                                    onclick: function() { loadGame(save); },
                                                    title: "Load"
                                                }, m("span", {class: "material-symbols-outlined text-sm"}, "play_arrow")),
                                                m("button", {
                                                    class: "cg-save-btn-delete",
                                                    onclick: function() { deleteGame(save); },
                                                    title: "Delete"
                                                }, m("span", {class: "material-symbols-outlined text-sm"}, "delete"))
                                            ])
                                        ]);
                                    }))
                            ] : ""
                        ]),

                        // Footer with current save info
                        currentSave ? m("div", {class: "cg-modal-footer"}, [
                            m("span", {class: "cg-nav-label"}, "Current: " + currentSave.name)
                        ]) : ""
                    ])
                ]);
            }
        };
    }

    // ==========================================
    // Main View
    // ==========================================

    function getOuterView() {
        return m("div", {class: "content-outer"}, [
            fullMode ? "" : m(page.components.navigation),
            m("div", {class: "content-main"}, [
                mainPanel()
            ])
        ]);
    }

    function mainPanel() {
        // On small screens: vertical scroll layout
        // On medium+ screens: horizontal layout with fixed panels
        return m("div", {class: "cg-main-panel"}, [
            m("div", {class: "cg-main-panel-inner"}, [
                // Left panel - Draw Piles (horizontal row on mobile, vertical column on desktop)
                m("div", {class: "cg-left-panel"}, [
                    m(ConsolidatedDecks)
                ]),

                // Center panel - Either hands or action view (chat, etc.)
                chatDialogOpen ?
                    // Chat view replaces the hands
                    m("div", {class: "cg-center-panel"}, [
                        m(ChatPanel)
                    ]) :
                    // Default: Both hands stacked (actions integrated into hand headers)
                    m("div", {class: "cg-center-panel"}, [
                        // User's hand (top) - 50%
                        m("div", {class: "flex-1 min-h-32 md:min-h-0 overflow-hidden"}, [
                            m(UserHandPile)
                        ]),
                        // System's hand (bottom) - 50%
                        m("div", {class: "flex-1 min-h-32 md:min-h-0 overflow-hidden"}, [
                            m(GameHandPile)
                        ])
                    ]),

                // Right panel - Selected Card View
                m("div", {class: "cg-right-panel"}, [
                    m(SelectedCardView)
                ])
            ])
        ]);
    }

    function toggleTag(tag) {
        let idx = activeTags.indexOf(tag);
        if (idx >= 0) {
            activeTags.splice(idx, 1);
        } else {
            activeTags.push(tag);
        }
        // Reload characters with new filter
        loadCharacters();
    }

    function getFooter() {
        let nudeActive = activeTags.indexOf("nude") >= 0;
        return [
            // Save/Load game button
            m("button", {
                class: "flyout-button",
                onclick: function() { openSaveDialog('list'); }
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "folder_open"),
                "Game"
            ]),
            // Quick save (if current save exists)
            currentSave ? m("button", {
                class: "flyout-button",
                onclick: function() { saveGame(currentSave.name); }
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "save"),
                "Save"
            ]) : "",
            // Tag filter - Nude toggle
            m("button", {
                class: "flyout-button" + (nudeActive ? " bg-pink-600 hover:bg-pink-500 text-white" : ""),
                onclick: function() { toggleTag("nude"); },
                title: nudeActive ? "Nude filter active - click to disable" : "Filter by nude tag"
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, nudeActive ? "visibility" : "visibility_off"),
                "Nude"
            ]),
            // New Deal button
            m("button", {
                class: "flyout-button",
                onclick: newDeal
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "playing_cards"),
                "Deal"
            ]),
            // End Turn button - advances time for claimed characters
            m("button", {
                class: "flyout-button",
                onclick: advanceTurn,
                title: "End turn: advance time and accumulate needs for claimed characters"
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "skip_next"),
                "End Turn"
            ]),
            m("button", {
                class: "flyout-button",
                onclick: toggleFullMode
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, fullMode ? "close_fullscreen" : "fullscreen"),
                fullMode ? "Exit" : "Full"
            ])
        ];
    }

    // ==========================================
    // Component Export
    // ==========================================

    let cardGame = {
        oninit: async function() {
            await loadCharacters();
            await loadItems();
            await loadApparel();
            initActionDeck();
            // Do initial deal
            await newDeal();
        },
        view: function() {
            return [
                getOuterView(),
                page.components.dialog.loadDialog(),
                page.loadToast(),
                // Chat dialog
                m(ChatDialog),
                // Save game dialog
                m(SaveGameDialog),
                // Footer toolbar
                m("div", {class: "cg-footer-fixed"},
                    getFooter()
                )
            ];
        }
    };

    page.views.cardGame = cardGame;
}());
