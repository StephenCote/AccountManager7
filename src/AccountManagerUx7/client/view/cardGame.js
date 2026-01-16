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
    let playedActions = [];     // User's actions played to tray (available to use)
    let systemActions = [];     // System's actions played to tray
    let actionDiscard = [];     // Discarded action cards
    let actionPreview = null;   // Preview card in action deck
    let actionPreviewIndex = 0; // Current preview index
    let selectedAction = null;  // Currently selected action for execution
    let pendingInteraction = null; // Interaction being set up

    // Item deck state
    let itemDeck = [];          // Item cards available to draw
    let itemHand = [];          // User's items in hand
    let systemItems = [];       // System's items in hand
    let itemDiscard = [];       // Discarded items
    let itemPreviewIndex = 0;   // Current preview index

    // Apparel deck state
    let apparelDeck = [];       // Apparel cards available to draw
    let apparelHand = [];       // User's apparel in hand
    let systemApparel = [];     // System's apparel in hand
    let apparelDiscard = [];    // Discarded apparel
    let apparelPreviewIndex = 0; // Current preview index

    // Active pile selection for consolidated view
    let activePile = 'character'; // 'character', 'action', 'item', 'apparel'

    // Action card types (these become cards in the action deck)
    // Each action appears twice in the deck
    const ACTION_CARD_TYPES = [
        {type: "CHAT", label: "Chat", icon: "forum", description: "Have a conversation"},
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

    // Card dimensions (3x5 aspect ratio)
    const CARD_WIDTH = 240;
    const CARD_HEIGHT = 400;

    // ==========================================
    // Utility Functions
    // ==========================================

    function getAge(char) {
        if (!char || !char.birthDate) return "?";
        let bd = new Date(char.birthDate);
        let now = new Date();
        return Math.floor((now - bd) / (365.25 * 24 * 60 * 60 * 1000));
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
            q.entity.request.push("profile", "name", "gender", "objectId");

            let qr = await page.search(q);
            if (qr && qr.results) {
                deck = qr.results;
                userHand = [];
                gameHand = [];
                discard = [];
                previewIndex = 0;
                selectedCard = null;
                previewCard = null;
                console.log("Loaded characters:", deck);
                page.toast("success", "Loaded " + deck.length + " characters into deck");
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
            console.log("Loading full character:", char.objectId);
            let full = await am7client.getFull("olio.charPerson", char.objectId);
            console.log("Full character loaded:", full);
            if (full) {
                am7model.applyModelNames(full);
                return full;
            }
        } catch (e) {
            console.error("Failed to load full character", e);
        }
        return char;
    }

    async function loadItems() {
        try {
            let itemDir = await page.findObject("auth.group", "data", gridPath + "/Items");
            if (!itemDir) {
                console.log("Items directory not found, skipping");
                return;
            }

            let q = am7view.viewQuery("olio.item");
            q.field("groupId", itemDir.id);
            q.range(0, 100);
            q.entity.request.push("name", "type", "category", "objectId");

            let qr = await page.search(q);
            if (qr && qr.results) {
                itemDeck = qr.results;
                itemHand = [];
                itemDiscard = [];
                itemPreviewIndex = 0;
                console.log("Loaded items:", itemDeck.length);
            }
        } catch (e) {
            console.error("Failed to load items", e);
        }
    }

    async function loadApparel() {
        try {
            let apparelDir = await page.findObject("auth.group", "data", gridPath + "/Apparel");
            if (!apparelDir) {
                console.log("Apparel directory not found, skipping");
                return;
            }

            let q = am7view.viewQuery("olio.apparel");
            q.field("groupId", apparelDir.id);
            q.range(0, 100);
            q.entity.request.push("name", "type", "category", "objectId", "description");

            let qr = await page.search(q);
            if (qr && qr.results) {
                apparelDeck = qr.results;
                apparelHand = [];
                apparelDiscard = [];
                apparelPreviewIndex = 0;
                console.log("Loaded apparel:", apparelDeck.length);
            }
        } catch (e) {
            console.error("Failed to load apparel", e);
        }
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

                let cardClass = "rounded-lg shadow-xl overflow-hidden cursor-pointer transition-all duration-200 " +
                    "bg-gradient-to-b from-amber-50 to-amber-100 dark:from-gray-700 dark:to-gray-800 border-2 " +
                    (isSelected ? "border-amber-500 ring-2 ring-amber-400" : "border-amber-300 dark:border-gray-600 hover:border-amber-400");

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
            m("div", {class: "px-3 py-2 bg-amber-200 dark:bg-gray-700 border-b border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-lg font-bold text-gray-800 dark:text-gray-100 truncate"}, char.name || "Unknown"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400"}, char.title || "")
            ]),

            // Portrait
            m("div", {class: "flex-1 flex flex-col items-center justify-center bg-gray-200 dark:bg-gray-700 p-2 min-h-0 relative"}, [
                portraitUrl ?
                    m("div", {class: "relative max-h-full max-w-full"}, [
                        m("img", {
                            class: "max-h-full max-w-full object-contain rounded shadow-md cursor-pointer hover:opacity-90",
                            src: portraitUrl,
                            onclick: function(e) { e.stopPropagation(); openPortraitView(char); }
                        }),
                        // Reimage overlay button
                        m("button", {
                            class: "absolute bottom-1 right-1 p-1 rounded-full bg-black/50 hover:bg-black/70 text-white opacity-60 hover:opacity-100 transition-opacity",
                            onclick: function(e) { e.stopPropagation(); generatePortrait(); },
                            title: "Regenerate portrait"
                        }, m("span", {class: "material-symbols-outlined text-sm"}, "refresh"))
                    ]) :
                    m("div", {class: "flex flex-col items-center"}, [
                        m("span", {class: "material-symbols-outlined text-6xl text-gray-400 dark:text-gray-500 mb-2"}, "person"),
                        m("button", {
                            class: "px-3 py-1 rounded bg-blue-600 hover:bg-blue-500 text-white text-xs flex items-center space-x-1",
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
                char.eyeColor || char.hairColor ? m("div", {class: "pt-1 border-t border-amber-200 dark:border-gray-700 text-xs text-gray-600 dark:text-gray-400"}, [
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
            m("div", {class: "px-3 py-2 bg-amber-200 dark:bg-gray-700 border-b border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-lg font-bold text-gray-800 dark:text-gray-100"}, "Statistics"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 truncate"}, char.name)
            ]),

            // Stats list
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-2"},
                statFields.map(function(sf) {
                    let val = stats[sf.name];
                    let pct = val !== undefined ? (val / 20) * 100 : 0;

                    return m("div", {class: "flex items-center space-x-2"}, [
                        m("span", {class: "material-symbols-outlined text-sm text-gray-500 dark:text-gray-400"}, sf.icon),
                        m("span", {class: "w-8 text-xs text-gray-700 dark:text-gray-300"}, sf.label),
                        m("div", {class: "flex-1 h-4 bg-gray-300 dark:bg-gray-600 rounded overflow-hidden"}, [
                            m("div", {
                                class: "h-full " + getStatColor(val, 20),
                                style: "width: " + pct + "%"
                            })
                        ]),
                        m("span", {class: "w-6 text-right text-xs text-gray-800 dark:text-gray-200"}, formatStatValue(val))
                    ]);
                })
            ),

            // Derived stats footer
            m("div", {class: "p-2 bg-amber-100 dark:bg-gray-800 border-t border-amber-200 dark:border-gray-700 text-xs"}, [
                m("div", {class: "flex justify-between"}, [
                    m("span", {class: "text-gray-600 dark:text-gray-400"}, "Health:"),
                    m("span", {class: "text-green-600 dark:text-green-400"}, formatStatValue(stats.health))
                ]),
                m("div", {class: "flex justify-between"}, [
                    m("span", {class: "text-gray-600 dark:text-gray-400"}, "Power:"),
                    m("span", {class: "text-blue-600 dark:text-blue-400"}, formatStatValue(stats.power))
                ])
            ])
        ]);
    }

    function renderTraitsView(char) {
        let traits = char.traits || [];

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "px-3 py-2 bg-amber-200 dark:bg-gray-700 border-b border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-lg font-bold text-gray-800 dark:text-gray-100"}, "Traits"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 truncate"}, char.name)
            ]),

            // Traits list
            m("div", {class: "flex-1 overflow-y-auto p-2"}, [
                traits.length === 0 ?
                    m("div", {class: "text-center text-gray-500 dark:text-gray-400 py-4"}, "No traits defined") :
                    m("div", {class: "flex flex-wrap gap-2"},
                        traits.map(function(trait) {
                            let tname = trait.name || trait;
                            return m("span", {
                                class: "px-2 py-1 rounded-full text-xs bg-amber-200 dark:bg-gray-600 text-gray-800 dark:text-gray-200 border border-amber-300 dark:border-gray-500"
                            }, tname);
                        })
                    )
            ]),

            // Alignment section
            m("div", {class: "p-2 bg-amber-100 dark:bg-gray-800 border-t border-amber-200 dark:border-gray-700"}, [
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 mb-1"}, "Alignment"),
                m("div", {class: "text-sm text-gray-800 dark:text-gray-200"}, char.alignment || "Neutral")
            ])
        ]);
    }

    function renderNarrativeView(char) {
        let narrative = char.narrative || {};
        let hasNarrative = narrative.physicalDescription || narrative.outfitDescription || narrative.statisticsDescription;

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "px-3 py-2 bg-amber-200 dark:bg-gray-700 border-b border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-lg font-bold text-gray-800 dark:text-gray-100"}, "Narrative"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 truncate"}, char.name)
            ]),

            // Narrative content
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-3 text-sm"}, [
                narrative.physicalDescription ? m("div", {}, [
                    m("div", {class: "text-xs text-gray-500 dark:text-gray-400 mb-1"}, "Physical"),
                    m("div", {class: "text-gray-800 dark:text-gray-200"}, narrative.physicalDescription)
                ]) : "",

                narrative.outfitDescription ? m("div", {}, [
                    m("div", {class: "text-xs text-gray-500 dark:text-gray-400 mb-1"}, "Outfit"),
                    m("div", {class: "text-gray-800 dark:text-gray-200"}, narrative.outfitDescription)
                ]) : "",

                narrative.statisticsDescription ? m("div", {}, [
                    m("div", {class: "text-xs text-gray-500 dark:text-gray-400 mb-1"}, "Abilities"),
                    m("div", {class: "text-gray-800 dark:text-gray-200"}, narrative.statisticsDescription)
                ]) : "",

                !hasNarrative ?
                    m("div", {class: "flex flex-col items-center justify-center py-8"}, [
                        m("span", {class: "material-symbols-outlined text-5xl text-gray-400 dark:text-gray-500 mb-3"}, "auto_stories"),
                        m("div", {class: "text-gray-500 dark:text-gray-400 mb-3"}, "No narrative generated"),
                        m("button", {
                            class: "px-4 py-2 rounded bg-blue-600 hover:bg-blue-500 text-white text-sm flex items-center space-x-1",
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
            m("div", {class: "px-3 py-2 bg-amber-200 dark:bg-gray-700 border-b border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-lg font-bold text-gray-800 dark:text-gray-100"}, "Instincts"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 truncate"}, char.name)
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
                        m("span", {class: "material-symbols-outlined text-sm text-gray-500 dark:text-gray-400"}, inst.icon),
                        m("span", {class: "w-16 text-xs text-gray-700 dark:text-gray-300"}, inst.label),
                        m("div", {class: "flex-1 h-3 bg-gray-300 dark:bg-gray-600 rounded overflow-hidden relative"}, [
                            // Center line
                            m("div", {class: "absolute left-1/2 top-0 bottom-0 w-px bg-gray-400 dark:bg-gray-500"}),
                            // Value bar
                            m("div", {
                                class: "absolute h-full " + getInstinctColor(displayVal),
                                style: "left: " + leftPct + "%; width: " + widthPct + "%"
                            })
                        ]),
                        m("span", {class: "w-8 text-right text-xs text-gray-800 dark:text-gray-200"},
                            val !== undefined ? Math.round(val) : "-")
                    ]);
                })
            )
        ]);
    }

    function renderApparelView(char) {
        let store = char.store;
        let apparel = store && store.apparel ? store.apparel[0] : null;
        let outfitDesc = char.narrative ? char.narrative.outfitDescription : null;

        return m("div", {class: "h-full flex flex-col"}, [
            // Header
            m("div", {class: "px-3 py-2 bg-amber-200 dark:bg-gray-700 border-b border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-lg font-bold text-gray-800 dark:text-gray-100"}, "Apparel"),
                m("div", {class: "text-xs text-gray-600 dark:text-gray-400 truncate"}, char.name)
            ]),

            // Apparel content
            m("div", {class: "flex-1 overflow-y-auto p-2 space-y-3 text-sm"}, [
                // Outfit description from narrative
                outfitDesc ? m("div", {}, [
                    m("div", {class: "text-xs text-gray-500 dark:text-gray-400 mb-1"}, "Current Outfit"),
                    m("div", {class: "text-gray-800 dark:text-gray-200 text-xs leading-relaxed"}, outfitDesc)
                ]) : m("div", {class: "text-gray-500 dark:text-gray-400 text-center py-2"}, "No outfit description"),

                // Apparel info
                apparel ? m("div", {class: "pt-2 border-t border-amber-200 dark:border-gray-700"}, [
                    m("div", {class: "text-xs text-gray-500 dark:text-gray-400 mb-1"}, "Apparel Details"),
                    apparel.description ?
                        m("div", {class: "text-gray-800 dark:text-gray-200 text-xs"}, apparel.description) :
                        m("div", {class: "text-gray-500 dark:text-gray-400 text-xs italic"}, "No description set")
                ]) : ""
            ]),

            // Dress up/down buttons
            m("div", {class: "p-2 bg-amber-100 dark:bg-gray-800 border-t border-amber-200 dark:border-gray-700"}, [
                m("div", {class: "text-xs text-gray-500 dark:text-gray-400 mb-2 text-center"}, "Change Clothing Level"),
                m("div", {class: "flex justify-center space-x-2"}, [
                    m("button", {
                        class: "px-3 py-2 rounded bg-blue-600 hover:bg-blue-500 text-white text-xs flex items-center space-x-1",
                        onclick: function(e) { e.stopPropagation(); dressCharacterUp(); },
                        title: "Add more clothing"
                    }, [
                        m("span", {class: "material-symbols-outlined text-sm"}, "add"),
                        m("span", {}, "Dress Up")
                    ]),
                    m("button", {
                        class: "px-3 py-2 rounded bg-orange-600 hover:bg-orange-500 text-white text-xs flex items-center space-x-1",
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
        e.dataTransfer.setData('text/plain', card.objectId);
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
        dropTarget = target;
    }

    function handleDragLeave(e) {
        dropTarget = null;
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

    function handleDropOnActionTray(e) {
        e.preventDefault();
        if (draggedCard && dragSource === 'actionDeck') {
            // Find and move from action deck to played actions
            let idx = actionDeck.findIndex(a => a.id === draggedCard.id);
            if (idx > -1) {
                let action = actionDeck.splice(idx, 1)[0];
                playedActions.push(action);
                updateActionPreview();
                page.toast("info", "Played: " + action.label);
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

    // Mini card for stack display
    // cardType: 'character', 'item', 'location', 'event' (for future use)
    function renderMiniCard(card, onClick, isHighlight, source, cardType) {
        let portraitUrl = card ? getPortraitUrl(card, "128x128") : null;
        let borderClass = isHighlight ? "border-amber-500 ring-2 ring-amber-400" : "border-gray-400 dark:border-gray-500";
        let isDragging = draggedCard && draggedCard.objectId === card?.objectId;
        let typeIcon = cardType === 'character' ? 'person' : cardType === 'item' ? 'inventory_2' : cardType === 'location' ? 'location_on' : 'event';

        return m("div", {
            class: "rounded-lg overflow-hidden border-2 bg-gradient-to-b from-amber-100 to-amber-200 dark:from-gray-700 dark:to-gray-800 shadow-lg cursor-grab hover:scale-105 transition-transform " + borderClass + (isDragging ? " opacity-50" : ""),
            style: "width: 80px; height: 110px;",
            onclick: onClick,
            draggable: source ? true : false,
            ondragstart: source ? function(e) { handleDragStart(e, card, source); } : null,
            ondragend: source ? handleDragEnd : null
        }, [
            m("div", {class: "h-20 bg-gray-300 dark:bg-gray-700 flex items-center justify-center overflow-hidden relative"}, [
                portraitUrl ?
                    m("img", {src: portraitUrl, class: "w-full h-full object-cover", draggable: false}) :
                    m("span", {class: "material-symbols-outlined text-2xl text-gray-400 dark:text-gray-500"}, "person"),
                // Card type icon overlay
                m("div", {class: "absolute top-1 right-1 p-0.5 rounded bg-black/40 text-white"}, [
                    m("span", {class: "material-symbols-outlined text-xs"}, typeIcon)
                ])
            ]),
            m("div", {class: "h-6 px-1 flex items-center justify-center bg-amber-200 dark:bg-gray-800 border-t border-amber-300 dark:border-gray-600"}, [
                m("div", {class: "text-xs font-bold text-gray-800 dark:text-gray-200 truncate text-center"},
                    card ? card.name.split(" ")[0] : "?")
            ])
        ]);
    }

    // Card back for deck stacks (no portrait, just type icon)
    function renderCardBack(cardType) {
        let typeIcon = cardType === 'character' ? 'person' : cardType === 'item' ? 'inventory_2' : cardType === 'location' ? 'location_on' : 'event';
        let typeName = cardType.charAt(0).toUpperCase() + cardType.slice(1);

        return m("div", {
            class: "rounded-lg overflow-hidden border-2 border-gray-400 dark:border-gray-500 bg-gradient-to-b from-blue-800 to-blue-900 shadow-lg",
            style: "width: 80px; height: 110px;"
        }, [
            m("div", {class: "h-full flex flex-col items-center justify-center"}, [
                m("span", {class: "material-symbols-outlined text-4xl text-blue-300"}, typeIcon),
                m("div", {class: "text-xs font-bold text-blue-300 mt-1"}, typeName)
            ])
        ]);
    }

    // Draw Pile (Deck)
    function DrawPile() {
        return {
            view: function() {
                let topCard = previewCard || (deck.length > 0 ? deck[previewIndex] : null);

                return m("div", {class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900"}, [
                    // Header
                    m("div", {class: "flex items-center justify-between px-2 py-1 bg-blue-600 text-white"}, [
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
                                    class: "absolute rounded-lg bg-gradient-to-b from-blue-700 to-blue-800 border-2 border-blue-600",
                                    style: "width: 80px; height: 110px; top: 4px; left: 4px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-2xl text-blue-400/50"}, "person")
                                    ])
                                ]) : "",
                                deck.length > 1 ? m("div", {
                                    class: "absolute rounded-lg bg-gradient-to-b from-blue-700 to-blue-800 border-2 border-blue-600",
                                    style: "width: 80px; height: 110px; top: 2px; left: 2px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-2xl text-blue-400/50"}, "person")
                                    ])
                                ]) : "",
                                // Top card (preview card) - draggable
                                renderMiniCard(topCard, drawToUserHand, false, 'deck', 'character')
                            ])
                    ]),

                    // Navigation and actions
                    m("div", {class: "px-2 py-1 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700"}, [
                        // Flip through controls
                        m("div", {class: "flex items-center justify-center space-x-1 mb-1"}, [
                            m("button", {
                                class: "p-1 rounded bg-gray-300 dark:bg-gray-700 hover:bg-gray-400 dark:hover:bg-gray-600 text-gray-800 dark:text-gray-200 disabled:opacity-50",
                                onclick: previewPrevCard,
                                disabled: deck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-xs"}, "chevron_left")),
                            m("span", {class: "text-xs text-gray-600 dark:text-gray-400 min-w-12 text-center"},
                                deck.length > 0 ? (previewIndex + 1) + "/" + deck.length : "-"),
                            m("button", {
                                class: "p-1 rounded bg-gray-300 dark:bg-gray-700 hover:bg-gray-400 dark:hover:bg-gray-600 text-gray-800 dark:text-gray-200 disabled:opacity-50",
                                onclick: previewNextCard,
                                disabled: deck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-xs"}, "chevron_right"))
                        ]),
                        // Draw buttons
                        m("div", {class: "flex space-x-1"}, [
                            m("button", {
                                class: "flex-1 px-2 py-1 rounded bg-green-600 hover:bg-green-500 text-white text-xs font-medium flex items-center justify-center space-x-1 disabled:opacity-50",
                                onclick: drawToUserHand,
                                disabled: deck.length === 0 || userHand.length >= USER_HAND_LIMIT,
                                title: "Draw to your hand"
                            }, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "person"),
                                m("span", {}, "You")
                            ]),
                            m("button", {
                                class: "flex-1 px-2 py-1 rounded bg-red-600 hover:bg-red-500 text-white text-xs font-medium flex items-center justify-center space-x-1 disabled:opacity-50",
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
                let isDropTarget = dropTarget === 'userHand' && dragSource === 'deck';

                return m("div", {
                    class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900 transition-colors " + (isDropTarget ? "bg-green-100 dark:bg-green-900/30" : ""),
                    ondragover: function(e) { handleDragOver(e, 'userHand'); },
                    ondragleave: handleDragLeave,
                    ondrop: handleDropOnUserHand
                }, [
                    // Header
                    m("div", {class: "flex items-center justify-between px-2 py-1 bg-green-600 text-white"}, [
                        m("div", {class: "flex items-center space-x-1"}, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "person"),
                            m("span", {class: "text-sm font-medium"}, "Your Hand"),
                            m("span", {class: "text-xs opacity-75"}, "(" + userHand.length + "/" + USER_HAND_LIMIT + ")")
                        ])
                    ]),

                    // Hand cards
                    m("div", {class: "flex-1 flex items-center justify-center p-2"}, [
                        userHand.length === 0 ?
                            m("div", {class: "text-center text-gray-400 text-xs"}, [
                                m("span", {class: "material-symbols-outlined text-3xl block mb-1"}, "person_outline"),
                                isDropTarget ? "Drop here" : "Draw a character"
                            ]) :
                            m("div", {class: "flex space-x-2 items-center justify-center"},
                                userHand.map(function(card) {
                                    let isSelected = selectedCard && selectedCard.objectId === card.objectId;
                                    return renderMiniCard(card, function() { selectCard(card); }, isSelected, 'userHand', 'character');
                                })
                            )
                    ]),

                    // Discard button
                    m("div", {class: "px-2 py-1 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700"}, [
                        m("button", {
                            class: "w-full px-2 py-1 rounded bg-orange-600 hover:bg-orange-500 text-white text-xs font-medium flex items-center justify-center space-x-1 disabled:opacity-50",
                            onclick: discardFromUserHand,
                            disabled: !selectedCard || userHand.findIndex(c => c.objectId === selectedCard.objectId) === -1
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "delete"),
                            m("span", {}, "Discard")
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
                let isDropTarget = dropTarget === 'gameHand' && dragSource === 'deck';

                return m("div", {
                    class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900 transition-colors " + (isDropTarget ? "bg-red-100 dark:bg-red-900/30" : ""),
                    ondragover: function(e) { handleDragOver(e, 'gameHand'); },
                    ondragleave: handleDragLeave,
                    ondrop: handleDropOnGameHand
                }, [
                    // Header
                    m("div", {class: "flex items-center justify-between px-2 py-1 bg-red-600 text-white"}, [
                        m("div", {class: "flex items-center space-x-1"}, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "smart_toy"),
                            m("span", {class: "text-sm font-medium"}, "Game Hand"),
                            m("span", {class: "text-xs opacity-75"}, "(" + gameHand.length + "/" + GAME_HAND_LIMIT + ")")
                        ]),
                        m("button", {
                            class: "p-1 rounded hover:bg-red-500 disabled:opacity-50",
                            onclick: drawToGameHand,
                            disabled: deck.length === 0 || gameHand.length >= GAME_HAND_LIMIT,
                            title: "Draw for game"
                        }, m("span", {class: "material-symbols-outlined text-sm"}, "add"))
                    ]),

                    // Hand cards - horizontal scroll for multiple
                    m("div", {class: "flex-1 overflow-x-auto overflow-y-hidden p-2"}, [
                        gameHand.length === 0 ?
                            m("div", {class: "flex items-center justify-center h-full text-gray-400 text-xs"}, [
                                m("div", {class: "text-center"}, [
                                    m("span", {class: "material-symbols-outlined text-3xl block mb-1"}, "smart_toy"),
                                    isDropTarget ? "Drop here" : "No opponents"
                                ])
                            ]) :
                            m("div", {class: "flex space-x-2 h-full items-center"},
                                gameHand.map(function(card) {
                                    let isSelected = selectedCard && selectedCard.objectId === card.objectId;
                                    return renderMiniCard(card, function() { selectCard(card); }, isSelected, 'gameHand', 'character');
                                })
                            )
                    ]),

                    // Discard button
                    m("div", {class: "px-2 py-1 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700"}, [
                        m("button", {
                            class: "w-full px-2 py-1 rounded bg-orange-600 hover:bg-orange-500 text-white text-xs font-medium flex items-center justify-center space-x-1 disabled:opacity-50",
                            onclick: discardFromGameHand,
                            disabled: !selectedCard || gameHand.findIndex(c => c.objectId === selectedCard.objectId) === -1
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "delete"),
                            m("span", {}, "Discard")
                        ])
                    ])
                ]);
            }
        };
    }

    // Render an action card (mini version for tray)
    function renderActionCard(action, onClick, isHighlight) {
        let isPending = pendingInteraction && pendingInteraction.actionId === action.id;

        return m("div", {
            class: "flex-shrink-0 flex flex-col items-center justify-center p-2 rounded-lg border-2 transition-all cursor-pointer " +
                (isPending ? "bg-purple-200 dark:bg-purple-800 border-purple-500 ring-2 ring-purple-400" :
                 isHighlight ? "bg-purple-100 dark:bg-purple-900/50 border-purple-400" :
                 "bg-gradient-to-b from-purple-50 to-purple-100 dark:from-purple-900 dark:to-purple-950 border-purple-300 dark:border-purple-700 hover:border-purple-400"),
            style: "width: 60px; height: 75px;",
            onclick: onClick,
            title: action.description
        }, [
            m("span", {class: "material-symbols-outlined text-lg " +
                (isPending ? "text-purple-600 dark:text-purple-300" : "text-purple-600 dark:text-purple-400")}, action.icon),
            m("span", {class: "text-xs mt-1 text-center leading-tight " +
                (isPending ? "text-purple-700 dark:text-purple-200 font-medium" : "text-purple-700 dark:text-purple-300")}, action.label)
        ]);
    }

    // Action Deck Pile - goes in left panel with character piles
    function ActionDeckPile() {
        return {
            view: function() {
                let topCard = actionPreview;

                return m("div", {class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900"}, [
                    // Header
                    m("div", {class: "flex items-center justify-between px-2 py-1 bg-purple-600 text-white"}, [
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
                                class: "absolute rounded-lg bg-gradient-to-b from-purple-600 to-purple-800 border-2 border-purple-500",
                                style: "width: 70px; height: 90px; top: 4px; left: 4px;"
                            }) : "",
                            actionDeck.length > 1 ? m("div", {
                                class: "absolute rounded-lg bg-gradient-to-b from-purple-600 to-purple-800 border-2 border-purple-500",
                                style: "width: 70px; height: 90px; top: 2px; left: 2px;"
                            }) : "",
                            // Top card (preview or back)
                            actionDeck.length > 0 ?
                                m("div", {
                                    class: "relative flex flex-col items-center justify-center p-2 rounded-lg border-2 bg-gradient-to-b from-purple-700 to-purple-900 border-purple-500",
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
                                    class: "flex items-center justify-center text-gray-400 dark:text-gray-500 text-xs border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg",
                                    style: "width: 70px; height: 90px;"
                                }, "Empty")
                        ]),

                        // Navigation buttons
                        m("div", {class: "flex items-center space-x-2 mb-2"}, [
                            m("button", {
                                class: "p-1 rounded bg-gray-300 dark:bg-gray-700 hover:bg-gray-400 dark:hover:bg-gray-600 disabled:opacity-50",
                                onclick: prevActionPreview,
                                disabled: actionDeck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-sm text-gray-700 dark:text-gray-300"}, "chevron_left")),
                            m("span", {class: "text-xs text-gray-500 dark:text-gray-400"},
                                actionDeck.length > 0 ? (actionPreviewIndex + 1) + "/" + actionDeck.length : "-"),
                            m("button", {
                                class: "p-1 rounded bg-gray-300 dark:bg-gray-700 hover:bg-gray-400 dark:hover:bg-gray-600 disabled:opacity-50",
                                onclick: nextActionPreview,
                                disabled: actionDeck.length === 0
                            }, m("span", {class: "material-symbols-outlined text-sm text-gray-700 dark:text-gray-300"}, "chevron_right"))
                        ]),

                        // Draw button
                        m("button", {
                            class: "w-full px-3 py-1.5 rounded bg-purple-600 hover:bg-purple-500 text-white text-sm font-medium flex items-center justify-center space-x-1 disabled:opacity-50",
                            onclick: drawActionToTray,
                            disabled: actionDeck.length === 0
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "play_arrow"),
                            m("span", {}, "Play to Tray")
                        ])
                    ]),

                    // Discard info
                    m("div", {class: "px-2 py-1 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700 text-center"}, [
                        m("span", {class: "text-xs text-orange-600 dark:text-orange-400"}, "Discard: " + actionDiscard.length)
                    ])
                ]);
            }
        };
    }

    // Action Tray - shows played actions, items, and apparel between character hands
    function ActionTray() {
        return {
            view: function() {
                let userChar = userHand.length > 0 ? userHand[0] : null;
                let gameChar = gameHand.length > 0 ? gameHand[0] : null;
                let isActionDrop = dropTarget === 'actionTray' && dragSource === 'actionDeck';
                let isItemDrop = dropTarget === 'itemHand' && dragSource === 'itemDeck';
                let isApparelDrop = dropTarget === 'apparelHand' && dragSource === 'apparelDeck';

                return m("div", {class: "flex flex-col h-full bg-gray-200 dark:bg-gray-800 border-y border-gray-300 dark:border-gray-700"}, [
                    // Pending interaction display (at top if active)
                    pendingInteraction ? m("div", {class: "px-2 py-1 bg-purple-100 dark:bg-purple-900/30 border-b border-purple-300 dark:border-purple-700"}, [
                        m("div", {class: "flex items-center justify-between text-sm"}, [
                            m("div", {class: "flex items-center space-x-2"}, [
                                m("span", {class: "text-gray-800 dark:text-gray-200 font-medium"}, userChar ? userChar.name.split(" ")[0] : "?"),
                                m("span", {class: "material-symbols-outlined text-purple-600 dark:text-purple-400"}, pendingInteraction.icon),
                                m("span", {class: "text-purple-600 dark:text-purple-400 font-medium"}, pendingInteraction.label),
                                m("span", {class: "material-symbols-outlined text-gray-500"}, "arrow_forward"),
                                m("span", {class: "text-gray-500 dark:text-gray-400"},
                                    selectedCard && gameHand.find(c => c.objectId === selectedCard.objectId)
                                        ? selectedCard.name.split(" ")[0]
                                        : "Select target...")
                            ]),
                            m("div", {class: "flex space-x-1"}, [
                                m("button", {
                                    class: "px-2 py-1 rounded bg-green-600 hover:bg-green-500 text-white text-xs disabled:opacity-50",
                                    onclick: executeAction,
                                    disabled: !selectedCard || gameHand.findIndex(c => c.objectId === selectedCard?.objectId) === -1
                                }, "Execute"),
                                m("button", {
                                    class: "px-2 py-1 rounded bg-red-500 hover:bg-red-400 text-white text-xs",
                                    onclick: cancelAction
                                }, "Cancel")
                            ])
                        ])
                    ]) : "",

                    // Two rows: User hand and System hand
                    m("div", {class: "flex-1 flex flex-col overflow-hidden"}, [
                        // USER ROW
                        m("div", {class: "flex-1 flex border-b border-gray-400 dark:border-gray-600"}, [
                            // User label
                            m("div", {class: "w-16 flex flex-col items-center justify-center bg-blue-600 text-white text-xs"}, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "person"),
                                m("span", {}, userChar ? userChar.name.split(" ")[0] : "User")
                            ]),
                            // User Actions
                            m("div", {
                                class: "flex-1 flex flex-col border-r border-gray-300 dark:border-gray-700 transition-colors " + (isActionDrop ? "bg-purple-100 dark:bg-purple-900/30" : ""),
                                ondragover: function(e) { handleDragOver(e, 'actionTray'); },
                                ondragleave: handleDragLeave,
                                ondrop: handleDropOnActionTray
                            }, [
                                m("div", {class: "px-1 py-0.5 bg-purple-600 text-white text-xs flex items-center"}, [
                                    m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "bolt"),
                                    m("span", {class: "ml-1"}, playedActions.length)
                                ]),
                                m("div", {class: "flex-1 flex items-center overflow-x-auto px-1 space-x-1"}, [
                                    playedActions.length === 0 ?
                                        m("span", {class: "text-gray-400 text-xs"}, "Actions") :
                                        playedActions.map(function(action) {
                                            let isSelected = selectedAction && selectedAction.id === action.id;
                                            return m("div", {class: "relative"}, [
                                                renderActionCard(action, function() { selectPlayedAction(action); }, isSelected),
                                                m("button", {
                                                    class: "absolute -top-1 -right-1 w-4 h-4 rounded-full bg-orange-500 hover:bg-orange-400 text-white flex items-center justify-center",
                                                    onclick: function(e) { e.stopPropagation(); discardPlayedAction(action); },
                                                    title: "Discard"
                                                }, m("span", {class: "material-symbols-outlined", style: "font-size: 10px"}, "close"))
                                            ]);
                                        })
                                ])
                            ]),
                            // User Items
                            m("div", {
                                class: "flex-1 flex flex-col border-r border-gray-300 dark:border-gray-700 transition-colors " + (isItemDrop ? "bg-emerald-100 dark:bg-emerald-900/30" : ""),
                                ondragover: function(e) { handleDragOver(e, 'itemHand'); },
                                ondragleave: handleDragLeave,
                                ondrop: handleDropOnItemHand
                            }, [
                                m("div", {class: "px-1 py-0.5 bg-emerald-600 text-white text-xs flex items-center"}, [
                                    m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "inventory_2"),
                                    m("span", {class: "ml-1"}, itemHand.length)
                                ]),
                                m("div", {class: "flex-1 flex items-center overflow-x-auto px-1 space-x-1"}, [
                                    itemHand.length === 0 ?
                                        m("span", {class: "text-gray-400 text-xs"}, "Items") :
                                        itemHand.map(function(item) {
                                            return m("div", {class: "relative"}, [
                                                renderItemCard(item),
                                                m("button", {
                                                    class: "absolute -top-1 -right-1 w-4 h-4 rounded-full bg-orange-500 hover:bg-orange-400 text-white flex items-center justify-center",
                                                    onclick: function(e) { e.stopPropagation(); discardItem(item); },
                                                    title: "Discard"
                                                }, m("span", {class: "material-symbols-outlined", style: "font-size: 10px"}, "close"))
                                            ]);
                                        })
                                ])
                            ]),
                            // User Apparel
                            m("div", {
                                class: "flex-1 flex flex-col transition-colors " + (isApparelDrop ? "bg-pink-100 dark:bg-pink-900/30" : ""),
                                ondragover: function(e) { handleDragOver(e, 'apparelHand'); },
                                ondragleave: handleDragLeave,
                                ondrop: handleDropOnApparelHand
                            }, [
                                m("div", {class: "px-1 py-0.5 bg-pink-600 text-white text-xs flex items-center"}, [
                                    m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "checkroom"),
                                    m("span", {class: "ml-1"}, apparelHand.length)
                                ]),
                                m("div", {class: "flex-1 flex items-center overflow-x-auto px-1 space-x-1"}, [
                                    apparelHand.length === 0 ?
                                        m("span", {class: "text-gray-400 text-xs"}, "Apparel") :
                                        apparelHand.map(function(apparel) {
                                            return m("div", {class: "relative"}, [
                                                renderApparelCard(apparel),
                                                m("button", {
                                                    class: "absolute -top-1 -right-1 w-4 h-4 rounded-full bg-orange-500 hover:bg-orange-400 text-white flex items-center justify-center",
                                                    onclick: function(e) { e.stopPropagation(); discardApparel(apparel); },
                                                    title: "Discard"
                                                }, m("span", {class: "material-symbols-outlined", style: "font-size: 10px"}, "close"))
                                            ]);
                                        })
                                ])
                            ])
                        ]),

                        // SYSTEM ROW
                        m("div", {class: "flex-1 flex"}, [
                            // System label
                            m("div", {class: "w-16 flex flex-col items-center justify-center bg-red-600 text-white text-xs"}, [
                                m("span", {class: "material-symbols-outlined text-sm"}, "smart_toy"),
                                m("span", {}, gameChar ? gameChar.name.split(" ")[0] : "System")
                            ]),
                            // System Actions
                            m("div", {class: "flex-1 flex flex-col border-r border-gray-300 dark:border-gray-700"}, [
                                m("div", {class: "px-1 py-0.5 bg-purple-800 text-white text-xs flex items-center"}, [
                                    m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "bolt"),
                                    m("span", {class: "ml-1"}, systemActions.length)
                                ]),
                                m("div", {class: "flex-1 flex items-center overflow-x-auto px-1 space-x-1"}, [
                                    systemActions.length === 0 ?
                                        m("span", {class: "text-gray-400 text-xs"}, "Actions") :
                                        systemActions.map(function(action) {
                                            return renderActionCard(action, null, false);
                                        })
                                ])
                            ]),
                            // System Items
                            m("div", {class: "flex-1 flex flex-col border-r border-gray-300 dark:border-gray-700"}, [
                                m("div", {class: "px-1 py-0.5 bg-emerald-800 text-white text-xs flex items-center"}, [
                                    m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "inventory_2"),
                                    m("span", {class: "ml-1"}, systemItems.length)
                                ]),
                                m("div", {class: "flex-1 flex items-center overflow-x-auto px-1 space-x-1"}, [
                                    systemItems.length === 0 ?
                                        m("span", {class: "text-gray-400 text-xs"}, "Items") :
                                        systemItems.map(function(item) {
                                            return renderItemCard(item);
                                        })
                                ])
                            ]),
                            // System Apparel
                            m("div", {class: "flex-1 flex flex-col"}, [
                                m("div", {class: "px-1 py-0.5 bg-pink-800 text-white text-xs flex items-center"}, [
                                    m("span", {class: "material-symbols-outlined", style: "font-size: 12px"}, "checkroom"),
                                    m("span", {class: "ml-1"}, systemApparel.length)
                                ]),
                                m("div", {class: "flex-1 flex items-center overflow-x-auto px-1 space-x-1"}, [
                                    systemApparel.length === 0 ?
                                        m("span", {class: "text-gray-400 text-xs"}, "Apparel") :
                                        systemApparel.map(function(apparel) {
                                            return renderApparelCard(apparel);
                                        })
                                ])
                            ])
                        ])
                    ])
                ]);
            }
        };
    }

    // Render a mini item card
    function renderItemCard(item) {
        return m("div", {
            class: "flex-shrink-0 flex flex-col items-center justify-center p-1 rounded border bg-gradient-to-b from-emerald-50 to-emerald-100 dark:from-emerald-900 dark:to-emerald-950 border-emerald-300 dark:border-emerald-700",
            style: "width: 50px; height: 60px;",
            title: item.name || "Item"
        }, [
            m("span", {class: "material-symbols-outlined text-lg text-emerald-600 dark:text-emerald-400"}, "inventory_2"),
            m("span", {class: "text-xs text-center text-emerald-700 dark:text-emerald-300 truncate w-full"}, item.name ? item.name.substring(0, 6) : "?")
        ]);
    }

    // Render a mini apparel card
    function renderApparelCard(apparel) {
        return m("div", {
            class: "flex-shrink-0 flex flex-col items-center justify-center p-1 rounded border bg-gradient-to-b from-pink-50 to-pink-100 dark:from-pink-900 dark:to-pink-950 border-pink-300 dark:border-pink-700",
            style: "width: 50px; height: 60px;",
            title: apparel.name || "Apparel"
        }, [
            m("span", {class: "material-symbols-outlined text-lg text-pink-600 dark:text-pink-400"}, "checkroom"),
            m("span", {class: "text-xs text-center text-pink-700 dark:text-pink-300 truncate w-full"}, apparel.name ? apparel.name.substring(0, 6) : "?")
        ]);
    }

    // Discard Pile
    function DiscardPile() {
        return {
            view: function() {
                let topCard = discard.length > 0 ? discard[discard.length - 1] : null;
                let isDropTarget = dropTarget === 'discard' && (dragSource === 'userHand' || dragSource === 'gameHand');

                return m("div", {
                    class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900 transition-colors " + (isDropTarget ? "bg-orange-100 dark:bg-orange-900/30" : ""),
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
                                    class: "absolute rounded-lg bg-gradient-to-b from-orange-700 to-orange-800 border-2 border-orange-600",
                                    style: "width: 80px; height: 110px; top: 4px; left: 4px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-2xl text-orange-400/50"}, "person")
                                    ])
                                ]) : "",
                                discard.length > 1 ? m("div", {
                                    class: "absolute rounded-lg bg-gradient-to-b from-orange-700 to-orange-800 border-2 border-orange-600",
                                    style: "width: 80px; height: 110px; top: 2px; left: 2px;"
                                }, [
                                    m("div", {class: "h-full flex items-center justify-center"}, [
                                        m("span", {class: "material-symbols-outlined text-2xl text-orange-400/50"}, "person")
                                    ])
                                ]) : "",
                                // Top card (not draggable from discard)
                                renderMiniCard(topCard, null, false, null, 'character')
                            ])
                    ]),

                    // Reshuffle button
                    m("div", {class: "px-2 py-1 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700"}, [
                        m("button", {
                            class: "w-full px-2 py-1 rounded bg-orange-600 hover:bg-orange-500 text-white text-xs font-medium flex items-center justify-center space-x-1 disabled:opacity-50",
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
                return m("div", {class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900 overflow-y-auto"}, [
                    // Header
                    m("div", {class: "px-2 py-1 bg-gray-600 text-white text-sm font-medium text-center"}, "Draw Piles"),

                    // Grid of draw piles
                    m("div", {class: "flex-1 grid grid-cols-2 gap-1 p-1"}, [
                        // Character pile
                        renderDrawPile("Characters", "person", "blue", deck, discard, "characterDeck",
                            function() { drawToUserHand(); }, shuffleDeck, reshuffleDiscard),

                        // Action pile
                        renderDrawPile("Actions", "bolt", "purple", actionDeck, actionDiscard, "actionDeck",
                            drawActionToTray, shuffleActionDeck, reshuffleActionDeck),

                        // Item pile
                        renderDrawPile("Items", "inventory_2", "emerald", itemDeck, itemDiscard, "itemDeck",
                            drawItemToHand, shuffleItemDeck, reshuffleItemDeck),

                        // Apparel pile
                        renderDrawPile("Apparel", "checkroom", "pink", apparelDeck, apparelDiscard, "apparelDeck",
                            drawApparelToHand, shuffleApparelDeck, reshuffleApparelDeck)
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

        return m("div", {class: "flex flex-col items-center p-1 rounded bg-gray-200 dark:bg-gray-800"}, [
            // Label
            m("div", {class: "text-xs font-medium text-gray-700 dark:text-gray-300 mb-1"}, label),

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
                    class: "p-0.5 rounded bg-gray-300 dark:bg-gray-700 hover:bg-gray-400 dark:hover:bg-gray-600 disabled:opacity-50",
                    onclick: onShuffle,
                    disabled: deckCount === 0,
                    title: "Shuffle"
                }, m("span", {class: "material-symbols-outlined text-xs text-gray-700 dark:text-gray-300"}, "shuffle")),
                m("button", {
                    class: "p-0.5 rounded bg-orange-500 hover:bg-orange-400 text-white disabled:opacity-50",
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

                return m("div", {class: "flex flex-col space-y-1 bg-gray-200 dark:bg-gray-700 p-1 rounded-lg"},
                    tabs.map(function(tab) {
                        let isActive = cardView === tab.id;
                        return m("button", {
                            class: "flex items-center space-x-2 px-2 py-1.5 rounded text-sm transition-colors " +
                                (isActive ? "bg-white dark:bg-gray-600 text-gray-800 dark:text-white shadow" : "text-gray-600 dark:text-gray-400 hover:text-gray-800 dark:hover:text-white hover:bg-gray-100 dark:hover:bg-gray-600"),
                            onclick: function() { cardView = tab.id; m.redraw(); },
                            title: tab.label
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, tab.icon),
                            m("span", {class: "text-xs"}, tab.label)
                        ]);
                    })
                );
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

                if (!selectedCard) {
                    return m("div", {
                        class: "flex flex-col items-center justify-center h-full text-gray-500 dark:text-gray-400 transition-colors " + (isDropTarget ? "bg-purple-100 dark:bg-purple-900/30" : ""),
                        ondragover: function(e) { handleDragOver(e, 'selected'); },
                        ondragleave: handleDragLeave,
                        ondrop: handleDropOnSelected
                    }, [
                        m("span", {class: "material-symbols-outlined text-6xl mb-2"}, "touch_app"),
                        m("div", {}, isDropTarget ? "Drop to view card" : "Draw or drag a card")
                    ]);
                }

                return m("div", {
                    class: "flex flex-col h-full bg-gray-100 dark:bg-gray-900 transition-colors " + (isDropTarget ? "bg-purple-100 dark:bg-purple-900/30" : ""),
                    ondragover: function(e) { handleDragOver(e, 'selected'); },
                    ondragleave: handleDragLeave,
                    ondrop: handleDropOnSelected
                }, [
                    // Main content area - tabs on left, card on right
                    m("div", {class: "flex-1 flex overflow-hidden"}, [
                        // Vertical tabs on left
                        m("div", {class: "p-1 border-r border-gray-300 dark:border-gray-700 bg-gray-200 dark:bg-gray-800 overflow-y-auto"}, [
                            m(CardViewTabs)
                        ]),

                        // Card display
                        m("div", {class: "flex-1 flex items-center justify-center p-2 bg-gray-100 dark:bg-gray-900 overflow-hidden"}, [
                            m(CharacterCard, {
                                character: selectedCard,
                                selected: true
                            })
                        ])
                    ]),

                    // Action buttons
                    m("div", {class: "p-2 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700 flex justify-center space-x-2"}, [
                        m("button", {
                            class: "px-3 py-1.5 rounded bg-blue-600 hover:bg-blue-500 text-white text-xs flex items-center space-x-1",
                            onclick: function() { openCharacterView(selectedCard); }
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "open_in_new"),
                            m("span", {}, "Full")
                        ]),
                        m("button", {
                            class: "px-3 py-1.5 rounded bg-gray-300 dark:bg-gray-700 hover:bg-gray-400 dark:hover:bg-gray-600 text-gray-800 dark:text-gray-200 text-xs flex items-center space-x-1",
                            onclick: function() { refreshSelectedCard(); }
                        }, [
                            m("span", {class: "material-symbols-outlined text-sm"}, "refresh")
                        ])
                    ])
                ]);
            }
        };
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
        userHand.push(drawn);
        // Adjust preview index if needed
        if (previewIndex >= deck.length && deck.length > 0) {
            previewIndex = 0;
        }
        previewCard = deck.length > 0 ? deck[previewIndex] : null;
        // Select the drawn card
        await selectCard(drawn);
        page.toast("info", "Drew: " + drawn.name);
    }

    function drawToGameHand() {
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
        gameHand.push(drawn);
        // Adjust preview index if needed
        if (previewIndex >= deck.length && deck.length > 0) {
            previewIndex = 0;
        }
        previewCard = deck.length > 0 ? deck[previewIndex] : null;
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
        console.log("selectCard called with:", card);
        selectedCard = await loadFullCharacter(card);
        console.log("selectedCard set to:", selectedCard);
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
        // Use the same approach as formDef.js
        let inst = am7model.prepareInstance(selectedCard);
        await am7model.forms.commands.narrate(undefined, inst);
        // Clear cache and reload the character
        am7client.clearCache("olio.charPerson");
        selectedCard = await loadFullCharacter(selectedCard);
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

    function drawActionToTray() {
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
        playedActions.push(card);
        actionPreviewIndex = Math.min(actionPreviewIndex, actionDeck.length - 1);
        if (actionPreviewIndex < 0) actionPreviewIndex = 0;
        updateActionPreview();
        page.toast("info", "Played " + card.label + " to tray");
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

        // Auto-select first game hand character if only one
        if (gameHand.length === 1) {
            pendingInteraction.interactor = gameHand[0];
            selectCard(gameHand[0]);
        }

        page.toast("info", "Select a target character from the game hand");
        m.redraw();
    }

    function discardPlayedAction(action) {
        let idx = playedActions.findIndex(a => a.id === action.id);
        if (idx !== -1) {
            playedActions.splice(idx, 1);
            actionDiscard.push(action);
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

    function discardItem(item) {
        let idx = itemHand.findIndex(i => i.objectId === item.objectId);
        if (idx !== -1) {
            itemHand.splice(idx, 1);
            itemDiscard.push(item);
            page.toast("info", "Discarded " + (item.name || "item"));
        }
        m.redraw();
    }

    function discardApparel(apparel) {
        let idx = apparelHand.findIndex(a => a.objectId === apparel.objectId);
        if (idx !== -1) {
            apparelHand.splice(idx, 1);
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

        // Determine target from selected card
        let target = selectedCard;
        if (!target || gameHand.findIndex(c => c.objectId === target.objectId) === -1) {
            page.toast("warn", "Select a target from the game hand");
            return;
        }

        pendingInteraction.interactor = target;

        let actor = pendingInteraction.actor;
        let interactor = pendingInteraction.interactor;

        // Handle CHAT action specially - opens dialog instead of creating interaction
        if (pendingInteraction.type === "CHAT") {
            // Discard the used action card from tray
            discardUsedAction();

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
                // Could display results, update character state, etc.
                console.log("Created interaction:", interaction);
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
        // Build the interaction object based on olio.interaction schema
        let actor = pending.actor;
        let interactor = pending.interactor;

        // For now, create a local interaction object to demonstrate the flow
        // In a full implementation, this would save to the server
        let interaction = {
            schema: "olio.interaction",
            name: pending.label + ": " + actor.name + " -> " + interactor.name,
            description: actor.name + " " + pending.label.toLowerCase() + "s " + interactor.name,
            type: pending.type.toLowerCase(),
            state: "pending",
            actorType: "olio.charPerson",
            actor: {objectId: actor.objectId, name: actor.name},
            interactorType: "olio.charPerson",
            interactor: {objectId: interactor.objectId, name: interactor.name},
            actorOutcome: "unknown",
            interactorOutcome: "unknown"
        };

        // Determine outcome based on simple logic (could be expanded with stats comparison)
        let outcomes = determineOutcome(pending.type, actor, interactor);
        interaction.actorOutcome = outcomes.actorOutcome;
        interaction.interactorOutcome = outcomes.interactorOutcome;
        interaction.description += " - " + formatOutcome(outcomes);

        console.log("Interaction result:", interaction);
        page.toast("info", formatOutcome(outcomes));

        return interaction;
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
            let promptCfg = promptConfigs.find(c => c.name === "Chat Prompt");

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
            let chatConfigName = "CardGame: " + actorFirst + " x " + targetFirst;

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
                console.log("Found existing chat config:", chatConfigName);
            } else {
                // Load full template to get all fields
                let fullTemplate = await am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);

                // Create new chatConfig based on template with character assignments
                let newChatCfg = am7model.newInstance("olio.llm.chatConfig");
                newChatCfg.api.groupId(chatDir.id);
                newChatCfg.api.groupPath(chatDir.path);
                newChatCfg.api.name(chatConfigName);

                // Copy template settings
                if (fullTemplate.model) newChatCfg.api.model(fullTemplate.model);
                if (fullTemplate.serverUrl) newChatCfg.api.serverUrl(fullTemplate.serverUrl);
                if (fullTemplate.serviceType) newChatCfg.api.serviceType(fullTemplate.serviceType);
                if (fullTemplate.rating) newChatCfg.api.rating(fullTemplate.rating);
                if (fullTemplate.messageTrim) newChatCfg.api.messageTrim(fullTemplate.messageTrim);
                if (fullTemplate.stream !== undefined) newChatCfg.api.stream(fullTemplate.stream);
                if (fullTemplate.useNLP !== undefined) newChatCfg.api.useNLP(fullTemplate.useNLP);

                // Assign characters: target = system (AI), actor = user (player)
                newChatCfg.entity.systemCharacter = { objectId: target.objectId };
                newChatCfg.entity.userCharacter = { objectId: actor.objectId };

                // Create the new chatConfig
                chatCfg = await page.createObject(newChatCfg.entity);
                if (!chatCfg) {
                    page.toast("error", "Failed to create chat config");
                    chatDialogOpen = false;
                    return;
                }
                console.log("Created new chat config:", chatConfigName);
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
                console.log("Found existing chat request:", requestName);
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
                console.log("Created new chat request:", requestName);
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
        if (!chatSession || !message.trim() || chatPending) {
            return;
        }

        chatPending = true;
        chatMessages.push({ role: "user", content: message });
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

    // Chat Dialog Component
    function ChatDialog() {
        let inputMessage = "";

        return {
            view: function() {
                if (!chatDialogOpen) return "";

                let actorUrl = getPortraitUrl(chatActor, "96x96");
                let targetUrl = getPortraitUrl(chatTarget, "96x96");

                return m("div", {
                    class: "fixed inset-0 z-50 flex items-center justify-center bg-black/50",
                    onclick: function(e) { if (e.target === e.currentTarget) closeChatDialog(); }
                }, [
                    m("div", {
                        class: "bg-white dark:bg-gray-800 rounded-lg shadow-2xl w-full max-w-2xl h-[80vh] flex flex-col",
                        onclick: function(e) { e.stopPropagation(); }
                    }, [
                        // Header with character portraits
                        m("div", {class: "flex items-center justify-between px-4 py-3 bg-purple-600 text-white rounded-t-lg"}, [
                            m("div", {class: "flex items-center space-x-3"}, [
                                actorUrl ?
                                    m("img", {src: actorUrl, class: "w-10 h-10 rounded-full border-2 border-white"}) :
                                    m("span", {class: "material-symbols-outlined text-2xl"}, "person"),
                                m("span", {class: "font-medium"}, chatActor?.name?.split(" ")[0] || "Actor"),
                                m("span", {class: "material-symbols-outlined mx-2"}, "swap_horiz"),
                                targetUrl ?
                                    m("img", {src: targetUrl, class: "w-10 h-10 rounded-full border-2 border-white"}) :
                                    m("span", {class: "material-symbols-outlined text-2xl"}, "person"),
                                m("span", {class: "font-medium"}, chatTarget?.name?.split(" ")[0] || "Target")
                            ]),
                            m("button", {
                                class: "p-1 rounded hover:bg-purple-500",
                                onclick: closeChatDialog
                            }, m("span", {class: "material-symbols-outlined"}, "close"))
                        ]),

                        // Messages area
                        m("div", {class: "flex-1 overflow-y-auto p-4 space-y-3 bg-gray-50 dark:bg-gray-900"}, [
                            chatMessages.length === 0 ?
                                m("div", {class: "text-center text-gray-500 dark:text-gray-400 py-8"}, [
                                    m("span", {class: "material-symbols-outlined text-4xl block mb-2"}, "forum"),
                                    m("div", {}, "Start a conversation...")
                                ]) :
                                chatMessages.map(function(msg, idx) {
                                    let isUser = msg.role === "user";
                                    let isAssistant = msg.role === "assistant";
                                    if (!isUser && !isAssistant) return ""; // Skip system messages

                                    return m("div", {
                                        class: "flex " + (isUser ? "justify-end" : "justify-start"),
                                        key: idx
                                    }, [
                                        m("div", {
                                            class: "max-w-[75%] px-4 py-2 rounded-lg " +
                                                (isUser ?
                                                    "bg-purple-600 text-white rounded-br-none" :
                                                    "bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-gray-200 rounded-bl-none")
                                        }, [
                                            m("div", {class: "text-xs opacity-70 mb-1"},
                                                isUser ? (chatActor?.name?.split(" ")[0] || "You") : (chatTarget?.name?.split(" ")[0] || "AI")),
                                            m("div", {class: "text-sm whitespace-pre-wrap"}, msg.content)
                                        ])
                                    ]);
                                })
                        ]),

                        // Pending indicator
                        chatPending ? m("div", {class: "px-4 py-2 bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700"}, [
                            m("div", {class: "flex items-center space-x-2 text-gray-500 dark:text-gray-400 text-sm"}, [
                                m("div", {class: "animate-pulse"}, "..."),
                                m("span", {}, chatTarget?.name?.split(" ")[0] + " is typing...")
                            ])
                        ]) : "",

                        // Input area
                        m("div", {class: "p-3 bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 rounded-b-lg"}, [
                            m("div", {class: "flex space-x-2"}, [
                                m("input", {
                                    type: "text",
                                    class: "flex-1 px-4 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-800 dark:text-gray-200 focus:outline-none focus:ring-2 focus:ring-purple-500",
                                    placeholder: "Type a message...",
                                    value: inputMessage,
                                    disabled: chatPending,
                                    oninput: function(e) { inputMessage = e.target.value; },
                                    onkeydown: function(e) {
                                        if (e.key === "Enter" && !e.shiftKey && inputMessage.trim()) {
                                            sendChatMessage(inputMessage);
                                            inputMessage = "";
                                        }
                                    }
                                }),
                                m("button", {
                                    class: "px-4 py-2 rounded-lg bg-purple-600 hover:bg-purple-500 text-white disabled:opacity-50",
                                    disabled: chatPending || !inputMessage.trim(),
                                    onclick: function() {
                                        if (inputMessage.trim()) {
                                            sendChatMessage(inputMessage);
                                            inputMessage = "";
                                        }
                                    }
                                }, m("span", {class: "material-symbols-outlined"}, "send"))
                            ])
                        ])
                    ])
                ]);
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
                selectedCard = findChar(state.selectedCardId);
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

    async function newDeal() {
        // New deal for BOTH user and game:
        // Each gets: 1 character, 3 random actions, 3 items, 2 apparel
        page.toast("info", "Dealing hands...");

        // Shuffle all decks first
        deck = shuffleArray(deck);
        actionDeck = shuffleArray(actionDeck);
        itemDeck = shuffleArray(itemDeck);
        apparelDeck = shuffleArray(apparelDeck);

        // Deal 1 character to user hand
        if (deck.length > 0 && userHand.length < USER_HAND_LIMIT) {
            let char = deck.splice(0, 1)[0];
            userHand.push(char);
            await selectCard(char);
        }

        // Deal 1 character to game hand
        if (deck.length > 0 && gameHand.length < GAME_HAND_LIMIT) {
            let char = deck.splice(0, 1)[0];
            gameHand.push(char);
        }

        // Deal 3 random action cards to user
        for (let i = 0; i < 3 && actionDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * actionDeck.length);
            let action = actionDeck.splice(randIdx, 1)[0];
            playedActions.push(action);
        }

        // Deal 3 random action cards to system
        for (let i = 0; i < 3 && actionDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * actionDeck.length);
            let action = actionDeck.splice(randIdx, 1)[0];
            systemActions.push(action);
        }

        // Deal 3 random items to user
        for (let i = 0; i < 3 && itemDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * itemDeck.length);
            let item = itemDeck.splice(randIdx, 1)[0];
            itemHand.push(item);
        }

        // Deal 3 random items to system
        for (let i = 0; i < 3 && itemDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * itemDeck.length);
            let item = itemDeck.splice(randIdx, 1)[0];
            systemItems.push(item);
        }

        // Deal 2 random apparel to user
        for (let i = 0; i < 2 && apparelDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * apparelDeck.length);
            let apparel = apparelDeck.splice(randIdx, 1)[0];
            apparelHand.push(apparel);
        }

        // Deal 2 random apparel to system
        for (let i = 0; i < 2 && apparelDeck.length > 0; i++) {
            let randIdx = Math.floor(Math.random() * apparelDeck.length);
            let apparel = apparelDeck.splice(randIdx, 1)[0];
            systemApparel.push(apparel);
        }

        // Update preview indices
        previewIndex = 0;
        previewCard = deck.length > 0 ? deck[0] : null;
        actionPreviewIndex = 0;
        updateActionPreview();
        itemPreviewIndex = 0;
        apparelPreviewIndex = 0;

        m.redraw();
    }

    function openSaveDialog(mode) {
        saveDialogMode = mode || 'list';
        newSaveName = currentSave ? currentSave.name : '';
        loadSavedGames();
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
                    class: "fixed inset-0 z-50 flex items-center justify-center bg-black/50",
                    onclick: function(e) { if (e.target === e.currentTarget) closeSaveDialog(); }
                }, [
                    m("div", {
                        class: "bg-white dark:bg-gray-800 rounded-lg shadow-2xl w-full max-w-md flex flex-col",
                        onclick: function(e) { e.stopPropagation(); }
                    }, [
                        // Header
                        m("div", {class: "flex items-center justify-between px-4 py-3 bg-blue-600 text-white rounded-t-lg"}, [
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

                        // Content
                        m("div", {class: "p-4 max-h-[60vh] overflow-y-auto"}, [
                            // Mode buttons
                            m("div", {class: "flex space-x-2 mb-4"}, [
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

                            // Save mode - input for save name
                            saveDialogMode === 'save' ? [
                                m("div", {class: "mb-4"}, [
                                    m("label", {class: "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"}, "Save Name"),
                                    m("input", {
                                        type: "text",
                                        class: "w-full px-3 py-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-800 dark:text-gray-200",
                                        placeholder: "Enter save name...",
                                        value: newSaveName,
                                        oninput: function(e) { newSaveName = e.target.value; }
                                    })
                                ]),
                                m("button", {
                                    class: "w-full px-4 py-2 rounded bg-green-600 hover:bg-green-500 text-white font-medium disabled:opacity-50",
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
                                        class: "px-6 py-2 rounded bg-red-600 hover:bg-red-500 text-white font-medium",
                                        onclick: newGame
                                    }, "Start New Game")
                                ])
                            ] : "",

                            // List mode - show saved games
                            saveDialogMode === 'list' ? [
                                savedGames.length === 0 ?
                                    m("div", {class: "text-center py-8 text-gray-500 dark:text-gray-400"}, [
                                        m("span", {class: "material-symbols-outlined text-4xl mb-2"}, "folder_open"),
                                        m("p", {}, "No saved games found")
                                    ]) :
                                    m("div", {class: "space-y-2"}, savedGames.map(function(save) {
                                        let isCurrent = currentSave && currentSave.objectId === save.objectId;
                                        return m("div", {
                                            class: "flex items-center justify-between p-3 rounded border " +
                                                (isCurrent ? "border-blue-500 bg-blue-50 dark:bg-blue-900/30" : "border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-700")
                                        }, [
                                            m("div", {class: "flex-1"}, [
                                                m("div", {class: "font-medium text-gray-800 dark:text-gray-200"}, save.name),
                                                m("div", {class: "text-xs text-gray-500 dark:text-gray-400"},
                                                    save.modifiedDate ? new Date(save.modifiedDate).toLocaleString() : "")
                                            ]),
                                            m("div", {class: "flex space-x-1"}, [
                                                m("button", {
                                                    class: "p-1 rounded bg-green-600 hover:bg-green-500 text-white",
                                                    onclick: function() { loadGame(save); },
                                                    title: "Load"
                                                }, m("span", {class: "material-symbols-outlined text-sm"}, "play_arrow")),
                                                m("button", {
                                                    class: "p-1 rounded bg-red-600 hover:bg-red-500 text-white",
                                                    onclick: function() { deleteGame(save); },
                                                    title: "Delete"
                                                }, m("span", {class: "material-symbols-outlined text-sm"}, "delete"))
                                            ])
                                        ]);
                                    }))
                            ] : ""
                        ]),

                        // Footer with current save info
                        currentSave ? m("div", {class: "px-4 py-2 bg-gray-100 dark:bg-gray-900 border-t border-gray-200 dark:border-gray-700 rounded-b-lg"}, [
                            m("span", {class: "text-xs text-gray-500 dark:text-gray-400"}, "Current: " + currentSave.name)
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
        return m("div", {class: "flex-grow overflow-hidden flex bg-gray-100 dark:bg-gray-900"}, [
            m("div", {class: "flex flex-col lg:flex-row w-full h-full"}, [
                // Left panel - Consolidated Card Decks
                m("div", {class: "w-full lg:w-48 h-1/3 lg:h-full flex-shrink-0 border-b lg:border-b-0 lg:border-r border-gray-300 dark:border-gray-700"}, [
                    m(ConsolidatedDecks)
                ]),

                // Center panel - Both hands with Action Tray between
                m("div", {class: "flex-1 h-1/3 lg:h-full flex flex-col border-b lg:border-b-0 lg:border-r border-gray-300 dark:border-gray-700"}, [
                    // User's hand (top) - takes about 40%
                    m("div", {class: "h-2/5 border-b border-gray-300 dark:border-gray-700"}, [
                        m(UserHandPile)
                    ]),
                    // Action tray (middle) - takes about 20%
                    m("div", {class: "h-1/5 flex-shrink-0"}, [
                        m(ActionTray)
                    ]),
                    // Game's hand (bottom) - takes about 40%
                    m("div", {class: "h-2/5"}, [
                        m(GameHandPile)
                    ])
                ]),

                // Right panel - Selected Card View
                m("div", {class: "w-full lg:w-1/4 h-1/3 lg:h-full bg-gray-100 dark:bg-gray-900"}, [
                    m(SelectedCardView)
                ])
            ])
        ]);
    }

    function getFooter() {
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
            // New Deal button
            m("button", {
                class: "flyout-button",
                onclick: newDeal
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "playing_cards"),
                "Deal"
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
                m("div", {class: "fixed bottom-0 left-0 right-0 bg-gray-200 dark:bg-gray-800 border-t border-gray-300 dark:border-gray-700 px-4 py-2 flex justify-center space-x-2 z-50"},
                    getFooter()
                )
            ];
        }
    };

    page.views.cardGame = cardGame;
}());
