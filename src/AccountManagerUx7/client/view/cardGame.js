// Card Game Interface - Situation-Driven UI
// Spatial awareness, threat radar, and context-based actions

(function(){
    // Configuration
    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

    // Game state
    let player = null;           // Current player character
    let situation = null;        // Full situation data from server
    let selectedEntity = null;   // Currently selected nearby entity
    let selectedEntityType = null; // 'threat', 'person', 'poi'
    let eventLog = [];           // Recent events and actions
    let isLoading = false;
    let fullMode = false;
    let moveMode = false;        // Whether in movement selection mode
    let zoomedView = false;       // Whether showing zoomed-in cell view (meter-level)
    let pendingMove = null;       // Long-running move action that can be aborted
    let moveProgress = 0;         // Progress of current move (0-100)

    // Chat dialog state
    let chatDialogOpen = false;
    let chatMessages = [];
    let chatPending = false;
    let chatSession = null;

    // Action state
    let pendingAction = null;    // Action being set up
    let actionResult = null;     // Result of last action

    // Character selection state
    let availableCharacters = [];
    let characterSelectOpen = false;

    // Save game state
    let currentSave = null;
    let savedGames = [];
    let saveDialogOpen = false;
    let saveDialogMode = 'list';
    let newSaveName = '';

    // Initialization state
    let initializing = true;
    let initMessage = "Loading...";

    // Grid display
    const GRID_SIZE = 10;        // 10x10 grid display to match map

    // Action types with their icons and descriptions
    const ACTION_TYPES = {
        TALK: { label: "Talk", icon: "forum", color: "blue" },
        BEFRIEND: { label: "Befriend", icon: "handshake", color: "green" },
        BARTER: { label: "Trade", icon: "swap_horiz", color: "yellow" },
        COMBAT: { label: "Fight", icon: "swords", color: "red" },
        DEFEND: { label: "Defend", icon: "shield", color: "gray" },
        HELP: { label: "Help", icon: "volunteer_activism", color: "teal" },
        COOPERATE: { label: "Cooperate", icon: "group_work", color: "purple" },
        INVESTIGATE: { label: "Investigate", icon: "search", color: "orange" },
        FLEE: { label: "Flee", icon: "directions_run", color: "yellow" },
        WATCH: { label: "Watch", icon: "visibility", color: "gray" }
    };

    // Configuration: set to true to use PNG tile images instead of emoji
    const USE_TILE_IMAGES = true;

    // Terrain icons (emoji fallback)
    const TERRAIN_ICONS = {
        CAVE: "\u{1F5FF}",        // Stone/cave
        CLEAR: "\u{1F33F}",       // Herb/clear
        DESERT: "\u{1F3DC}",      // Desert
        DUNES: "\u{1F3DC}",       // Desert dunes
        FOREST: "\u{1F332}",      // Tree
        GLACIER: "\u{1F9CA}",     // Ice
        GRASS: "\u{1F33F}",       // Herb/grass
        HILL: "\u{26F0}",         // Mountain
        HILLS: "\u{26F0}",        // Mountains
        JUNGLE: "\u{1F334}",      // Palm
        LAKE: "\u{1F4A7}",        // Water
        MARSH: "\u{1F344}",       // Mushroom
        MEADOW: "\u{1F33F}",      // Herb
        MOUNTAIN: "\u{1F3D4}",    // Mountain
        OASIS: "\u{1F334}",       // Palm/oasis
        OCEAN: "\u{1F30A}",       // Wave
        PLAINS: "\u{1F33E}",      // Wheat
        PLATEAU: "\u{1F3D4}",     // Mountain
        POND: "\u{1F4A7}",        // Water
        RIVER: "\u{1F4A7}",       // Water
        SAVANNA: "\u{1F33E}",     // Wheat/grass
        SHELTER: "\u{1F3E0}",     // House
        SHORELINE: "\u{1F3D6}",   // Beach
        STREAM: "\u{1F4A7}",      // Water
        SWAMP: "\u{1F344}",       // Mushroom
        TUNDRA: "\u{2744}",       // Snowflake
        VALLEY: "\u{1F3DE}",      // Valley
        WATER: "\u{1F4A7}",       // Water (generic)
        UNKNOWN: "?"
    };

    // Get tile URL for terrain type (served as static files from /dist/tiles/)
    function getTileUrl(terrainType) {
        if (!terrainType) return null;
        let terrain = terrainType.toLowerCase();
        if (terrain === "unknown") terrain = "clear";
        return "/dist/tiles/" + terrain + ".png";
    }

    // ==========================================
    // Utility Functions
    // ==========================================

    function getPortraitUrl(char, size) {
        if (!char || !char.profile || !char.profile.portrait) return null;
        let pp = char.profile.portrait;
        if (!pp.groupPath || !pp.name) return null;
        return g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) +
               "/data.data" + pp.groupPath + "/" + pp.name + "/" + (size || "256x256");
    }

    function formatPercent(val) {
        if (val === undefined || val === null) return 0;
        return Math.round(val * 100);
    }

    function getNeedColor(val) {
        if (val === undefined || val === null) return "gray";
        if (val >= 0.8) return "green";
        if (val >= 0.6) return "blue";
        if (val >= 0.4) return "yellow";
        if (val >= 0.2) return "orange";
        return "red";
    }

    function getPriorityColor(priority) {
        if (priority >= 0.7) return "red";
        if (priority >= 0.4) return "yellow";
        return "green";
    }

    function addEvent(text, type) {
        eventLog.unshift({
            text: text,
            type: type || 'info',
            time: new Date()
        });
        if (eventLog.length > 50) eventLog.pop();
    }

    // Helper to get character ID (handles both objectId and id fields)
    function getCharId(char) {
        return char ? (char.objectId || char.id) : null;
    }

    // Build 2D grid from flat list of adjacent cells
    // Server returns adjacentCells with eastings/northings, we need to position them relative to player
    function buildGridFromCells(adjacentCells, nearbyPeople, threats, size) {
        // Initialize empty grid
        let grid = [];
        for (let y = 0; y < size; y++) {
            let row = [];
            for (let x = 0; x < size; x++) {
                row.push({ terrainType: "UNKNOWN", occupants: [], pois: [] });
            }
            grid.push(row);
        }

        // Get player's current cell coordinates
        // Use situation.location (simplified map with eastings/northings) or fallback to player.state.currentLocation
        let playerLoc = (situation && situation.location) ||
                        (player && player.state && player.state.currentLocation);
        let playerEast = playerLoc ? (playerLoc.eastings || 0) : 0;
        let playerNorth = playerLoc ? (playerLoc.northings || 0) : 0;
        let center = Math.floor(size / 2);

        // Place adjacent cells in grid relative to player position
        for (let cell of adjacentCells) {
            if (!cell) continue;
            let cellEast = cell.eastings || 0;
            let cellNorth = cell.northings || 0;

            // Calculate grid position (player is at center)
            let gridX = center + (cellEast - playerEast);
            let gridY = center + (cellNorth - playerNorth);

            // Check bounds
            if (gridX >= 0 && gridX < size && gridY >= 0 && gridY < size) {
                grid[gridY][gridX] = {
                    terrainType: cell.terrainType || "UNKNOWN",
                    occupants: [],
                    pois: cell.features || []
                };
            }
        }

        // Place nearby people in grid based on their cell's grid coordinates
        for (let person of nearbyPeople) {
            if (!person) continue;
            // Support both simplified structure (currentLocation) and full (state.currentLocation)
            let personLoc = person.currentLocation || (person.state && person.state.currentLocation);
            if (!personLoc) continue;

            let personEast = personLoc.eastings || 0;
            let personNorth = personLoc.northings || 0;

            let gridX = center + (personEast - playerEast);
            let gridY = center + (personNorth - playerNorth);

            if (gridX >= 0 && gridX < size && gridY >= 0 && gridY < size) {
                grid[gridY][gridX].occupants.push({
                    type: 'person',
                    name: person.name,
                    objectId: person.objectId || person.id,
                    record: person
                });
            }
        }

        // Place threats in grid (threats have source IDs, need to find positions)
        for (let threat of threats) {
            if (!threat || !threat.source) continue;
            // Threats are already processed - they have source info but may not have position
            // For now, add them to center if we don't have position info
            // TODO: Look up threat source position from adjacentCells or nearbyPeople
            if (threat.isAnimal) {
                // Animals are threats - add to a random adjacent cell for now
                // This is a simplification - real impl should track animal positions
            }
        }

        return grid;
    }

    // ==========================================
    // API Functions
    // ==========================================

    async function loadSituation() {
        let playerId = getCharId(player);
        if (!playerId) return;
        isLoading = true;
        try {
            let resp = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/game/situation/" + playerId,
                withCredentials: true
            });
            situation = resp;

            // Sync all nested objects using generic sync helper
            if (player && resp) {
                am7client.syncFromResponse(player, resp, am7client.SYNC_MAPPINGS.situation);
            }

            m.redraw();
        } catch (e) {
            console.error("Failed to load situation", e);
            page.toast("error", "Failed to load situation: " + e.message);
        }
        isLoading = false;
    }

    async function resolveAction(targetId, actionType) {
        let playerId = getCharId(player);
        if (!playerId) return null;
        isLoading = true;
        try {
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/resolve/" + playerId,
                body: { targetId: targetId, actionType: actionType },
                withCredentials: true
            });

            actionResult = resp;

            // Log the result
            if (resp.interrupted) {
                addEvent("Action interrupted by " + (resp.interruptSource || "threat") + "!", "danger");
            } else if (resp.result) {
                let outcome = resp.result.outcome || resp.result.type || "completed";
                addEvent(actionType + " with " + (resp.targetName || "target") + ": " + outcome,
                    outcome === "POSITIVE" || outcome === "SUCCESS" ? "success" : "info");
            }

            // Refresh situation
            await loadSituation();
            return resp;
        } catch (e) {
            console.error("Failed to resolve action", e);
            page.toast("error", "Action failed: " + e.message);
            addEvent("Action failed: " + e.message, "danger");
        }
        isLoading = false;
        return null;
    }

    async function moveCharacter(direction) {
        let playerId = getCharId(player);
        if (!playerId) return;
        isLoading = true;
        console.log("Moving character:", playerId, "direction:", direction);
        try {
            // Move 1 meter per click for fine-grained control within the 100m x 100m cell
            // currentEast/currentNorth track position 0-99 meters within the cell
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/move/" + playerId,
                headers: { "Content-Type": "application/json" },
                body: { direction: direction, distance: 1 },
                withCredentials: true
            });

            console.log("Move response:", resp);
            situation = resp;

            // Sync all nested objects using generic sync helper
            if (player && resp) {
                am7client.syncFromResponse(player, resp, am7client.SYNC_MAPPINGS.situation);
            }

            addEvent("Moved " + direction, "info");
            moveMode = false;
            m.redraw();
        } catch (e) {
            console.error("Failed to move", e, "Response:", e.response);
            // Mithril puts the response body in e.response for non-2xx responses
            let errorMsg = "Movement failed";
            if (e.response && e.response.error) {
                errorMsg = e.response.error;
            } else if (e.message) {
                errorMsg = e.message;
            }
            page.toast("error", errorMsg);
            addEvent(errorMsg, "warning");
        }
        isLoading = false;
    }

    async function loadAvailableCharacters() {
        try {
            // Find the population directory
            let popDir = await page.findObject("auth.group", "data", gridPath + "/Population");
            if (!popDir) {
                console.warn("Population directory not found at " + gridPath + "/Population");
                page.toast("warn", "Population directory not found - check gridPath");
                return;
            }

            // Query for characters - viewQuery includes "profile" from model's query array
            let q = am7view.viewQuery("olio.charPerson");
            q.field("groupId", popDir.id);
            q.range(0, 50);
            // Add other foreign fields we need for display
            q.entity.request.push("store", "statistics");

            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length) {
                am7model.updateListModel(qr.results);
                availableCharacters = qr.results;
                console.log("Loaded " + availableCharacters.length + " characters with full data");
            } else {
                console.warn("No characters found in Population directory");
            }
        } catch (e) {
            console.error("Failed to load characters", e);
            page.toast("error", "Failed to load characters: " + e.message);
        }
    }

    async function claimCharacter(char) {
        if (!char) return;
        let charId = getCharId(char);
        if (!charId) {
            page.toast("error", "Character has no ID");
            return;
        }
        characterSelectOpen = false;

        // Load full character data with all nested models (state, store, statistics, etc.)
        let fullChar = await am7client.getFull("olio.charPerson", charId);
        if (!fullChar) {
            page.toast("error", "Failed to load character data");
            return;
        }
        player = fullChar;
        window.dbgChar = player;

        // Mark as player controlled via state update
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/claim/" + charId,
                withCredentials: true
            });
        } catch (e) {
            // Endpoint may not exist yet, continue anyway
            console.log("Claim endpoint not available, continuing...");
        }

        await loadSituation();
        addEvent("Now playing as " + player.name, "success");
        page.toast("success", "Now playing as " + char.name);
    }

    async function advanceTurn() {
        if (!player) return;
        isLoading = true;
        try {
            // Advance turn updates needs and advances time for player-controlled characters
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/advance",
                withCredentials: true
            });
            // Reload situation to get updated state
            await loadSituation();
            if (resp && resp.updated !== undefined) {
                addEvent("Turn advanced (" + resp.updated + " updated)", "info");
            } else {
                addEvent("Turn advanced", "info");
            }
        } catch (e) {
            console.error("Failed to advance turn", e);
            page.toast("error", "Failed to advance turn");
        }
        isLoading = false;
    }

    // ==========================================
    // Chat Functions
    // ==========================================

    async function startChat(target) {
        if (!player || !target) return;
        chatMessages = [];
        chatDialogOpen = true;
        chatSession = {
            actor: player,
            target: target
        };
        addEvent("Started conversation with " + target.name, "info");
        m.redraw();
    }

    async function sendChatMessage(text) {
        if (!chatSession || !text.trim()) return;
        chatPending = true;
        chatMessages.push({ role: 'user', content: text, time: new Date() });
        m.redraw();

        try {
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/chat",
                body: {
                    actorId: chatSession.actor.objectId,
                    targetId: chatSession.target.objectId,
                    message: text
                },
                withCredentials: true
            });

            if (resp && resp.response) {
                chatMessages.push({ role: 'assistant', content: resp.response, time: new Date() });
            }
        } catch (e) {
            console.error("Chat failed", e);
            chatMessages.push({ role: 'system', content: "Communication failed", time: new Date() });
        }

        chatPending = false;
        m.redraw();
    }

    async function endChat() {
        if (!chatSession) return;

        // Create interaction event from chat
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/endChat",
                body: {
                    actorId: chatSession.actor.objectId,
                    targetId: chatSession.target.objectId,
                    messageCount: chatMessages.length
                },
                withCredentials: true
            });
            addEvent("Conversation ended with " + chatSession.target.name, "info");
        } catch (e) {
            console.log("End chat endpoint not available");
        }

        chatDialogOpen = false;
        chatSession = null;
        chatMessages = [];
        await loadSituation();
    }

    // ==========================================
    // Save/Load Functions
    // ==========================================

    async function loadSavedGames() {
        try {
            let resp = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/game/saves",
                withCredentials: true
            });
            savedGames = resp || [];
        } catch (e) {
            savedGames = [];
        }
    }

    async function saveGame(name) {
        if (!player) return;
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/save",
                body: {
                    name: name,
                    characterId: getCharId(player),
                    eventLog: eventLog.slice(0, 20)
                },
                withCredentials: true
            });
            currentSave = { name: name };
            page.toast("success", "Game saved");
        } catch (e) {
            page.toast("error", "Save failed");
        }
    }

    async function loadGame(save) {
        try {
            let resp = await m.request({
                method: 'GET',
                url: g_application_path + "/rest/game/load/" + save.objectId,
                withCredentials: true
            });
            if (resp && resp.saveData && resp.saveData.characterId) {
                // Load full character data with all nested models
                let fullChar = await am7client.getFull("olio.charPerson", resp.saveData.characterId);
                if (fullChar) {
                    player = fullChar;
                } else {
                    page.toast("error", "Failed to load character");
                    return;
                }
                eventLog = resp.saveData.eventLog || [];
                await loadSituation();
                currentSave = save;
                page.toast("success", "Game loaded");
            }
        } catch (e) {
            page.toast("error", "Load failed");
        }
        saveDialogOpen = false;
    }

    // ==========================================
    // UI Components
    // ==========================================

    // Character Status Panel (Left)
    let CharacterPanel = {
        view: function() {
            if (!player) {
                return m("div", {class: "sg-panel sg-character-panel"}, [
                    m("div", {class: "sg-panel-header"}, [
                        m("span", {class: "material-symbols-outlined"}, "person"),
                        " SELECT CHARACTER"
                    ]),
                    m("div", {class: "sg-empty-state"}, [
                        m("p", "No character selected"),
                        m("button", {
                            class: "sg-btn sg-btn-primary",
                            onclick: function() {
                                characterSelectOpen = true;
                                loadAvailableCharacters();
                            }
                        }, [
                            m("span", {class: "material-symbols-outlined"}, "person_add"),
                            " Choose Character"
                        ])
                    ])
                ]);
            }

            let needs = situation && situation.needs ? situation.needs : {};
            let portrait = getPortraitUrl(player, "256x256");
    
            return m("div", {class: "sg-panel sg-character-panel"}, [
                // Header
                m("div", {class: "sg-panel-header"}, [
                    m("span", {class: "material-symbols-outlined"}, "person"),
                    " YOUR CHARACTER"
                ]),

                // Portrait with overlay buttons
                m("div", {class: "sg-portrait-frame", style: "position: relative;"}, [
                    portrait ?
                        m("img", {src: portrait, class: "sg-portrait-img"}) :
                        m("div", {class: "sg-portrait-placeholder"}, [
                            m("span", {class: "material-symbols-outlined"}, "person")
                        ]),
                    // Overlay buttons on portrait
                    m("div", {style: "position: absolute; bottom: 4px; right: 4px; display: flex; flex-direction: column; gap: 4px;"}, [
                        // View/Edit button
                        m("button", {
                            class: "sg-btn sg-btn-small sg-btn-icon",
                            style: "opacity: 0.8;",
                            onclick: function(e) {
                                e.stopPropagation();
                                window.open("#/view/olio.charPerson/" + getCharId(player), "_blank");
                            },
                            title: "View/Edit Character"
                        }, [
                            m("span", {class: "material-symbols-outlined"}, "open_in_new")
                        ]),
                        // Reimage button
                        m("button", {
                            class: "sg-btn sg-btn-small sg-btn-icon",
                            style: "opacity: 0.8;",
                            onclick: async function(e) {
                                e.stopPropagation();
                                page.toast("info", "Generating new portrait...");
                                try {
                                    await m.request({
                                        method: 'GET',
                                        url: g_application_path + "/rest/olio/charPerson/" + getCharId(player) + "/reimage/false",
                                        withCredentials: true
                                    });
                                    page.toast("success", "Portrait regenerated!");
                                    await loadSituation();
                                    m.redraw();
                                } catch (e) {
                                    page.toast("error", "Failed to reimage: " + (e.message || "Unknown error"));
                                }
                            },
                            title: "Reimage Portrait"
                        }, [
                            m("span", {class: "material-symbols-outlined"}, "auto_awesome")
                        ])
                    ])
                ]),

                // Name and info
                m("div", {class: "sg-character-info"}, [
                    m("div", {class: "sg-character-name"}, player.name || "Unknown"),
                    m("div", {class: "sg-character-details"},
                        (player.age || "?") + " years \u2022 " + (player.gender || "?")
                    )
                ]),

                // Needs bars
                m("div", {class: "sg-needs-section"}, [
                    m("div", {class: "sg-section-label"}, "NEEDS"),
                    renderNeedBar("Health", needs.health, "favorite", "red"),
                    renderNeedBar("Energy", needs.energy, "bolt", "yellow"),
                    renderNeedBar("Hunger", 1 - (needs.hunger || 0), "restaurant", "orange"),
                    renderNeedBar("Thirst", 1 - (needs.thirst || 0), "water_drop", "blue"),
                    renderNeedBar("Fatigue", 1 - (needs.fatigue || 0), "bedtime", "purple")
                ]),

                // Location info - use situation.location (simplified map with all fields)
                (function() {
                    let playerLoc = situation && situation.location ? situation.location : null;
                    let playerState = player.state || {};
                    let terrainType = playerLoc ? playerLoc.terrainType : null;
                    // Normalize terrain type to uppercase for icon lookup
                    let terrainKey = terrainType ? terrainType.toUpperCase() : null;
                    // Display name is capitalized (e.g., "Desert")
                    let terrainDisplay = terrainType ? terrainType.charAt(0).toUpperCase() + terrainType.slice(1).toLowerCase() : null;
                    let climate = situation ? situation.climate : null;

                    // Grid coordinates (cell position in grid)
                    let cellEast = playerLoc ? playerLoc.eastings : null;
                    let cellNorth = playerLoc ? playerLoc.northings : null;
                    // Position within cell (meters from cell edge)
                    let posEast = playerState.currentEast;
                    let posNorth = playerState.currentNorth;

                    return m("div", {class: "sg-location-section"}, [
                        m("div", {class: "sg-section-label"}, "LOCATION"),
                        m("div", {class: "sg-location-info"}, [
                            m("span", {class: "sg-terrain-icon"},
                                TERRAIN_ICONS[terrainKey] || TERRAIN_ICONS.UNKNOWN),
                            m("span", terrainDisplay || "Unknown terrain")
                        ]),
                        // Show MGRS coordinate in smaller text
                        playerLoc && playerLoc.name ?
                            m("div", {class: "sg-location-coord", style: "font-size: 0.7em; opacity: 0.7;"},
                                playerLoc.name) : null,
                        // Show full coordinates: cell grid position + position within cell
                        (cellEast !== null || posEast !== null) ?
                            m("div", {class: "sg-location-coord", style: "font-size: 0.7em; opacity: 0.7; margin-top: 2px;"}, [
                                "Cell: [" + (cellEast !== null ? cellEast : "?") + ", " + (cellNorth !== null ? cellNorth : "?") + "]",
                                " Pos: [" + (posEast !== null && posEast >= 0 ? posEast : "?") + ", " + (posNorth !== null && posNorth >= 0 ? posNorth : "?") + "]m"
                            ]) : null,
                        climate ?
                            m("div", {class: "sg-climate-info"},
                                climate + " climate") : null
                    ]);
                })(),

            ]);
        }
    };

    function renderNeedBar(label, value, icon, color) {
        let pct = formatPercent(value);
        let barColor = getNeedColor(value);
        return m("div", {class: "sg-need-row"}, [
            m("span", {class: "material-symbols-outlined sg-need-icon sg-color-" + color}, icon),
            m("span", {class: "sg-need-label"}, label),
            m("div", {class: "sg-need-bar"}, [
                m("div", {
                    class: "sg-need-fill sg-bg-" + barColor,
                    style: "width: " + pct + "%"
                })
            ]),
            m("span", {class: "sg-need-value"}, pct + "%")
        ]);
    }

    // Situation Panel (Center)
    let SituationPanel = {
        view: function() {
            if (!player) {
                return m("div", {class: "sg-panel sg-situation-panel"}, [
                    m("div", {class: "sg-empty-state"}, "Select a character to begin")
                ]);
            }

            let narrative = situation && situation.narrative ? situation.narrative :
                "The world awaits your actions...";

            return m("div", {class: "sg-panel sg-situation-panel"}, [
                // Narrative text
                m("div", {class: "sg-narrative"}, [
                    m("div", {class: "sg-narrative-text"}, "\"" + narrative + "\"")
                ]),

                // Spatial grid with view toggle
                m("div", {class: "sg-grid-section"}, [
                    m("div", {class: "sg-grid-header"}, [
                        m("div", {class: "sg-section-label"}, zoomedView ? "CURRENT CELL (100m)" : "NEARBY AREA"),
                        m("button", {
                            class: "sg-btn sg-btn-small sg-btn-toggle",
                            onclick: function() { zoomedView = !zoomedView; },
                            title: zoomedView ? "Show area map" : "Zoom into current cell"
                        }, [
                            m("span", {class: "material-symbols-outlined"},
                                zoomedView ? "zoom_out" : "zoom_in"),
                            zoomedView ? " Area" : " Cell"
                        ])
                    ]),
                    zoomedView ? renderZoomedCellView() : renderGrid(),
                    zoomedView ? renderZoomedLegend() : renderGridLegend()
                ]),

                // Movement controls (only show in area view, zoomed view uses click-to-move)
                !zoomedView ? m("div", {class: "sg-movement-controls"}, [
                    renderMovementPad()
                ]) : null
            ]);
        }
    };

    function renderGrid() {
        // Server returns adjacentCells as flat list, convert to 2D grid
        // adjacentCells excludes the player's current cell, so add it from situation.location
        let adjacentCells = situation && situation.adjacentCells ? situation.adjacentCells.slice() : [];
        let playerCell = situation && situation.location ? situation.location : null;
        if (playerCell) {
            adjacentCells.push(playerCell);  // Add player's cell so it shows in grid
        }
        let nearbyPeople = situation && situation.nearbyPeople ? situation.nearbyPeople : [];
        let threats = situation && situation.threats ? situation.threats : [];
        let size = GRID_SIZE;
        let center = Math.floor(size / 2);

        // Build grid from adjacentCells - cells have eastings/northings
        let grid = buildGridFromCells(adjacentCells, nearbyPeople, threats, size);


        return m("div", {class: "sg-grid"}, grid.map(function(row, y) {
            return m("div", {class: "sg-grid-row"}, row.map(function(cell, x) {
                let isCenter = x === center && y === center;
                let hasThreat = cell.occupants && cell.occupants.some(function(o) {
                    return o.threatType || o.type === 'animal';
                });
                let hasPerson = cell.occupants && cell.occupants.some(function(o) {
                    return o.type === 'person' || o.name;
                });
                let hasPOI = cell.pois && cell.pois.length > 0;

                let cellClass = "sg-grid-cell";
                if (isCenter) cellClass += " sg-cell-current";
                if (hasThreat) cellClass += " sg-cell-threat";
                if (hasPerson && !hasThreat) cellClass += " sg-cell-person";

                // Determine overlay icon (player, threat, person, POI)
                let overlayIcon = null;
                if (isCenter) {
                    overlayIcon = "\u2B50"; // Star for player
                } else if (hasThreat && cell.occupants) {
                    let threat = cell.occupants.find(function(o) { return o.threatType; });
                    overlayIcon = threat && threat.icon ? threat.icon : "\u{1F43A}"; // Wolf default
                } else if (hasPerson && cell.occupants) {
                    overlayIcon = "\u{1F464}"; // Person
                } else if (hasPOI) {
                    overlayIcon = "\u{1F3E0}"; // House/POI
                }

                // Build cell content
                let cellContent = [];
                let emojiIcon = TERRAIN_ICONS[cell.terrainType] || TERRAIN_ICONS.UNKNOWN;

                // Background: tile image with emoji fallback
                if (USE_TILE_IMAGES && cell.terrainType) {
                    // Include both image and emoji - emoji is hidden by default, shown on img error
                    cellContent.push(m("img", {
                        src: getTileUrl(cell.terrainType),
                        class: "sg-tile-img",
                        alt: cell.terrainType,
                        onerror: function(e) {
                            // Hide broken image and show emoji fallback
                            e.target.style.display = 'none';
                            let fallback = e.target.nextElementSibling;
                            if (fallback && fallback.classList.contains('sg-tile-fallback')) {
                                fallback.style.display = 'flex';
                            }
                        }
                    }));
                    // Hidden emoji fallback
                    cellContent.push(m("span", {
                        class: "sg-tile-fallback",
                        style: "display: none; position: absolute; top: 0; left: 0; width: 100%; height: 100%; align-items: center; justify-content: center; font-size: 1.5rem;"
                    }, emojiIcon));
                } else {
                    cellContent.push(emojiIcon);
                }

                // Overlay icon on top of tile
                if (overlayIcon) {
                    cellContent.push(m("span", {class: "sg-cell-overlay"}, overlayIcon));
                }

                // Calculate cell coordinates for movement
                let playerLoc = (situation && situation.location) || {};
                let playerEastCell = playerLoc.eastings || 0;
                let playerNorthCell = playerLoc.northings || 0;
                let cellEast = playerEastCell + (x - center);
                let cellNorth = playerNorthCell + (y - center);

                // Check if this is an adjacent cell (within 1 cell of player)
                let isAdjacent = !isCenter && Math.abs(x - center) <= 1 && Math.abs(y - center) <= 1;

                return m("div", {
                    class: cellClass + (isAdjacent ? " sg-cell-clickable" : ""),
                    title: isAdjacent && !isCenter ? "Move to cell [" + cellEast + ", " + cellNorth + "]" : "",
                    onclick: function() {
                        if (isCenter) {
                            // Click on player's cell - switch to zoomed view
                            zoomedView = true;
                        } else if (cell.occupants && cell.occupants.length) {
                            // Select entity for interaction
                            selectEntity(cell.occupants[0], hasThreat ? 'threat' : 'person');
                        } else if (hasPOI) {
                            selectEntity(cell.pois[0], 'poi');
                        } else if (isAdjacent) {
                            // Move to adjacent empty cell
                            moveToAdjacentCell(cellEast, cellNorth);
                        }
                    }
                }, cellContent);
            }));
        }));
    }

    function renderGridLegend() {
        return m("div", {class: "sg-grid-legend"}, [
            m("span", "\u2B50 You"),
            m("span", {class: "sg-color-red"}, "\u{1F43A} Threat"),
            m("span", {class: "sg-color-yellow"}, "\u{1F464} Person"),
            m("span", {class: "sg-color-blue"}, "\u{1F4A7} Water"),
            m("span", {class: "sg-color-orange"}, "\u{1F525} Camp"),
            m("span", {class: "sg-color-gray", style: "font-size: 0.8em; opacity: 0.7;"}, "Click cell to move")
        ]);
    }

    function renderZoomedLegend() {
        return m("div", {class: "sg-grid-legend"}, [
            m("span", "\u2B50 You"),
            m("span", {class: "sg-color-yellow"}, "\u{1F464} Person"),
            m("span", {class: "sg-color-blue"}, "\u{1F3E0} POI"),
            m("span", {class: "sg-color-gray", style: "font-size: 0.8em; opacity: 0.7;"}, "Click to move (10m blocks)")
        ]);
    }

    // Zoomed-in cell view showing meter-level position within current 100m x 100m cell
    // Includes narrow border strips showing adjacent cell terrain
    function renderZoomedCellView() {
        let playerState = player && player.state ? player.state : {};
        let playerLoc = situation && situation.location ? situation.location : null;
        let posEast = playerState.currentEast || 0;
        let posNorth = playerState.currentNorth || 0;

        // Get entities in the same cell
        let nearbyPeople = situation && situation.nearbyPeople ? situation.nearbyPeople : [];
        let pois = playerLoc && playerLoc.features ? playerLoc.features : [];

        // Filter to only entities in the same cell as the player
        let playerCellEast = playerLoc ? playerLoc.eastings : null;
        let playerCellNorth = playerLoc ? playerLoc.northings : null;

        // Build map of adjacent cells by relative position
        let adjacentCells = situation && situation.adjacentCells ? situation.adjacentCells : [];
        let adjacentMap = {};  // key: "dx,dy" -> cell
        for (let cell of adjacentCells) {
            if (!cell) continue;
            let dx = (cell.eastings || 0) - (playerCellEast || 0);
            let dy = (cell.northings || 0) - (playerCellNorth || 0);
            adjacentMap[dx + "," + dy] = cell;
        }

        // Helper to get adjacent cell terrain
        function getAdjacentTerrain(dx, dy) {
            let cell = adjacentMap[dx + "," + dy];
            return cell ? cell.terrainType : null;
        }

        let sameCellPeople = nearbyPeople.filter(function(p) {
            let loc = p.currentLocation || (p.state && p.state.currentLocation);
            if (!loc) return false;
            return loc.eastings === playerCellEast && loc.northings === playerCellNorth;
        });

        // Create 10x10 grid representing the 100m cell (each sub-cell is 10m)
        const ZOOM_SIZE = 10;
        let grid = [];
        for (let y = 0; y < ZOOM_SIZE; y++) {
            let row = [];
            for (let x = 0; x < ZOOM_SIZE; x++) {
                row.push({
                    x: x * 10,  // Meters 0-90 for start of each 10m block
                    y: y * 10,
                    occupants: [],
                    pois: []
                });
            }
            grid.push(row);
        }

        // Place player in grid (convert 0-99 meters to 0-9 grid position)
        let playerGridX = Math.floor(posEast / 10);
        let playerGridY = Math.floor(posNorth / 10);
        if (playerGridX >= ZOOM_SIZE) playerGridX = ZOOM_SIZE - 1;
        if (playerGridY >= ZOOM_SIZE) playerGridY = ZOOM_SIZE - 1;

        // Place same-cell people in grid
        for (let person of sameCellPeople) {
            let pState = person.state || {};
            let pEast = pState.currentEast || 0;
            let pNorth = pState.currentNorth || 0;
            let gx = Math.min(Math.floor(pEast / 10), ZOOM_SIZE - 1);
            let gy = Math.min(Math.floor(pNorth / 10), ZOOM_SIZE - 1);
            grid[gy][gx].occupants.push({
                type: 'person',
                name: person.name,
                objectId: person.objectId || person.id,
                record: person,
                posEast: pEast,
                posNorth: pNorth
            });
        }

        // Place POIs in grid (POIs may have position within cell)
        for (let poi of pois) {
            let poiEast = poi.posEast || Math.floor(Math.random() * 100);
            let poiNorth = poi.posNorth || Math.floor(Math.random() * 100);
            let gx = Math.min(Math.floor(poiEast / 10), ZOOM_SIZE - 1);
            let gy = Math.min(Math.floor(poiNorth / 10), ZOOM_SIZE - 1);
            grid[gy][gx].pois.push(poi);
        }

        // Get terrain for background
        let terrainType = playerLoc ? playerLoc.terrainType : "UNKNOWN";

        // Helper to render an adjacent cell edge strip
        function renderEdgeStrip(dx, dy, position) {
            let adjTerrain = getAdjacentTerrain(dx, dy);
            let adjCell = adjacentMap[dx + "," + dy];
            let targetCellEast = (playerCellEast || 0) + dx;
            let targetCellNorth = (playerCellNorth || 0) + dy;

            let stripClass = "sg-edge-strip sg-edge-" + position;
            let style = "";
            if (USE_TILE_IMAGES && adjTerrain) {
                style = "background-image: url('" + getTileUrl(adjTerrain) + "'); background-size: cover;";
            }

            let title = adjTerrain ?
                (adjTerrain.charAt(0).toUpperCase() + adjTerrain.slice(1).toLowerCase()) + " [" + targetCellEast + "," + targetCellNorth + "]" :
                "Edge of map";

            return m("div", {
                class: stripClass + (adjCell ? " sg-edge-clickable" : " sg-edge-void"),
                style: style,
                title: adjCell ? "Move to " + title : title,
                onclick: function() {
                    if (adjCell) {
                        moveToAdjacentCell(targetCellEast, targetCellNorth);
                    }
                }
            }, !adjTerrain ? m("span", {class: "sg-edge-void-icon"}, "\u2205") : null);
        }

        // Helper to render corner
        function renderCorner(dx, dy, position) {
            let adjTerrain = getAdjacentTerrain(dx, dy);
            let adjCell = adjacentMap[dx + "," + dy];
            let targetCellEast = (playerCellEast || 0) + dx;
            let targetCellNorth = (playerCellNorth || 0) + dy;

            let cornerClass = "sg-edge-corner sg-corner-" + position;
            let style = "";
            if (USE_TILE_IMAGES && adjTerrain) {
                style = "background-image: url('" + getTileUrl(adjTerrain) + "'); background-size: cover;";
            }

            return m("div", {
                class: cornerClass + (adjCell ? " sg-edge-clickable" : " sg-edge-void"),
                style: style,
                title: adjCell ? "Move diagonal" : "",
                onclick: function() {
                    if (adjCell) {
                        moveToAdjacentCell(targetCellEast, targetCellNorth);
                    }
                }
            });
        }

        return m("div", {class: "sg-zoomed-grid"}, [
            // Grid with adjacent cell borders
            m("div", {class: "sg-zoomed-wrapper"}, [
                // Top row: NW corner, North edge, NE corner
                m("div", {class: "sg-edge-row sg-edge-row-top"}, [
                    renderCorner(-1, 1, "nw"),
                    renderEdgeStrip(0, 1, "north"),
                    renderCorner(1, 1, "ne")
                ]),

                // Middle row: West edge, Main grid, East edge
                m("div", {class: "sg-edge-row sg-edge-row-middle"}, [
                    renderEdgeStrip(-1, 0, "west"),

                    // Main 10x10 grid - render north (high y) at top by reversing row order
                    m("div", {
                        class: "sg-zoomed-container",
                        style: USE_TILE_IMAGES && terrainType ?
                            "background-image: url('" + getTileUrl(terrainType) + "'); background-size: cover;" :
                            ""
                    }, grid.slice().reverse().map(function(row, visualY) {
                        // visualY=0 is top of screen (north), visualY=9 is bottom (south)
                        // Convert back to grid coordinates for position matching
                        let gridY = ZOOM_SIZE - 1 - visualY;
                        return m("div", {class: "sg-zoomed-row"}, row.map(function(cell, x) {
                            let isPlayerCell = x === playerGridX && gridY === playerGridY;
                            let hasPerson = cell.occupants.length > 0;
                            let hasPOI = cell.pois.length > 0;
                            let hasThreat = cell.occupants.some(function(o) { return o.type === 'threat'; });

                            let cellClass = "sg-zoomed-cell";
                            if (isPlayerCell) cellClass += " sg-cell-player";
                            if (hasThreat) cellClass += " sg-cell-threat";
                            if (hasPerson && !isPlayerCell) cellClass += " sg-cell-person";
                            if (hasPOI) cellClass += " sg-cell-poi";

                            // Determine what to show in cell
                            let content = [];
                            if (isPlayerCell) {
                                content.push(m("span", {class: "sg-zoom-icon sg-zoom-player"}, "\u2B50"));
                            }
                            if (hasPerson && !isPlayerCell) {
                                content.push(m("span", {class: "sg-zoom-icon sg-zoom-person"}, "\u{1F464}"));
                            }
                            if (hasPOI) {
                                content.push(m("span", {class: "sg-zoom-icon sg-zoom-poi"}, "\u{1F3E0}"));
                            }
                            if (hasThreat) {
                                content.push(m("span", {class: "sg-zoom-icon sg-zoom-threat"}, "\u{1F43A}"));
                            }

                            // Click handler - move to this position within cell
                            // Use gridY (not visualY) for actual map coordinates
                            let targetEast = x * 10 + 5;  // Center of the 10m block
                            let targetNorth = gridY * 10 + 5;

                            return m("div", {
                                class: cellClass,
                                title: "Move to [" + targetEast + ", " + targetNorth + "]m",
                                onclick: function() {
                                    if (!isPlayerCell) {
                                        moveToPosition(targetEast, targetNorth);
                                    }
                                }
                            }, content);
                        }));
                    })),

                    renderEdgeStrip(1, 0, "east")
                ]),

                // Bottom row: SW corner, South edge, SE corner
                m("div", {class: "sg-edge-row sg-edge-row-bottom"}, [
                    renderCorner(-1, -1, "sw"),
                    renderEdgeStrip(0, -1, "south"),
                    renderCorner(1, -1, "se")
                ])
            ]),

            // Position indicator
            m("div", {class: "sg-zoom-position"}, [
                "Position: [" + posEast + ", " + posNorth + "]m within cell [" +
                (playerCellEast !== null ? playerCellEast : "?") + ", " +
                (playerCellNorth !== null ? playerCellNorth : "?") + "]"
            ]),

            // Pending move indicator
            pendingMove ? m("div", {class: "sg-move-progress"}, [
                m("div", {class: "sg-progress-bar"}, [
                    m("div", {
                        class: "sg-progress-fill",
                        style: "width: " + moveProgress + "%"
                    })
                ]),
                m("button", {
                    class: "sg-btn sg-btn-small sg-btn-danger",
                    onclick: abortMove
                }, [
                    m("span", {class: "material-symbols-outlined"}, "stop"),
                    " Abort"
                ])
            ]) : null
        ]);
    }

    // Move to a specific position within the current cell using the moveTo endpoint
    // This uses proper angle-based pathfinding (diagonal movement along the slope)
    async function moveToPosition(targetEast, targetNorth) {
        if (!player || isLoading || pendingMove) return;

        let playerLoc = situation && situation.location ? situation.location : null;
        if (!playerLoc) return;

        // Target is in the same cell
        let targetCellEast = playerLoc.eastings || 0;
        let targetCellNorth = playerLoc.northings || 0;

        await executeMoveTo(targetCellEast, targetCellNorth, targetEast, targetNorth, "Moving...");
    }

    // Move to an adjacent cell using the moveTo endpoint
    async function moveToAdjacentCell(cellEast, cellNorth) {
        if (!player || isLoading || pendingMove) return;

        let playerLoc = situation && situation.location ? situation.location : null;
        if (!playerLoc) return;

        let currentCellEast = playerLoc.eastings || 0;
        let currentCellNorth = playerLoc.northings || 0;

        // Only allow movement to adjacent cells (delta of -1, 0, or 1)
        let deltaEast = cellEast - currentCellEast;
        let deltaNorth = cellNorth - currentCellNorth;
        if (Math.abs(deltaEast) > 1 || Math.abs(deltaNorth) > 1) {
            page.toast("warning", "Can only move to adjacent cells");
            return;
        }

        // Target the center of the destination cell
        await executeMoveTo(cellEast, cellNorth, 50, 50, "Moving to adjacent cell...");
    }

    // Execute movement towards a target using the moveTo endpoint
    // This uses proper angle-based direction calculation on the backend
    async function executeMoveTo(targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, message) {
        if (pendingMove) return;

        pendingMove = {
            targetCellEast: targetCellEast,
            targetCellNorth: targetCellNorth,
            targetPosEast: targetPosEast,
            targetPosNorth: targetPosNorth,
            aborted: false
        };
        moveProgress = 0;
        addEvent(message || "Moving...", "info");
        m.redraw();

        // Calculate initial distance for progress tracking
        let playerState = player.state || {};
        let playerLoc = situation && situation.location ? situation.location : {};
        let currentX = (playerLoc.eastings || 0) * 100 + (playerState.currentEast || 0);
        let currentY = (playerLoc.northings || 0) * 100 + (playerState.currentNorth || 0);
        let targetX = targetCellEast * 100 + targetPosEast;
        let targetY = targetCellNorth * 100 + targetPosNorth;
        let totalDistance = Math.sqrt(Math.pow(targetX - currentX, 2) + Math.pow(targetY - currentY, 2));

        if (totalDistance <= 0) {
            pendingMove = null;
            return;
        }

        // Move in chunks until at destination or aborted
        let chunkSize = 10;  // Move up to 10m at a time

        while (!pendingMove.aborted) {
            try {
                isLoading = true;
                let resp = await m.request({
                    method: 'POST',
                    url: g_application_path + "/rest/game/moveTo/" + getCharId(player),
                    headers: { "Content-Type": "application/json" },
                    body: {
                        targetCellEast: targetCellEast,
                        targetCellNorth: targetCellNorth,
                        targetPosEast: targetPosEast,
                        targetPosNorth: targetPosNorth,
                        distance: chunkSize
                    },
                    withCredentials: true
                });

                situation = resp;
                if (player && resp) {
                    am7client.syncFromResponse(player, resp, am7client.SYNC_MAPPINGS.situation);
                }

                // Update progress based on remaining distance
                let remaining = resp.remainingDistance || 0;
                moveProgress = Math.round(((totalDistance - remaining) / totalDistance) * 100);
                m.redraw();

                // Check if arrived
                if (resp.arrived || remaining <= 1) {
                    break;
                }

            } catch (e) {
                console.error("MoveTo failed", e);
                // Better error extraction - check response object, then message, then status
                let errorMsg = "Unknown error";
                if (e.response && e.response.error) {
                    errorMsg = e.response.error;
                } else if (e.message) {
                    errorMsg = e.message;
                } else if (e.code) {
                    errorMsg = "HTTP " + e.code;
                }
                addEvent("Movement blocked: " + errorMsg, "warning");
                pendingMove.aborted = true;
                break;
            } finally {
                isLoading = false;
            }

            // Small delay for UI responsiveness
            await new Promise(function(resolve) { setTimeout(resolve, 50); });
        }

        // Clean up
        let wasAborted = pendingMove.aborted;
        pendingMove = null;
        moveProgress = 0;

        if (!wasAborted) {
            addEvent("Arrived at destination", "success");
        } else {
            addEvent("Movement aborted", "warning");
        }

        m.redraw();
    }

    // Abort a pending move
    function abortMove() {
        if (pendingMove) {
            pendingMove.aborted = true;
        }
    }

    function renderMovementPad() {
        return m("div", {class: "sg-move-pad"}, [
            m("div", {class: "sg-move-row"}, [
                m("div"), // spacer
                m("button", {class: "sg-move-btn", onclick: function() { moveCharacter("NORTH"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_upward")
                ]),
                m("div") // spacer
            ]),
            m("div", {class: "sg-move-row"}, [
                m("button", {class: "sg-move-btn", onclick: function() { moveCharacter("WEST"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_back")
                ]),
                m("button", {class: "sg-move-btn sg-move-center"}, [
                    m("span", {class: "material-symbols-outlined"}, "radio_button_checked")
                ]),
                m("button", {class: "sg-move-btn", onclick: function() { moveCharacter("EAST"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_forward")
                ])
            ]),
            m("div", {class: "sg-move-row"}, [
                m("div"), // spacer
                m("button", {class: "sg-move-btn", onclick: function() { moveCharacter("SOUTH"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_downward")
                ]),
                m("div") // spacer
            ])
        ]);
    }

    function selectEntity(entity, type) {
        selectedEntity = entity;
        selectedEntityType = type;
        m.redraw();
    }

    // Threat/Action Panel (Right)
    let ThreatActionPanel = {
        view: function() {
            if (!player) {
                return m("div", {class: "sg-panel sg-action-panel"}, [
                    m("div", {class: "sg-empty-state"}, "No threats or actions available")
                ]);
            }

            let threats = situation && situation.threats ? situation.threats : [];
            let people = situation && situation.nearbyPeople ? situation.nearbyPeople : [];
            let clock = situation && situation.clock ? situation.clock : null;
            let interactions = situation && situation.interactions ? situation.interactions : [];

            return m("div", {class: "sg-panel sg-action-panel"}, [
                // Game clock
                clock ? m("div", {class: "sg-clock-section"}, [
                    m("div", {class: "sg-panel-header sg-header-clock"}, [
                        m("span", {class: "material-symbols-outlined"}, "schedule"),
                        " GAME TIME"
                    ]),
                    m("div", {class: "sg-clock-display"}, [
                        m("div", {class: "sg-clock-time"}, clock.currentTime || "--:--"),
                        m("div", {class: "sg-clock-date"}, clock.currentDate || "----"),
                        clock.epoch ? m("div", {class: "sg-clock-epoch"}, "Year " + (clock.epoch.name || "?")) : null,
                        m("div", {class: "sg-clock-remaining"}, [
                            clock.remainingDays !== undefined ? clock.remainingDays + " days remaining" : ""
                        ])
                    ])
                ]) : null,

                // Threat radar
                m("div", {class: "sg-threat-section"}, [
                    m("div", {class: "sg-panel-header sg-header-danger"}, [
                        m("span", {class: "material-symbols-outlined"}, "warning"),
                        " THREAT RADAR"
                    ]),
                    threats.length ? threats.map(renderThreatCard) :
                        m("div", {class: "sg-no-threats"}, "No immediate threats")
                ]),

                // Nearby people
                people.length ? m("div", {class: "sg-people-section"}, [
                    m("div", {class: "sg-panel-header sg-header-info"}, [
                        m("span", {class: "material-symbols-outlined"}, "groups"),
                        " NEARBY"
                    ]),
                    people.slice(0, 3).map(renderPersonCard)
                ]) : null,

                // Player interactions
                interactions.length ? m("div", {class: "sg-interactions-section"}, [
                    m("div", {class: "sg-panel-header sg-header-social"}, [
                        m("span", {class: "material-symbols-outlined"}, "handshake"),
                        " INTERACTIONS"
                    ]),
                    m("div", {class: "sg-interactions-list"}, interactions.slice(0, 5).map(function(inter) {
                        return m("div", {class: "sg-interaction-item"}, [
                            m("span", {class: "sg-interaction-type"}, inter.type || "?"),
                            m("span", {class: "sg-interaction-with"}, " with "),
                            m("span", {class: "sg-interaction-name"}, inter.interactorName || "someone"),
                            inter.state ? m("span", {class: "sg-interaction-state sg-state-" + inter.state.toLowerCase()}, " [" + inter.state + "]") : null
                        ]);
                    }))
                ]) : null,

                // Available actions
                m("div", {class: "sg-actions-section"}, [
                    m("div", {class: "sg-panel-header sg-header-primary"}, [
                        m("span", {class: "material-symbols-outlined"}, "bolt"),
                        " ACTIONS"
                    ]),
                    renderAvailableActions()
                ]),

                // Event log
                m("div", {class: "sg-event-log"}, [
                    m("div", {class: "sg-section-label"}, "RECENT EVENTS"),
                    m("div", {class: "sg-events"}, eventLog.slice(0, 5).map(function(evt) {
                        return m("div", {class: "sg-event sg-event-" + evt.type}, evt.text);
                    }))
                ])
            ]);
        }
    };

    function renderThreatCard(threat) {
        let priority = threat.priority || 0.5;
        let pColor = getPriorityColor(priority);
        // Determine icon and name based on whether it's an animal
        let icon = threat.icon || "\u26A0"; // Default warning
        let name = threat.sourceName || threat.name || threat.type || "Threat";
        let typeLabel = threat.threatType || threat.type || "THREAT";

        if (threat.isAnimal) {
            icon = "\u{1F43E}"; // Paw print for animals
            if (threat.animalType) {
                name = threat.animalType;
                if (threat.sourceName && threat.sourceName !== threat.animalType) {
                    name = threat.animalType + " (" + threat.sourceName + ")";
                }
            }
            typeLabel = threat.groupType || "ANIMAL";
        }

        // Check if this threat is selected
        let threatId = threat.source || threat.objectId || threat.id;
        let selectedId = selectedEntity ? (selectedEntity.source || selectedEntity.objectId || selectedEntity.id) : null;
        let isSelected = selectedEntityType === 'threat' && threatId && selectedId && threatId === selectedId;

        return m("div", {
            class: "sg-threat-card sg-border-" + pColor + (isSelected ? " sg-card-selected" : ""),
            onclick: function() { selectEntity(threat, 'threat'); }
        }, [
            m("div", {class: "sg-threat-header"}, [
                m("span", {class: "sg-threat-icon"}, icon),
                m("div", {class: "sg-threat-info"}, [
                    m("div", {class: "sg-threat-name"}, name),
                    m("div", {class: "sg-threat-type"}, typeLabel)
                ]),
                m("div", {class: "sg-threat-priority sg-color-" + pColor},
                    priority.toFixed(2))
            ]),
            m("div", {class: "sg-priority-bar"}, [
                m("div", {
                    class: "sg-priority-fill sg-bg-" + pColor,
                    style: "width: " + (priority * 100) + "%"
                })
            ])
        ]);
    }

    function renderPersonCard(person) {
        let record = person.record || person;
        let portrait = getPortraitUrl(record, "64x64");

        // Build display name from name, or fallback to gender/age description
        let displayName = record.name;
        if (!displayName) {
            let gender = record.gender || "";
            let age = record.age || 0;
            if (gender && age) {
                let isMale = gender.toLowerCase() === "male";
                if (age < 13) {
                    displayName = isMale ? "Boy" : "Girl";
                } else if (age < 20) {
                    displayName = isMale ? "Young man" : "Young woman";
                } else if (age < 60) {
                    displayName = isMale ? "Man" : "Woman";
                } else {
                    displayName = isMale ? "Old man" : "Old woman";
                }
            } else {
                displayName = "Unknown person";
            }
        }

        // Check if this person is selected
        let personId = record.objectId || record.id;
        let selectedId = selectedEntity ? (selectedEntity.objectId || selectedEntity.id) : null;
        let isSelected = selectedEntityType === 'person' && personId && selectedId && personId === selectedId;

        return m("div", {
            class: "sg-person-card" + (isSelected ? " sg-card-selected" : ""),
            onclick: function() { selectEntity(record, 'person'); }
        }, [
            portrait ?
                m("img", {src: portrait, class: "sg-person-portrait"}) :
                m("span", {class: "sg-person-icon"}, "\u{1F464}"),
            m("div", {class: "sg-person-info"}, [
                m("div", {class: "sg-person-name"}, displayName),
                m("div", {class: "sg-person-rel"},
                    (person.relationship || "neutral") + " \u2022 " + (person.distance || 0) + " cells")
            ])
        ]);
    }

    function renderAvailableActions() {
        // Context-based actions
        let actions = [];

        // If threat selected, show combat actions
        if (selectedEntityType === 'threat') {
            actions.push({type: "COMBAT", primary: true});
            actions.push({type: "FLEE"});
            actions.push({type: "WATCH"});
        }
        // If person selected, show social actions
        else if (selectedEntityType === 'person') {
            actions.push({type: "TALK", primary: true});
            actions.push({type: "BARTER"});
            actions.push({type: "HELP"});
            actions.push({type: "COMBAT"});
        }
        // Default actions when nothing selected
        else {
            actions.push({type: "INVESTIGATE"});
            actions.push({type: "WATCH"});
        }

        // Action buttons
        let actionButtons = m("div", {class: "sg-action-buttons"}, actions.map(function(act) {
            let def = ACTION_TYPES[act.type] || {label: act.type, icon: "help", color: "gray"};
            return m("button", {
                class: "sg-btn sg-btn-action" + (act.primary ? " sg-btn-primary" : ""),
                onclick: function() { executeAction(act.type); }
            }, [
                m("span", {class: "material-symbols-outlined"}, def.icon),
                " " + def.label
            ]);
        }));

        // Utility buttons for selected entity (View/Reimage)
        let utilityButtons = null;
        if (selectedEntity && selectedEntityType) {
            let entityId = selectedEntity.objectId || selectedEntity.id || selectedEntity.source;
            let isAnimal = selectedEntityType === 'threat' && (selectedEntity.isAnimal || selectedEntity.animalType);
            let isPerson = selectedEntityType === 'person';

            // Always show cancel button when something is selected
            let cancelBtn = m("button", {
                class: "sg-btn sg-btn-small",
                onclick: function() {
                    selectedEntity = null;
                    selectedEntityType = null;
                },
                title: "Cancel selection"
            }, [
                m("span", {class: "material-symbols-outlined"}, "close"),
                " Cancel"
            ]);

            if (entityId && (isPerson || isAnimal)) {
                utilityButtons = m("div", {class: "sg-utility-buttons", style: "margin-top: 8px; display: flex; gap: 8px;"}, [
                    cancelBtn,
                    // View/Edit button - opens in new tab
                    m("button", {
                        class: "sg-btn sg-btn-small sg-btn-icon",
                        onclick: function() {
                            let modelType = isPerson ? "olio.charPerson" : "olio.animal";
                            window.open("#/view/" + modelType + "/" + entityId, "_blank");
                        },
                        title: "View/Edit (new tab)"
                    }, [
                        m("span", {class: "material-symbols-outlined"}, "open_in_new")
                    ]),
                    // Reimage button
                    m("button", {
                        class: "sg-btn sg-btn-small sg-btn-icon",
                        onclick: async function() {
                            if (isPerson) {
                                page.toast("info", "Generating new portrait...");
                                try {
                                    await m.request({
                                        method: 'GET',
                                        url: g_application_path + "/rest/olio/charPerson/" + entityId + "/reimage/false",
                                        withCredentials: true
                                    });
                                    page.toast("success", "Portrait regenerated!");
                                    await loadSituation();
                                    m.redraw();
                                } catch (e) {
                                    page.toast("error", "Failed to reimage: " + (e.message || "Unknown error"));
                                }
                            } else if (isAnimal) {
                                page.toast("info", "Generating animal image...");
                                try {
                                    await m.request({
                                        method: 'POST',
                                        url: g_application_path + "/rest/olio/animal/" + entityId + "/reimage",
                                        body: {hires: false},
                                        withCredentials: true
                                    });
                                    page.toast("success", "Animal image generated!");
                                    await loadSituation();
                                    m.redraw();
                                } catch (e) {
                                    page.toast("error", "Failed to reimage: " + (e.message || "Unknown error"));
                                }
                            }
                        },
                        title: "Reimage"
                    }, [
                        m("span", {class: "material-symbols-outlined"}, "auto_awesome")
                    ])
                ]);
            } else {
                // Show just cancel button if no valid entityId
                utilityButtons = m("div", {class: "sg-utility-buttons", style: "margin-top: 8px; display: flex; gap: 8px;"}, [
                    cancelBtn
                ]);
            }
        }

        return m("div", [actionButtons, utilityButtons]);
    }

    async function executeAction(actionType) {
        if (!player || !selectedEntity) {
            // Investigate and Watch don't need a target
            if (actionType === "INVESTIGATE") {
                try {
                    let resp = await m.request({
                        method: 'POST',
                        url: g_application_path + "/rest/game/investigate/" + getCharId(player),
                        withCredentials: true
                    });
                    if (resp && resp.discoveries) {
                        resp.discoveries.forEach(function(d) {
                            addEvent(d, "info");
                        });
                    }
                    await loadSituation();
                } catch (e) {
                    console.error("Failed to investigate", e);
                    page.toast("error", "Investigation failed: " + e.message);
                }
                return;
            }
            if (actionType === "WATCH") {
                addEvent("You keep watch over the area...", "info");
                await loadSituation();
                return;
            }
            page.toast("warning", "Select a target first");
            return;
        }

        let targetId = selectedEntity.objectId || selectedEntity.id || selectedEntity.source;
        if (!targetId) {
            page.toast("error", "Invalid target");
            return;
        }

        // Special handling for TALK - opens chat dialog
        if (actionType === "TALK") {
            await startChat(selectedEntity);
            return;
        }

        // Resolve the action
        await resolveAction(targetId, actionType);
        selectedEntity = null;
        selectedEntityType = null;
    }

    // Character Selection Dialog
    let CharacterSelectDialog = {
        view: function() {
            if (!characterSelectOpen) return null;

            return m("div", {class: "sg-dialog-overlay"}, [
                m("div", {class: "sg-dialog"}, [
                    m("div", {class: "sg-dialog-header"}, [
                        m("span", {class: "material-symbols-outlined"}, "person"),
                        " Select Character",
                        m("button", {
                            class: "sg-dialog-close",
                            onclick: function() { characterSelectOpen = false; }
                        }, "\u00D7")
                    ]),
                    m("div", {class: "sg-dialog-content"}, [
                        m("div", {class: "sg-character-grid"}, availableCharacters.map(function(char) {
                            let portrait = getPortraitUrl(char, "128x128");
                            return m("div", {
                                class: "sg-character-option",
                                onclick: function() { claimCharacter(char); }
                            }, [
                                m("div", {class: "sg-option-portrait"}, [
                                    portrait ?
                                        m("img", {src: portrait}) :
                                        m("span", {class: "material-symbols-outlined"}, "person")
                                ]),
                                m("div", {class: "sg-option-name"}, char.name),
                                m("div", {class: "sg-option-info"},
                                    (char.age || "?") + " \u2022 " + (char.gender || "?"))
                            ]);
                        }))
                    ])
                ])
            ]);
        }
    };

    // Chat Dialog
    let ChatDialog = {
        view: function() {
            if (!chatDialogOpen || !chatSession) return null;

            return m("div", {class: "sg-dialog-overlay"}, [
                m("div", {class: "sg-dialog sg-dialog-chat"}, [
                    m("div", {class: "sg-dialog-header"}, [
                        m("span", {class: "material-symbols-outlined"}, "forum"),
                        " Talking to " + (chatSession.target.name || "Unknown"),
                        m("button", {
                            class: "sg-dialog-close",
                            onclick: endChat
                        }, "\u00D7")
                    ]),
                    m("div", {class: "sg-chat-messages"}, chatMessages.map(function(msg) {
                        return m("div", {class: "sg-chat-msg sg-chat-" + msg.role}, [
                            m("div", {class: "sg-chat-content"}, msg.content)
                        ]);
                    })),
                    m("div", {class: "sg-chat-input"}, [
                        m("input", {
                            type: "text",
                            placeholder: "Type a message...",
                            disabled: chatPending,
                            onkeydown: function(e) {
                                if (e.key === "Enter" && e.target.value.trim()) {
                                    sendChatMessage(e.target.value);
                                    e.target.value = "";
                                }
                            }
                        }),
                        m("button", {
                            class: "sg-btn sg-btn-primary",
                            disabled: chatPending,
                            onclick: function(e) {
                                let input = e.target.parentElement.querySelector("input");
                                if (input.value.trim()) {
                                    sendChatMessage(input.value);
                                    input.value = "";
                                }
                            }
                        }, chatPending ? "..." : "Send")
                    ]),
                    m("div", {class: "sg-chat-actions"}, [
                        m("button", {class: "sg-btn", onclick: endChat}, "End Conversation")
                    ])
                ])
            ]);
        }
    };

    // Initialization Loading Overlay
    let InitLoadingOverlay = {
        view: function() {
            if (!initializing) return null;

            return m("div", {class: "sg-init-overlay"}, [
                m("div", {class: "sg-init-dialog"}, [
                    m("div", {class: "sg-init-spinner"}),
                    m("div", {class: "sg-init-message"}, initMessage)
                ])
            ]);
        }
    };

    // Save/Load Dialog
    let SaveGameDialog = {
        view: function() {
            if (!saveDialogOpen) return null;

            return m("div", {class: "sg-dialog-overlay"}, [
                m("div", {class: "sg-dialog"}, [
                    m("div", {class: "sg-dialog-header"}, [
                        m("span", {class: "material-symbols-outlined"}, "folder_open"),
                        " Saved Games",
                        m("button", {
                            class: "sg-dialog-close",
                            onclick: function() { saveDialogOpen = false; }
                        }, "\u00D7")
                    ]),
                    m("div", {class: "sg-dialog-content"}, [
                        // Save list
                        savedGames.length ?
                            m("div", {class: "sg-save-list"}, savedGames.map(function(save) {
                                return m("div", {
                                    class: "sg-save-item",
                                    onclick: function() { loadGame(save); }
                                }, [
                                    m("span", {class: "material-symbols-outlined"}, "save"),
                                    m("span", save.name),
                                    m("span", {class: "sg-save-date"}, save.date || "")
                                ]);
                            })) :
                            m("div", {class: "sg-no-saves"}, "No saved games"),

                        // New save
                        player ? m("div", {class: "sg-new-save"}, [
                            m("input", {
                                type: "text",
                                placeholder: "Save name...",
                                value: newSaveName,
                                oninput: function(e) { newSaveName = e.target.value; }
                            }),
                            m("button", {
                                class: "sg-btn sg-btn-primary",
                                onclick: function() {
                                    if (newSaveName.trim()) {
                                        saveGame(newSaveName);
                                        saveDialogOpen = false;
                                    }
                                }
                            }, "Save Game")
                        ]) : null
                    ])
                ])
            ]);
        }
    };

    // Main Layout
    function getOuterView() {
        let layoutClass = fullMode ? "sg-layout sg-layout-full" : "sg-layout";

        return m("div", {class: layoutClass}, [
            // Header bar
            m("div", {class: "sg-header"}, [
                m("div", {class: "sg-header-left"}, [
                    situation && situation.time ?
                        m("span", {class: "sg-header-item"}, [
                            m("span", {class: "material-symbols-outlined"}, "schedule"),
                            " " + situation.time
                        ]) : null,
                    situation && situation.location ?
                        m("span", {class: "sg-header-item"}, [
                            m("span", {class: "material-symbols-outlined"}, "location_on"),
                            " " + (situation.location.feature || situation.location.terrainType || "Unknown")
                        ]) : null
                ]),
                m("div", {class: "sg-header-right"}, [
                    isLoading ? m("span", {class: "sg-loading"}, "Loading...") : null
                ])
            ]),

            // Main content - 3 column layout
            m("div", {class: "sg-main"}, [
                m("div", {class: "sg-col sg-col-left"}, [m(CharacterPanel)]),
                m("div", {class: "sg-col sg-col-center"}, [m(SituationPanel)]),
                m("div", {class: "sg-col sg-col-right"}, [m(ThreatActionPanel)])
            ])
        ]);
    }

    function getFooter() {
        return [
            m("button", {
                class: "flyout-button",
                onclick: startNewGame,
                title: "Start a new game"
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "add_circle"),
                "New"
            ]),
            m("button", {
                class: "flyout-button",
                onclick: function() {
                    saveDialogOpen = true;
                    loadSavedGames();
                    // Suggest a save name based on player and date
                    if (player && player.name) {
                        let d = new Date();
                        let dateStr = (d.getMonth() + 1) + "/" + d.getDate();
                        newSaveName = player.name + " - " + dateStr;
                    }
                }
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "folder_open"),
                "Game"
            ]),
            currentSave ? m("button", {
                class: "flyout-button",
                onclick: function() { saveGame(currentSave.name); }
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "save"),
                "Save"
            ]) : "",
            m("button", {
                class: "flyout-button",
                onclick: loadSituation,
                title: "Refresh situation"
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "refresh"),
                "Refresh"
            ]),
            m("button", {
                class: "flyout-button",
                onclick: advanceTurn,
                title: "End turn and advance time"
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"}, "skip_next"),
                "End Turn"
            ]),
            m("button", {
                class: "flyout-button",
                onclick: function() { fullMode = !fullMode; }
            }, [
                m("span", {class: "material-symbols-outlined material-icons-24"},
                    fullMode ? "close_fullscreen" : "fullscreen"),
                fullMode ? "Exit" : "Full"
            ])
        ];
    }

    // ==========================================
    // ==========================================
    // New Game Functions
    // ==========================================

    // Start a completely new game - resets state and loads fresh character list
    async function startNewGame() {
        // Reset all state
        player = null;
        situation = null;
        selectedEntity = null;
        selectedEntityType = null;
        eventLog = [];
        chatMessages = [];
        chatSession = null;
        currentSave = null;

        // Clear any stored character selection
        sessionStorage.removeItem("olio_selected_character");

        // Load fresh character list using proper AM7 query
        await loadAvailableCharacters();
        if (availableCharacters.length > 0) {
            addEvent("New game started - " + availableCharacters.length + " characters available", "info");
        }

        // Open character selection
        characterSelectOpen = true;
        m.redraw();
    }

    // Set a specific character to start with (called from external navigation)
    function setSelectedCharacter(charId) {
        sessionStorage.setItem("olio_selected_character", charId);
    }

    // ==========================================
    // Component Export
    // ==========================================

    // Keyboard handler for arrow key movement
    function handleKeydown(e) {
        // Only handle if not in an input field
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

        let direction = null;
        switch (e.key) {
            case 'ArrowUp':
            case 'w':
            case 'W':
                direction = 'NORTH';
                break;
            case 'ArrowDown':
            case 's':
            case 'S':
                direction = 'SOUTH';
                break;
            case 'ArrowLeft':
            case 'a':
            case 'A':
                direction = 'WEST';
                break;
            case 'ArrowRight':
            case 'd':
            case 'D':
                direction = 'EAST';
                break;
        }

        if (direction && player && !isLoading) {
            e.preventDefault();
            moveCharacter(direction);
        }
    }

    let cardGame = {
        oncreate: function() {
            document.addEventListener('keydown', handleKeydown);
        },
        onremove: function() {
            document.removeEventListener('keydown', handleKeydown);
        },
        oninit: async function(vnode) {
            // Show loading overlay during initialization
            initializing = true;
            initMessage = "Loading world...";
            m.redraw();

            try {
                // Check for character passed via route or sessionStorage
                let preSelectedCharId = null;

                // Check route params first
                if (vnode.attrs && vnode.attrs.character) {
                    preSelectedCharId = vnode.attrs.character;
                }
                // Then check sessionStorage
                if (!preSelectedCharId) {
                    preSelectedCharId = sessionStorage.getItem("olio_selected_character");
                    if (preSelectedCharId) {
                        sessionStorage.removeItem("olio_selected_character");
                    }
                }

                // Load available characters
                initMessage = "Loading characters...";
                m.redraw();
                await loadAvailableCharacters();

                // If we have a pre-selected character, find and claim them
                if (preSelectedCharId && availableCharacters.length > 0) {
                    let targetChar = availableCharacters.find(c =>
                        c.objectId === preSelectedCharId || c.id === preSelectedCharId
                    );
                    if (targetChar) {
                        initMessage = "Claiming " + targetChar.name + "...";
                        m.redraw();
                        await claimCharacter(targetChar);
                        initializing = false;
                        return;
                    } else {
                        // Character not in available list - might need to be adopted
                        page.toast("warn", "Selected character not found in world population");
                    }
                }

                // Auto-select first character if available
                if (availableCharacters.length > 0) {
                    initMessage = "Starting as " + availableCharacters[0].name + "...";
                    m.redraw();
                    await claimCharacter(availableCharacters[0]);
                } else {
                    // No characters - show selection dialog
                    characterSelectOpen = true;
                }
            } finally {
                initializing = false;
                m.redraw();
            }
        },
        view: function() {
            return [
                (fullMode ? "" : m(page.components.navigation)),
                getOuterView(),
                page.components.dialog.loadDialog(),
                page.loadToast(),
                m(InitLoadingOverlay),
                m(CharacterSelectDialog),
                m(ChatDialog),
                m(SaveGameDialog),
                m("div", {class: "cg-footer-fixed"}, getFooter())
            ];
        }
    };

    // Export external interface for card game
    window.am7cardGame = {
        setSelectedCharacter: setSelectedCharacter,
        startNewGame: startNewGame
    };

    page.views.cardGame = cardGame;
}());
