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
    let moveProgressTimer = null; // Timer for animating progress bar
    let actionInProgress = false; // True when any action is being executed (prevents multiple actions)

    // Chat dialog state
    let chatDialogOpen = false;
    let chatMessages = [];
    let chatPending = false;
    let chatSession = null;
    let chatStreaming = false;  // True when streaming response is in progress

    // Configuration: set to true to use WebSocket streaming for chat (same as regular chat)
    const USE_CHAT_STREAMING = true;

    // NPC-initiated chat requests
    let pendingNpcChats = [];
    let npcChatPollTimer = null;

    // Reimage state
    let reimaging = {};  // Track which entities are being reimaged by objectId

    // Chat image token state
    let chatShowTagSelector = false;
    let chatSelectedImageTags = [];

    function toggleChatImageTag(tag) {
        let idx = chatSelectedImageTags.indexOf(tag);
        if (idx > -1) {
            chatSelectedImageTags.splice(idx, 1);
        } else {
            chatSelectedImageTags.push(tag);
        }
    }

    function insertChatImageToken() {
        if (!chatSelectedImageTags.length) return;
        let token = "${image." + chatSelectedImageTags.join(",") + "}";
        let input = document.querySelector(".sg-chat-input input");
        if (input) {
            input.value = (input.value ? input.value + " " : "") + token;
        }
        chatSelectedImageTags = [];
        chatShowTagSelector = false;
    }

    // --- Chat token processing (same as chat.js) ---
    function pruneTag(cnt, tag) {
        let tdx1 = cnt.toLowerCase().indexOf("<" + tag + ">");
        let maxCheck = 20;
        let check = 0;
        while (tdx1 > -1) {
            if (check++ >= maxCheck) break;
            let tdx2 = cnt.toLowerCase().indexOf("</" + tag + ">");
            if (tdx1 > -1 && tdx2 > -1 && tdx2 > tdx1) {
                cnt = cnt.substring(0, tdx1) + cnt.substring(tdx2 + tag.length + 3, cnt.length);
            }
            tdx1 = cnt.toLowerCase().indexOf("<" + tag + ">");
        }
        return cnt;
    }

    function pruneToMark(cnt, mark) {
        let idx = cnt.indexOf(mark);
        if (idx > -1) {
            cnt = cnt.substring(0, idx);
        }
        return cnt;
    }

    function pruneOut(cnt, start, end) {
        let idx1 = cnt.indexOf(start);
        let idx2 = cnt.indexOf(end);
        if (idx1 > -1 && idx2 > -1 && idx2 > idx1) {
            cnt = cnt.substring(0, idx1) + cnt.substring(idx2 + end.length, cnt.length);
        }
        return cnt;
    }

    function pruneOther(cnt) {
        cnt = cnt.replace(/\[interrupted\]/g, "");
        return cnt;
    }

    function processChatContent(cnt, role) {
        if (!cnt) return "";
        if (typeof cnt !== "string") cnt = String(cnt);
        cnt = pruneOut(cnt, "--- CITATION", "END CITATIONS ---");
        cnt = pruneToMark(cnt, "<|reserved_special_token");
        cnt = pruneTag(cnt, "think");
        cnt = pruneTag(cnt, "thought");
        cnt = pruneOther(cnt);
        if (role === "user") {
            cnt = pruneToMark(cnt, "(Metrics");
            cnt = pruneToMark(cnt, "(Reminder");
            cnt = pruneToMark(cnt, "(KeyFrame");
        }
        if (role === "assistant") {
            cnt = pruneToMark(cnt, "(Metrics");
            cnt = pruneToMark(cnt, "(Reminder");
            cnt = pruneToMark(cnt, "(KeyFrame");
        }
        return cnt.trim();
    }

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
        WALK_TO: { label: "Walk to", icon: "directions_walk", color: "green" },
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

    // Proximity threshold for social actions (in meters)
    const TALK_DISTANCE_THRESHOLD = 10;

    // Configuration: set to true to use PNG tile images instead of emoji
    const USE_TILE_IMAGES = true;

    // Configuration: set to true to use WebSocket streaming instead of REST
    const USE_WEBSOCKET_STREAMING = true;

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
    // WebSocket Game Stream Functions
    // ==========================================

    function getGameStream() {
        return page.gameStream || window.gameStream;
    }

    function initGameStream(charId) {
        let gs = getGameStream();
        if (!gs || !USE_WEBSOCKET_STREAMING) return;

        // Subscribe to push events for this character
        gs.subscribe(charId);

        // Setup push event handlers
        gs.subscriptions.onSituationUpdate = (receivedCharId, newSituation) => {
            if (receivedCharId === charId) {
                situation = newSituation;
                if (player && newSituation) {
                    am7client.syncFromResponse(player, newSituation, am7client.SYNC_MAPPINGS.situation);
                }
                m.redraw();
            }
        };

        gs.subscriptions.onStateUpdate = (receivedCharId, statePatch) => {
            if (receivedCharId === charId && situation) {
                Object.assign(situation.state || {}, statePatch);
                m.redraw();
            }
        };

        gs.subscriptions.onThreatDetected = (receivedCharId, threat) => {
            if (receivedCharId === charId) {
                if (situation && situation.threats) {
                    situation.threats.push(threat);
                }
                addEvent("Threat detected: " + (threat.name || "Unknown"), "danger");
                page.toast("warn", "Threat detected nearby!");
                m.redraw();
            }
        };

        gs.subscriptions.onThreatRemoved = (receivedCharId, threatId) => {
            if (receivedCharId === charId && situation && situation.threats) {
                situation.threats = situation.threats.filter(t => (t.objectId || t.id) !== threatId);
                m.redraw();
            }
        };

        gs.subscriptions.onNpcMoved = (npcId, from, to) => {
            // Update NPC position in nearbyPeople if visible
            if (situation && situation.nearbyPeople) {
                let npc = situation.nearbyPeople.find(p => (p.objectId || p.id) === npcId);
                if (npc && npc.currentLocation) {
                    npc.currentLocation = to;
                    m.redraw();
                }
            }
        };

        gs.subscriptions.onTimeAdvanced = (clockData) => {
            if (situation) {
                situation.clock = clockData;
                m.redraw();
            }
        };

        gs.subscriptions.onEventOccurred = (event) => {
            addEvent(event.description || "World event occurred", "info");
            m.redraw();
        };

        console.log("Game stream initialized for character: " + charId);
    }

    function cleanupGameStream() {
        let gs = getGameStream();
        if (!gs) return;

        let charId = getCharId(player);
        if (charId) {
            gs.unsubscribe(charId);
        }
        gs.clearActiveActions();
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
        if (!playerId || actionInProgress) return null;

        let gs = getGameStream();

        // Use WebSocket streaming if available
        if (USE_WEBSOCKET_STREAMING && gs) {
            return new Promise((resolve) => {
                actionInProgress = true;
                isLoading = true;
                m.redraw();

                gs.executeAction("resolve", {
                    charId: playerId,
                    targetId: targetId,
                    actionType: actionType
                }, {
                    onStart: (id, data) => {
                        console.log("Action started:", data);
                        m.redraw();
                    },
                    onProgress: (id, data) => {
                        console.log("Action progress:", data);
                        // Could show combat rounds, skill checks, etc.
                        if (data.event) {
                            addEvent(data.event.description || "Action in progress...", "info");
                        }
                        m.redraw();
                    },
                    onComplete: (id, data) => {
                        console.log("Action complete:", data);
                        actionResult = data;

                        if (data.situation) {
                            situation = data.situation;
                            if (player) {
                                am7client.syncFromResponse(player, data.situation, am7client.SYNC_MAPPINGS.situation);
                            }
                        }

                        // Log the result
                        if (data.interrupted) {
                            addEvent("Action interrupted by " + (data.interruptSource || "threat") + "!", "danger");
                        } else if (data.result) {
                            let outcome = data.result.outcome || data.result.type || "completed";
                            addEvent(actionType + ": " + outcome,
                                outcome === "POSITIVE" || outcome === "SUCCESS" ? "success" : "info");
                        }

                        actionInProgress = false;
                        isLoading = false;
                        m.redraw();
                        resolve(data);
                    },
                    onError: (id, data) => {
                        console.error("Action error:", data);
                        page.toast("error", data.message || "Action failed");
                        addEvent("Action failed: " + (data.message || "Unknown error"), "danger");
                        actionInProgress = false;
                        isLoading = false;
                        m.redraw();
                        resolve(null);
                    },
                    onInterrupt: (id, data) => {
                        console.log("Action interrupted:", data);
                        addEvent("Action interrupted!", "warning");
                        m.redraw();
                    }
                });
            });
        }

        // Fallback to REST
        actionInProgress = true;
        isLoading = true;
        m.redraw();

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
        } finally {
            actionInProgress = false;
            isLoading = false;
            m.redraw();
        }
        return null;
    }

    async function moveCharacter(direction) {
        let playerId = getCharId(player);
        if (!playerId || actionInProgress) return;

        let gs = getGameStream();
        console.log("Moving character:", playerId, "direction:", direction);

        // Use WebSocket streaming if available
        if (USE_WEBSOCKET_STREAMING && gs) {
            actionInProgress = true;
            isLoading = true;
            moveProgress = 0;
            m.redraw();

            gs.executeAction("move", {
                charId: playerId,
                direction: direction,
                distance: 1
            }, {
                onStart: (actionId, data) => {
                    console.log("Move started:", actionId, data);
                    moveProgress = 10;
                    m.redraw();
                },
                onProgress: (actionId, data) => {
                    console.log("Move progress:", data);
                    moveProgress = data.progress || 50;
                    if (data.currentState) {
                        // Update position in real-time
                        if (situation && situation.state) {
                            Object.assign(situation.state, data.currentState);
                        }
                    }
                    m.redraw();
                },
                onComplete: (actionId, data) => {
                    console.log("Move complete:", data);
                    if (data.situation) {
                        situation = data.situation;
                        if (player) {
                            am7client.syncFromResponse(player, data.situation, am7client.SYNC_MAPPINGS.situation);
                        }
                    }
                    addEvent("Moved " + direction, "info");
                    moveMode = false;
                    moveProgress = 100;
                    actionInProgress = false;
                    isLoading = false;
                    m.redraw();
                },
                onError: (actionId, data) => {
                    console.error("Move error:", data);
                    page.toast("error", data.message || "Movement failed");
                    addEvent(data.message || "Movement failed", "warning");
                    actionInProgress = false;
                    isLoading = false;
                    m.redraw();
                }
            });
            return;
        }

        // Fallback to REST
        actionInProgress = true;
        isLoading = true;
        m.redraw();

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
        } catch (e) {
            console.error("Failed to move", e);
            let errorMsg = "Movement failed";
            if (typeof e === 'string') {
                errorMsg = e;
            } else if (e && e.error) {
                errorMsg = e.error;
            } else if (e && e.response && e.response.error) {
                errorMsg = e.response.error;
            } else if (e && e.message) {
                errorMsg = e.message;
            } else if (e) {
                try { errorMsg = JSON.stringify(e); } catch(_) { errorMsg = String(e); }
            }
            page.toast("error", errorMsg);
            addEvent(errorMsg, "warning");
        } finally {
            actionInProgress = false;
            isLoading = false;
            m.redraw();
        }
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

        // Initialize WebSocket game stream for push notifications
        if (USE_WEBSOCKET_STREAMING) {
            initGameStream(charId);
        }

        await loadSituation();
        startNpcChatPolling();
        addEvent("Now playing as " + player.name, "success");
        page.toast("success", "Now playing as " + char.name);
    }

    async function advanceTurn() {
        if (!player) return;

        let gs = getGameStream();

        // Use WebSocket streaming if available
        if (USE_WEBSOCKET_STREAMING && gs) {
            isLoading = true;
            m.redraw();

            gs.executeAction("advance", {}, {
                onStart: () => {
                    console.log("Advance turn started");
                },
                onComplete: (id, data) => {
                    console.log("Advance turn complete:", data);
                    if (data.situation) {
                        situation = data.situation;
                        if (player) {
                            am7client.syncFromResponse(player, data.situation, am7client.SYNC_MAPPINGS.situation);
                        }
                    }
                    let updated = data.advanced || 0;
                    addEvent("Turn advanced (" + updated + " updated)", "info");
                    isLoading = false;
                    m.redraw();
                },
                onError: (id, data) => {
                    console.error("Advance turn error:", data);
                    page.toast("error", data.message || "Failed to advance turn");
                    isLoading = false;
                    m.redraw();
                }
            });
            return;
        }

        // Fallback to REST
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

    // Create streaming callbacks for chat (same pattern as chat.js)
    function newCardGameChatStream() {
        return {
            streamId: undefined,
            onchatcomplete: function(id) {
                console.log("CardGame chat completed: " + id);
                clearChatStream();
                m.redraw();
            },
            onchaterror: function(id, msg) {
                console.error("CardGame chat error: " + msg);
                chatMessages.push({ role: 'system', content: "Communication failed: " + msg, time: new Date() });
                clearChatStream();
                m.redraw();
            },
            onchatstart: function(id, req) {
                chatMessages.push({ role: 'assistant', content: '', time: new Date() });
                m.redraw();
            },
            onchatupdate: function(id, msg) {
                if (!chatMessages.length) {
                    console.error("Unexpected chat history state");
                    return;
                }
                let lastMsg = chatMessages[chatMessages.length - 1];
                if (lastMsg.role !== 'assistant') {
                    console.error("Expected assistant message to be most recent");
                    return;
                }
                lastMsg.content += msg;
                m.redraw();
            }
        };
    }

    function clearChatStream() {
        page.chatStream = undefined;
        chatStreaming = false;
        chatPending = false;
    }

    async function startChat(target) {
        if (!player || !target) return;
        chatMessages = [];
        chatPending = true;
        chatDialogOpen = true;
        m.redraw();

        try {
            // Load chat config from ~/Chat directory
            let chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
            if (!chatDir) {
                addEvent("Chat directory ~/Chat not found", "error");
                chatDialogOpen = false;
                chatPending = false;
                return;
            }

            // Find the "Open Chat" config template
            let chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 0);
            let templateCfg = chatConfigs.find(c => c.name === "Open Chat");
            if (!templateCfg) {
                addEvent("Chat config 'Open Chat' not found in ~/Chat", "error");
                chatDialogOpen = false;
                chatPending = false;
                return;
            }

            // Create unique chat config name for this character pair
            let actorFirst = player.firstName || player.name.split(" ")[0];
            let targetFirst = target.firstName || target.name.split(" ")[0];
            let chatConfigName = "CardGame - " + actorFirst + " x " + targetFirst;

            // Search for existing chatConfig by name
            let chatCfg;
            let q = am7view.viewQuery("olio.llm.chatConfig");
            q.field("groupId", chatDir.id);
            q.field("name", chatConfigName);
            q.cache(false);
            let qr = await page.search(q);

            // Always load full template for LLM settings
            let fullTemplate = await am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);

            if (qr && qr.results && qr.results.length > 0) {
                chatCfg = qr.results[0];
                // Sync LLM settings from template in case they've changed
                if (fullTemplate && (chatCfg.serverUrl !== fullTemplate.serverUrl ||
                    chatCfg.serviceType !== fullTemplate.serviceType ||
                    chatCfg.model !== fullTemplate.model)) {
                    chatCfg.serverUrl = fullTemplate.serverUrl;
                    chatCfg.serviceType = fullTemplate.serviceType;
                    chatCfg.model = fullTemplate.model;
                    chatCfg.apiKey = fullTemplate.apiKey;
                    chatCfg.apiVersion = fullTemplate.apiVersion;
                    await page.updateObject(chatCfg);
                    console.log("Updated chat config LLM settings from template");
                }
            } else {
                // Copy full template
                let newChatCfg = JSON.parse(JSON.stringify(fullTemplate));

                // Clear identity fields
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

                // Assign characters: target = system (AI), player = user
                newChatCfg.systemCharacter = { objectId: target.objectId };
                newChatCfg.userCharacter = { objectId: player.objectId };

                chatCfg = await page.createObject(newChatCfg);
                if (!chatCfg) {
                    addEvent("Failed to create chat config", "error");
                    chatDialogOpen = false;
                    chatPending = false;
                    return;
                }
            }

            chatSession = {
                actor: player,
                target: target,
                chatConfigId: chatCfg.objectId,
                chatConfig: chatCfg,
                chatRequestId: null  // Will be set if using streaming
            };

            // If using WebSocket streaming, create a ChatRequest for the streaming infrastructure
            if (USE_CHAT_STREAMING) {
                // Find or create a prompt config for game chat
                let promptConfigs = await am7client.list("olio.llm.promptConfig", chatDir.objectId, null, 0, 0);
                let promptCfg = promptConfigs.find(p => p.name === "CardGame Prompt") || promptConfigs[0];

                if (!promptCfg && promptConfigs.length === 0) {
                    // Create a basic prompt config if none exists
                    let newPromptCfg = am7model.newInstance("olio.llm.promptConfig");
                    newPromptCfg.api.groupId(chatDir.id);
                    newPromptCfg.api.groupPath(chatDir.path);
                    newPromptCfg.api.name("CardGame Prompt");
                    newPromptCfg.entity.system = ["You are an AI character in a game world. Stay in character and respond naturally."];
                    promptCfg = await page.createObject(newPromptCfg.entity);
                }

                if (promptCfg) {
                    // Find or create ChatRequest for streaming
                    let chatReqName = "CardGame - " + actorFirst + " x " + targetFirst;

                    // First, search for existing ChatRequest with this name
                    let chatReqDir = await page.findObject("auth.group", "DATA", "~/ChatRequests");
                    let existingChatRequest = null;

                    if (chatReqDir) {
                        let crq = am7view.viewQuery("olio.llm.chatRequest");
                        crq.field("groupId", chatReqDir.id);
                        crq.field("name", chatReqName);
                        crq.cache(false);
                        let crqr = await page.search(crq);
                        if (crqr && crqr.results && crqr.results.length > 0) {
                            existingChatRequest = crqr.results[0];
                            console.log("Found existing ChatRequest:", existingChatRequest.objectId);
                        }
                    }

                    let chatRequest = existingChatRequest;

                    // Create new ChatRequest if none exists
                    if (!chatRequest) {
                        let chatReqBody = {
                            schema: "olio.llm.chatRequest",
                            name: chatReqName,
                            chatConfig: { objectId: chatCfg.objectId },
                            promptConfig: { objectId: promptCfg.objectId },
                            uid: page.uid()
                        };

                        try {
                            chatRequest = await m.request({
                                method: 'POST',
                                url: g_application_path + "/rest/chat/new",
                                withCredentials: true,
                                body: chatReqBody
                            });
                        } catch (createErr) {
                            console.warn("Failed to create ChatRequest:", createErr.message);
                        }
                    }

                    if (chatRequest && chatRequest.objectId) {
                        chatSession.chatRequestId = chatRequest.objectId;
                        chatSession.chatRequest = chatRequest;
                        // Check if the chatConfig has stream enabled
                        chatSession.streamEnabled = chatCfg.stream !== false;
                        console.log("CardGame chat streaming enabled, ChatRequest:", chatRequest.objectId);
                    } else {
                        console.warn("No ChatRequest available for streaming, falling back to REST");
                    }
                }
            }

            addEvent("Started conversation with " + target.name, "info");
        } catch (e) {
            console.error("Failed to setup chat:", e);
            addEvent("Failed to setup chat: " + e.message, "error");
            chatDialogOpen = false;
        }

        chatPending = false;
        m.redraw();
    }

    async function sendChatMessage(text) {
        if (!chatSession || !text.trim()) return;
        chatPending = true;
        chatMessages.push({ role: 'user', content: text, time: new Date() });
        m.redraw();

        // Use WebSocket streaming if enabled and ChatRequest was created
        if (USE_CHAT_STREAMING && chatSession.chatRequestId && chatSession.streamEnabled) {
            try {
                page.chatStream = newCardGameChatStream();
                chatStreaming = true;

                let chatReq = {
                    schema: "olio.llm.chatRequest",
                    objectId: chatSession.chatRequestId,
                    uid: page.uid(),
                    message: text
                };

                page.wss.send("chat", JSON.stringify(chatReq), undefined, "olio.llm.chatRequest");
            } catch (e) {
                console.error("Chat streaming failed, falling back to REST", e);
                clearChatStream();
                await sendChatMessageRest(text);
            }
        } else {
            // Fall back to REST
            await sendChatMessageRest(text);
        }
    }

    // REST fallback for chat when streaming is unavailable
    async function sendChatMessageRest(text) {
        try {
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/chat",
                body: {
                    actorId: chatSession.actor.objectId,
                    targetId: chatSession.target.objectId,
                    chatConfigId: chatSession.chatConfigId,
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

        // Conclude chat and create interaction record via LLM evaluation
        try {
            // Convert chatMessages to the format expected by the server
            let messages = chatMessages.filter(m => m.role !== 'system').map(m => ({
                role: m.role,
                content: m.content
            }));

            if (messages.length > 0) {
                let result = await m.request({
                    method: 'POST',
                    url: g_application_path + "/rest/game/concludeChat",
                    body: {
                        actorId: chatSession.actor.objectId,
                        targetId: chatSession.target.objectId,
                        chatConfigId: chatSession.chatConfigId,
                        messages: messages
                    },
                    withCredentials: true
                });

                // Log the evaluation results
                if (result && result.evaluation) {
                    let evaluation = result.evaluation;
                    let outcomeMsg = "Conversation with " + chatSession.target.name + ": " +
                        (evaluation.outcome || "unknown") + " outcome";
                    if (evaluation.summary) {
                        outcomeMsg += " - " + evaluation.summary;
                    }
                    addEvent(outcomeMsg, evaluation.outcome === "POSITIVE" ? "success" :
                        evaluation.outcome === "NEGATIVE" ? "warning" : "info");
                    console.log("Chat evaluation:", result.evaluation);
                } else {
                    addEvent("Conversation ended with " + chatSession.target.name, "info");
                }
            } else {
                addEvent("Conversation ended with " + chatSession.target.name, "info");
            }
        } catch (e) {
            console.log("Conclude chat failed:", e.message);
        }

        // Clean up streaming state if active
        if (chatStreaming) {
            clearChatStream();
        }

        chatDialogOpen = false;
        chatSession = null;
        chatMessages = [];
        await loadSituation();
    }

    // ==========================================
    // NPC-Initiated Chat Functions
    // ==========================================

    async function pollPendingNpcChats() {
        if (!player) return;

        try {
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/chat/pending",
                body: { playerId: player.objectId },
                withCredentials: true
            });

            if (resp && Array.isArray(resp.pending)) {
                pendingNpcChats = resp.pending;
                m.redraw();
            }
        } catch (e) {
            // Silently ignore polling errors - endpoint may not exist
            console.debug("NPC chat polling error (may be expected):", e.message);
        }
    }

    function startNpcChatPolling() {
        if (npcChatPollTimer) return;
        npcChatPollTimer = setInterval(pollPendingNpcChats, 30000); // Poll every 30 seconds
        pollPendingNpcChats(); // Initial poll
    }

    function stopNpcChatPolling() {
        if (npcChatPollTimer) {
            clearInterval(npcChatPollTimer);
            npcChatPollTimer = null;
        }
    }

    async function acceptNpcChat(npcChat) {
        // Resolve the pending request
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/chat/resolve",
                body: { interactionId: npcChat.interactionId, accepted: true },
                withCredentials: true
            });
        } catch (e) {
            console.log("Failed to resolve chat request");
        }

        // Remove from pending list
        pendingNpcChats = pendingNpcChats.filter(c => c.interactionId !== npcChat.interactionId);

        // Find the NPC and start chat with them
        let npc = nearbyPeople.find(p => p.objectId === npcChat.npcId);
        if (!npc) {
            // Try to find in threats
            npc = nearbyThreats.find(t => t.objectId === npcChat.npcId);
        }
        if (npc) {
            await startChat(npc);
        } else {
            addEvent(npcChat.npcFirstName + " is no longer nearby", "warn");
        }
    }

    async function dismissNpcChat(npcChat) {
        try {
            await m.request({
                method: 'POST',
                url: g_application_path + "/rest/game/chat/resolve",
                body: { interactionId: npcChat.interactionId, accepted: false },
                withCredentials: true
            });
        } catch (e) {
            console.log("Failed to dismiss chat request");
        }

        pendingNpcChats = pendingNpcChats.filter(c => c.interactionId !== npcChat.interactionId);
        m.redraw();
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
            // Server returns {saves: [...]}
            savedGames = (resp && resp.saves) ? resp.saves : (Array.isArray(resp) ? resp : []);
        } catch (e) {
            savedGames = [];
        }
    }

    function getMostRecentSave() {
        if (!savedGames || savedGames.length === 0) return null;
        // Sort by modifiedDate descending
        let sorted = savedGames.slice().sort(function(a, b) {
            let dateA = a.modifiedDate ? new Date(a.modifiedDate) : new Date(0);
            let dateB = b.modifiedDate ? new Date(b.modifiedDate) : new Date(0);
            return dateB - dateA;
        });
        return sorted[0];
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
                    // Reimage loading overlay
                    reimaging[getCharId(player)] ? m("div", {class: "sg-reimage-overlay"}, [
                        m("span", {class: "material-symbols-outlined sg-reimage-spinner"}, "progress_activity"),
                        m("span", {class: "sg-reimage-label"}, "Generating...")
                    ]) : null,
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
                            disabled: !!reimaging[getCharId(player)],
                            onclick: async function(e) {
                                e.stopPropagation();
                                let charId = getCharId(player);
                                reimaging[charId] = true;
                                m.redraw();
                                try {
                                    await m.request({
                                        method: 'GET',
                                        url: g_application_path + "/rest/olio/charPerson/" + charId + "/reimage/false",
                                        withCredentials: true
                                    });
                                    page.toast("success", "Portrait regenerated!");
                                    await loadSituation();
                                } catch (e) {
                                    page.toast("error", "Failed to reimage: " + (e.message || "Unknown error"));
                                } finally {
                                    delete reimaging[charId];
                                    m.redraw();
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

                // Any non-center cell is clickable for movement
                let isClickable = !isCenter;

                return m("div", {
                    class: cellClass + (isClickable ? " sg-cell-clickable" : ""),
                    title: isCenter ? "Click to zoom into cell" : "Walk to cell [" + cellEast + ", " + cellNorth + "]",
                    onclick: function() {
                        if (isCenter) {
                            // Click on player's cell - switch to zoomed view
                            zoomedView = true;
                        } else {
                            // Walk to target cell (use moveTo endpoint)
                            moveToCell(cellEast, cellNorth);
                        }
                        // Entity selection only from threat radar and nearby list, not map
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
            m("span", {class: "sg-color-red"}, "\u{1F43A} Threat"),
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
        let threats = situation && situation.threats ? situation.threats : [];
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

        // Filter threats (animals) in the same cell
        let sameCellThreats = threats.filter(function(t) {
            let loc = t.currentLocation || (t.state && t.state.currentLocation);
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

        // Place same-cell threats (animals) in grid
        for (let threat of sameCellThreats) {
            let tState = threat.state || {};
            let tEast = tState.currentEast || 0;
            let tNorth = tState.currentNorth || 0;
            let gx = Math.min(Math.floor(tEast / 10), ZOOM_SIZE - 1);
            let gy = Math.min(Math.floor(tNorth / 10), ZOOM_SIZE - 1);
            grid[gy][gx].occupants.push({
                type: 'threat',
                name: threat.name,
                objectId: threat.objectId || threat.id,
                record: threat,
                posEast: tEast,
                posNorth: tNorth,
                threatType: threat.threatType || 'animal',
                icon: threat.icon || "\u{1F43A}"
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

        // Helper: get adjacent cell info for a given offset
        function adjInfo(dx, dy) {
            let adjCell = adjacentMap[dx + "," + dy];
            let adjTerrain = getAdjacentTerrain(dx, dy);
            let cellEast = (playerCellEast || 0) + dx;
            let cellNorth = (playerCellNorth || 0) + dy;
            let ident = adjCell ? (cellEast + "," + cellNorth) : "";
            let style = "";
            if (USE_TILE_IMAGES && adjTerrain) {
                style = "background-image: url('" + getTileUrl(adjTerrain) + "'); background-size: cover; background-position: center;";
            }
            return { cell: adjCell, terrain: adjTerrain, east: cellEast, north: cellNorth, ident: ident, style: style };
        }

        // Helper: make a border cell (corner or edge) as a td
        function borderTd(dx, dy, attrs) {
            let info = adjInfo(dx, dy);
            let cls = "sg-border-cell" + (info.cell ? " sg-edge-clickable" : " sg-edge-void");
            let title = info.cell ? "Move to [" + info.east + "," + info.north + "]" : "";
            return m("td", Object.assign({
                class: cls,
                style: info.style,
                title: title,
                onclick: function() { if (info.cell) moveToCell(info.east, info.north); }
            }, attrs || {}));
        }

        // Build table rows
        let tableRows = [];

        // Row 0: NW corner | North edge (colspan 10) | NE corner
        tableRows.push(m("tr", [
            borderTd(-1, 1),
            borderTd(0, 1, {colspan: 10}),
            borderTd(1, 1)
        ]));

        // Row 1: West spacer (rowspan 11) | col headers 0-9 | East spacer (rowspan 11)
        let colHeaderCells = [];
        let westInfo = adjInfo(-1, 0);
        colHeaderCells.push(m("td", {
            class: "sg-border-cell" + (westInfo.cell ? " sg-edge-clickable" : " sg-edge-void"),
            style: westInfo.style,
            title: westInfo.cell ? "Move to [" + westInfo.east + "," + westInfo.north + "]" : "",
            rowspan: 11,
            onclick: function() { if (westInfo.cell) moveToCell(westInfo.east, westInfo.north); }
        }));

        for (let i = 0; i < ZOOM_SIZE; i++) {
            colHeaderCells.push(m("td", {class: "sg-col-header"}, i));
        }

        let eastInfo = adjInfo(1, 0);
        colHeaderCells.push(m("td", {
            class: "sg-border-cell" + (eastInfo.cell ? " sg-edge-clickable" : " sg-edge-void"),
            style: eastInfo.style,
            title: eastInfo.cell ? "Move to [" + eastInfo.east + "," + eastInfo.north + "]" : "",
            rowspan: 11,
            onclick: function() { if (eastInfo.cell) moveToCell(eastInfo.east, eastInfo.north); }
        }));

        tableRows.push(m("tr", colHeaderCells));

        // Rows 2-11: 10 grid rows (north at top)
        let reversedGrid = grid.slice().reverse();
        for (let visualY = 0; visualY < ZOOM_SIZE; visualY++) {
            let gridY = ZOOM_SIZE - 1 - visualY;
            let row = reversedGrid[visualY];
            let rowCells = row.map(function(cell, x) {
                let isPlayerCell = x === playerGridX && gridY === playerGridY;
                let hasPerson = cell.occupants.length > 0;
                let hasPOI = cell.pois.length > 0;
                let hasThreat = cell.occupants.some(function(o) { return o.type === 'threat'; });

                let cellClass = "sg-zoomed-cell";
                if (isPlayerCell) cellClass += " sg-cell-player";
                if (hasThreat) cellClass += " sg-cell-threat";
                if (hasPerson && !isPlayerCell) cellClass += " sg-cell-person";
                if (hasPOI) cellClass += " sg-cell-poi";

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

                let targetEast = x * 10 + 5;
                let targetNorth = gridY * 10 + 5;

                return m("td", {
                    class: cellClass,
                    title: "Move to [" + targetEast + ", " + targetNorth + "]m",
                    onclick: function() {
                        if (!isPlayerCell) moveToPosition(targetEast, targetNorth);
                    }
                }, content);
            });
            // West and East cells are covered by rowspan from row 1
            tableRows.push(m("tr", rowCells));
        }

        // Last row: SW corner | South edge (colspan 10) | SE corner
        tableRows.push(m("tr", [
            borderTd(-1, -1),
            borderTd(0, -1, {colspan: 10}),
            borderTd(1, -1)
        ]));

        let tableStyle = "";
        if (USE_TILE_IMAGES && terrainType) {
            tableStyle = "background-image: url('" + getTileUrl(terrainType) + "'); background-size: cover; background-position: center;";
        }

        return m("div", {class: "sg-zoomed-grid"}, [
            m("table", {class: "sg-cell-table", style: tableStyle}, [
                m("tbody", tableRows)
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

    // Move to any cell using the moveTo endpoint
    async function moveToCell(cellEast, cellNorth) {
        if (!player || isLoading || pendingMove) return;

        let playerLoc = situation && situation.location ? situation.location : null;
        if (!playerLoc) return;

        // Target the center of the destination cell
        await executeMoveTo(cellEast, cellNorth, 50, 50, "Moving to cell...");
    }

    // Walk to a selected entity (person or threat)
    async function walkToEntity(entity) {
        if (!player || isLoading || pendingMove || !entity) return;

        // Get entity's location
        let entityState = entity.state || {};
        let entityLoc = entity.currentLocation || entityState.currentLocation || {};

        let targetCellEast = entityLoc.eastings;
        let targetCellNorth = entityLoc.northings;
        let targetPosEast = entityState.currentEast;
        let targetPosNorth = entityState.currentNorth;

        // If we don't have precise position, target cell center
        if (targetPosEast === undefined) targetPosEast = 50;
        if (targetPosNorth === undefined) targetPosNorth = 50;

        // If we don't have cell coordinates, can't navigate
        if (targetCellEast === undefined || targetCellNorth === undefined) {
            console.warn("Cannot walk to entity - missing location data", entity);
            page.toast("error", "Cannot determine target location");
            return;
        }

        let entityName = entity.name || entity.sourceName || entity.animalType || "target";
        await executeMoveTo(targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, "Walking to " + entityName + "...");
    }

    // Calculate distance between two positions (in meters)
    function calculateDistance(fromCellE, fromCellN, fromPosE, fromPosN, toCellE, toCellN, toPosE, toPosN) {
        let fromX = fromCellE * 100 + fromPosE;
        let fromY = fromCellN * 100 + fromPosN;
        let toX = toCellE * 100 + toPosE;
        let toY = toCellN * 100 + toPosN;
        return Math.sqrt(Math.pow(toX - fromX, 2) + Math.pow(toY - fromY, 2));
    }

    // Start progress bar animation based on estimated time
    function startProgressAnimation(estimatedSeconds) {
        if (moveProgressTimer) {
            clearInterval(moveProgressTimer);
        }
        moveProgress = 0;
        let startTime = Date.now();
        let totalMs = estimatedSeconds * 1000;

        moveProgressTimer = setInterval(function() {
            let elapsed = Date.now() - startTime;
            // Cap at 90% until server responds, use easeOut curve for visual effect
            let linearProgress = elapsed / totalMs;
            // Ease-out curve: starts fast, slows down as it approaches end
            moveProgress = Math.min(90, linearProgress * (2 - linearProgress) * 100);
            m.redraw();
        }, 50); // 50ms for smooth animation
    }

    // Stop progress bar animation
    function stopProgressAnimation() {
        if (moveProgressTimer) {
            clearInterval(moveProgressTimer);
            moveProgressTimer = null;
        }
        moveProgress = 100;
        m.redraw();
    }

    // Execute movement towards a target using the moveTo endpoint
    // Server handles the full movement in a single call (fullMove: true)
    async function executeMoveTo(targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, message) {
        if (pendingMove || actionInProgress) return;

        // Get current position
        let playerLoc = situation && situation.location ? situation.location : null;
        let playerState = player && player.state ? player.state : null;
        let currentCellEast = playerLoc ? (playerLoc.eastings || 0) : 0;
        let currentCellNorth = playerLoc ? (playerLoc.northings || 0) : 0;
        let currentPosEast = playerState ? (playerState.currentEast || 50) : 50;
        let currentPosNorth = playerState ? (playerState.currentNorth || 50) : 50;

        // Calculate distance and estimate time
        // Server typically responds in 0.5-2 seconds, so estimate based on that
        // not actual walking time (which would be much longer)
        let distance = calculateDistance(
            currentCellEast, currentCellNorth, currentPosEast, currentPosNorth,
            targetCellEast, targetCellNorth, targetPosEast, targetPosNorth
        );
        // Estimate 1.5-4 seconds for server response, scale slightly with distance
        // Minimum 1.5s so progress bar is visible, max 4s for long distances
        let estimatedSeconds = Math.min(4, Math.max(1.5, 1 + distance / 200));

        pendingMove = {
            targetCellEast: targetCellEast,
            targetCellNorth: targetCellNorth,
            targetPosEast: targetPosEast,
            targetPosNorth: targetPosNorth,
            aborted: false,
            distance: distance
        };
        actionInProgress = true;

        // Start progress animation
        startProgressAnimation(estimatedSeconds);
        addEvent((message || "Moving...") + " (" + Math.round(distance) + "m)", "info");
        m.redraw();

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
                    fullMove: true  // Server handles the full movement
                },
                withCredentials: true
            });

            situation = resp;
            if (player && resp) {
                am7client.syncFromResponse(player, resp, am7client.SYNC_MAPPINGS.situation);
            }

            // Check result
            if (resp.abortReason) {
                addEvent("Movement stopped: " + resp.abortReason, "warning");
            } else if (resp.threats && resp.threats.length > 0) {
                addEvent("Arrived - threats nearby!", "danger");
            } else if (resp.arrived) {
                addEvent("Arrived at destination", "success");
            } else {
                addEvent("Movement complete", "info");
            }

        } catch (e) {
            console.error("MoveTo failed", e);
            let errorMsg = "Unknown error";
            if (typeof e === 'string') {
                errorMsg = e;
            } else if (e && e.error) {
                errorMsg = e.error;
            } else if (e && e.response && e.response.error) {
                errorMsg = e.response.error;
            } else if (e && e.message) {
                errorMsg = e.message;
            } else if (e && e.code) {
                errorMsg = "HTTP " + e.code;
            } else if (e) {
                try { errorMsg = JSON.stringify(e); } catch(_) { errorMsg = String(e); }
            }
            addEvent("Movement blocked: " + errorMsg, "warning");
        } finally {
            isLoading = false;
            stopProgressAnimation();
        }

        // Clean up
        pendingMove = null;
        actionInProgress = false;
        moveProgress = 0;
        m.redraw();
    }

    // Abort a pending move
    function abortMove() {
        if (pendingMove) {
            pendingMove.aborted = true;
        }
    }

    function renderMovementPad() {
        let isDisabled = actionInProgress || isLoading || pendingMove;
        let btnClass = "sg-move-btn" + (isDisabled ? " sg-btn-disabled" : "");

        return m("div", {class: "sg-move-pad"}, [
            m("div", {class: "sg-move-row"}, [
                m("div"), // spacer
                m("button", {class: btnClass, disabled: isDisabled, onclick: function() { if (!isDisabled) moveCharacter("NORTH"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_upward")
                ]),
                m("div") // spacer
            ]),
            m("div", {class: "sg-move-row"}, [
                m("button", {class: btnClass, disabled: isDisabled, onclick: function() { if (!isDisabled) moveCharacter("WEST"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_back")
                ]),
                m("button", {class: "sg-move-btn sg-move-center"}, [
                    m("span", {class: "material-symbols-outlined"}, "radio_button_checked")
                ]),
                m("button", {class: btnClass, disabled: isDisabled, onclick: function() { if (!isDisabled) moveCharacter("EAST"); }}, [
                    m("span", {class: "material-symbols-outlined"}, "arrow_forward")
                ])
            ]),
            m("div", {class: "sg-move-row"}, [
                m("div"), // spacer
                m("button", {class: btnClass, disabled: isDisabled, onclick: function() { if (!isDisabled) moveCharacter("SOUTH"); }}, [
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
            let rawPeople = situation && situation.nearbyPeople ? situation.nearbyPeople : [];
            let clock = situation && situation.clock ? situation.clock : null;
            let interactions = situation && situation.interactions ? situation.interactions : [];

            // Exclude the player and sort by distance (closest first)
            let playerOid = player && player.objectId;
            let people = rawPeople.filter(function(p) {
                return p.objectId !== playerOid;
            }).sort(function(a, b) {
                let distA = getDistanceAndDirection(a.state, a.currentLocation).distance;
                let distB = getDistanceAndDirection(b.state, b.currentLocation).distance;
                return distA - distB;
            });

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
                        // Show current increment (1-hour block) - changes with Overwatch processing
                        clock.increment ? m("div", {class: "sg-clock-increment"}, clock.increment.name || "") : null,
                        m("div", {class: "sg-clock-remaining"}, [
                            clock.remainingDays !== undefined ? clock.remainingDays + " days remaining" : ""
                        ])
                    ])
                ]) : null,

                // Action progress bar (shown when movement or action is in progress)
                pendingMove ? m("div", {class: "sg-action-progress-section"}, [
                    m("div", {class: "sg-panel-header sg-header-action"}, [
                        m("span", {class: "material-symbols-outlined"}, "directions_walk"),
                        " MOVING"
                    ]),
                    m("div", {class: "sg-action-progress"}, [
                        m("div", {class: "sg-progress-bar-large"}, [
                            m("div", {
                                class: "sg-progress-fill",
                                style: "width: " + moveProgress + "%"
                            })
                        ]),
                        m("div", {class: "sg-progress-info"}, [
                            m("span", Math.round(pendingMove.distance || 0) + "m"),
                            m("button", {
                                class: "sg-btn sg-btn-small sg-btn-danger",
                                onclick: abortMove
                            }, [
                                m("span", {class: "material-symbols-outlined"}, "stop"),
                                " Stop"
                            ])
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

                // Nearby people (sorted by distance, closest first)
                people.length ? m("div", {class: "sg-people-section"}, [
                    m("div", {class: "sg-panel-header sg-header-info"}, [
                        m("span", {class: "material-symbols-outlined"}, "groups"),
                        " NEARBY",
                        people.length > 3 ? m("span", {class: "sg-nearby-count"}, " (" + people.length + ")") : null
                    ]),
                    people.slice(0, 5).map(renderPersonCard)
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
        let portrait = null;

        if (threat.isAnimal) {
            icon = "\u{1F43E}"; // Paw print for animals
            if (threat.animalType) {
                name = threat.animalType;
                if (threat.sourceName && threat.sourceName !== threat.animalType) {
                    name = threat.animalType + " (" + threat.sourceName + ")";
                }
            }
            typeLabel = threat.groupType || "ANIMAL";
        } else {
            // Try to get portrait for person threats
            portrait = getPortraitUrl(threat, "64x64");
        }

        // Calculate distance and direction from player to threat
        let distDir = getDistanceAndDirection(threat.state, threat.currentLocation);
        let distanceStr = distDir.distance >= 1000 ?
            (distDir.distance / 1000).toFixed(1) + "km" :
            distDir.distance + "m";
        let compassArrow = distDir.direction ? distDir.direction.arrow : "";
        let compassDir = distDir.direction ? distDir.direction.dir : "";

        // Check if this threat is selected
        let threatId = threat.source || threat.objectId || threat.id;
        let selectedId = selectedEntity ? (selectedEntity.source || selectedEntity.objectId || selectedEntity.id) : null;
        let isSelected = selectedEntityType === 'threat' && threatId && selectedId && threatId === selectedId;

        return m("div", {
            class: "sg-threat-card sg-border-" + pColor + (isSelected ? " sg-card-selected" : ""),
            onclick: function() { selectEntity(threat, 'threat'); }
        }, [
            m("div", {class: "sg-threat-header"}, [
                portrait ?
                    m("img", {src: portrait, class: "sg-threat-portrait"}) :
                    m("span", {class: "sg-threat-icon"}, icon),
                m("div", {class: "sg-threat-info"}, [
                    m("div", {class: "sg-threat-name"}, name),
                    m("div", {class: "sg-threat-type"}, typeLabel)
                ]),
                m("div", {class: "sg-threat-distance"}, [
                    m("span", {class: "sg-compass-arrow", title: compassDir}, compassArrow),
                    " " + distanceStr
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

    // Convert angle (in degrees, 0=East, counter-clockwise) to 8-point compass direction
    // Returns { direction: "NE", arrow: "↗" }
    function angleToCompass(angleDeg) {
        // Normalize angle to 0-360
        while (angleDeg < 0) angleDeg += 360;
        while (angleDeg >= 360) angleDeg -= 360;

        // 8-point compass: each direction covers 45 degrees
        // Arrows point in the direction of travel
        const directions = [
            { dir: "E",  arrow: "→" },   // 0 degrees (centered at 0)
            { dir: "NE", arrow: "↗" },   // 45 degrees
            { dir: "N",  arrow: "↑" },   // 90 degrees
            { dir: "NW", arrow: "↖" },   // 135 degrees
            { dir: "W",  arrow: "←" },   // 180 degrees
            { dir: "SW", arrow: "↙" },   // 225 degrees
            { dir: "S",  arrow: "↓" },   // 270 degrees
            { dir: "SE", arrow: "↘" }    // 315 degrees
        ];

        // Each sector is 45 degrees, offset by 22.5 to center
        let index = Math.round(angleDeg / 45) % 8;
        return directions[index];
    }

    // Calculate distance and direction from player to a target
    function getDistanceAndDirection(targetState, targetLocation) {
        if (!player || !situation) return { distance: 0, direction: null };

        let playerState = player.state || {};
        let playerLoc = situation.location || {};

        // Player absolute position
        let playerAbsX = (playerLoc.eastings || 0) * 100 + (playerState.currentEast || 0);
        let playerAbsY = (playerLoc.northings || 0) * 100 + (playerState.currentNorth || 0);

        // Target absolute position
        let targetLoc = targetLocation || (targetState && targetState.currentLocation) || {};
        let targetAbsX = (targetLoc.eastings || 0) * 100 + ((targetState && targetState.currentEast) || 0);
        let targetAbsY = (targetLoc.northings || 0) * 100 + ((targetState && targetState.currentNorth) || 0);

        // Calculate distance
        let dx = targetAbsX - playerAbsX;
        let dy = targetAbsY - playerAbsY;
        let distance = Math.sqrt(dx * dx + dy * dy);

        // Calculate angle (atan2 gives radians, convert to degrees)
        // atan2(dy, dx) gives angle where 0=East, positive=counter-clockwise
        let angleRad = Math.atan2(dy, dx);
        let angleDeg = angleRad * (180 / Math.PI);

        return {
            distance: Math.round(distance),
            direction: angleToCompass(angleDeg)
        };
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

        // Calculate distance and direction from player
        let distDir = getDistanceAndDirection(record.state, record.currentLocation);
        let distanceStr = distDir.distance >= 1000 ?
            (distDir.distance / 1000).toFixed(1) + "km" :
            distDir.distance + "m";
        let compassArrow = distDir.direction ? distDir.direction.arrow : "";
        let compassDir = distDir.direction ? distDir.direction.dir : "";

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
                m("div", {class: "sg-person-distance"}, [
                    m("span", {class: "sg-compass-arrow", title: compassDir}, compassArrow),
                    " " + distanceStr
                ]),
                m("div", {class: "sg-person-rel"}, person.relationship || "neutral")
            ])
        ]);
    }

    function renderAvailableActions() {
        // Context-based actions
        let actions = [];

        // If threat selected, show combat actions
        if (selectedEntityType === 'threat') {
            // Calculate distance to threat
            let distDir = getDistanceAndDirection(selectedEntity.state, selectedEntity.currentLocation);
            let distanceToThreat = distDir.distance;

            if (distanceToThreat > TALK_DISTANCE_THRESHOLD) {
                actions.push({type: "WALK_TO", primary: true, target: selectedEntity});
            }
            actions.push({type: "COMBAT", primary: distanceToThreat <= TALK_DISTANCE_THRESHOLD});
            actions.push({type: "FLEE"});
            actions.push({type: "WATCH"});
        }
        // If person selected, show social actions based on distance
        else if (selectedEntityType === 'person') {
            // Calculate distance to selected person
            let distDir = getDistanceAndDirection(selectedEntity.state, selectedEntity.currentLocation);
            let distanceToPerson = distDir.distance;

            // If too far away, primary action is "Walk to"
            if (distanceToPerson > TALK_DISTANCE_THRESHOLD) {
                actions.push({type: "WALK_TO", primary: true, target: selectedEntity});
                // Show Talk as disabled hint
                actions.push({type: "TALK", disabled: true, hint: "Get closer to talk (" + distanceToPerson + "m away)"});
            } else {
                // Close enough to interact
                actions.push({type: "TALK", primary: true});
                actions.push({type: "BARTER"});
            }
            actions.push({type: "HELP"});
            actions.push({type: "COMBAT"});
        }
        // Default actions when nothing selected
        else {
            actions.push({type: "INVESTIGATE"});
            actions.push({type: "WATCH"});
        }

        // Action buttons (disabled when action is in progress or action-specific disabled)
        let globalDisabled = actionInProgress || isLoading || pendingMove;
        let actionButtons = m("div", {class: "sg-action-buttons"}, actions.map(function(act) {
            let def = ACTION_TYPES[act.type] || {label: act.type, icon: "help", color: "gray"};
            let isActDisabled = globalDisabled || act.disabled;
            let tooltip = act.hint || def.label;

            return m("button", {
                class: "sg-btn sg-btn-action" +
                    (act.primary ? " sg-btn-primary" : "") +
                    (isActDisabled ? " sg-btn-disabled" : "") +
                    (act.disabled ? " sg-btn-unavailable" : ""),
                disabled: isActDisabled,
                title: tooltip,
                onclick: function() {
                    if (isActDisabled) return;
                    if (act.type === "WALK_TO" && act.target) {
                        // Walk to the selected entity
                        walkToEntity(act.target);
                    } else {
                        executeAction(act.type);
                    }
                }
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
                        disabled: !!reimaging[entityId],
                        onclick: async function() {
                            reimaging[entityId] = true;
                            m.redraw();
                            try {
                                if (isPerson) {
                                    await m.request({
                                        method: 'GET',
                                        url: g_application_path + "/rest/olio/charPerson/" + entityId + "/reimage/false",
                                        withCredentials: true
                                    });
                                    page.toast("success", "Portrait regenerated!");
                                } else if (isAnimal) {
                                    await m.request({
                                        method: 'POST',
                                        url: g_application_path + "/rest/olio/animal/" + entityId + "/reimage",
                                        body: {hires: false},
                                        withCredentials: true
                                    });
                                    page.toast("success", "Animal image generated!");
                                }
                                await loadSituation();
                            } catch (e) {
                                page.toast("error", "Failed to reimage: " + (e.message || "Unknown error"));
                            } finally {
                                delete reimaging[entityId];
                                m.redraw();
                            }
                        },
                        title: "Reimage"
                    }, [
                        reimaging[entityId]
                            ? m("span", {class: "material-symbols-outlined sg-reimage-spinner"}, "progress_activity")
                            : m("span", {class: "material-symbols-outlined"}, "auto_awesome")
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

            let playerPortrait = getPortraitUrl(player, "64x64");
            let targetPortrait = getPortraitUrl(chatSession.target, "64x64");

            // Build avatar vnodes
            let playerAvatar = playerPortrait
                ? m("img", {src: playerPortrait})
                : m("span", {class: "material-symbols-outlined text-sm text-gray-500"}, "person");
            let targetAvatar = targetPortrait
                ? m("img", {src: targetPortrait})
                : m("span", {class: "material-symbols-outlined text-sm text-gray-500"}, "person");

            // Image tag selector row
            let tagSelector = null;
            if (chatShowTagSelector && window.am7imageTokens) {
                tagSelector = m("div", {class: "sg-chat-tag-selector"}, [
                    window.am7imageTokens.tags.map(function(tag) {
                        let isSelected = chatSelectedImageTags.indexOf(tag) > -1;
                        return m("button", {
                            class: "px-2 py-0.5 rounded-full text-xs " +
                                (isSelected ? "bg-purple-600 text-white" : "bg-gray-500 text-gray-200 hover:bg-gray-400"),
                            onclick: function() { toggleChatImageTag(tag); }
                        }, tag);
                    }),
                    chatSelectedImageTags.length > 0
                        ? m("button", {
                            class: "ml-2 px-3 py-0.5 rounded bg-purple-600 text-white text-xs hover:bg-purple-700",
                            onclick: function() { insertChatImageToken(); }
                        }, "Insert " + chatSelectedImageTags.join(",") + " pic")
                        : null
                ]);
            }

            return m("div", {class: "sg-dialog-overlay"}, [
                m("div", {class: "sg-dialog sg-dialog-chat"}, [
                    // Header with portraits
                    m("div", {class: "sg-dialog-header"}, [
                        m("div", {class: "sg-chat-header-portraits"}, [
                            playerPortrait ? m("img", {class: "sg-chat-header-portrait", src: playerPortrait}) : null,
                            m("span", {class: "material-symbols-outlined"}, "forum"),
                            targetPortrait ? m("img", {class: "sg-chat-header-portrait", src: targetPortrait}) : null
                        ]),
                        m("span", {style: "margin-left: 8px;"}, " Talking to " + (chatSession.target.firstName || chatSession.target.name || "Unknown")),
                        m("button", {
                            class: "sg-dialog-close",
                            onclick: endChat
                        }, "\u00D7")
                    ]),

                    // Messages with avatars
                    m("div", {class: "sg-chat-messages", oncreate: function(vnode) {
                        vnode.dom.scrollTop = vnode.dom.scrollHeight;
                    }, onupdate: function(vnode) {
                        vnode.dom.scrollTop = vnode.dom.scrollHeight;
                    }}, chatMessages.map(function(msg) {
                        let cnt = processChatContent(msg.content || "", msg.role);
                        if (!cnt) return null;
                        let rendered = (msg.role === "assistant" && typeof marked !== "undefined")
                            ? m.trust(marked.parse(cnt.replace(/\r/, "")))
                            : cnt;
                        let isUser = msg.role === "user";
                        let avatar = isUser ? playerAvatar : targetAvatar;
                        let roleLabel = isUser
                            ? (player ? player.firstName || player.name : "You")
                            : (chatSession.target ? chatSession.target.firstName || chatSession.target.name : "NPC");

                        if (msg.role === "system") {
                            return m("div", {class: "sg-chat-msg sg-chat-system"}, [
                                m("div", {class: "sg-chat-content"}, cnt)
                            ]);
                        }

                        return m("div", {class: "sg-chat-msg-row" + (isUser ? " sg-chat-row-user" : "")}, [
                            m("div", {class: "sg-chat-avatar"}, avatar),
                            m("div", {class: "sg-chat-msg sg-chat-" + msg.role}, [
                                m("div", {class: "sg-chat-role"}, roleLabel),
                                m("div", {class: "sg-chat-content"}, rendered)
                            ])
                        ]);
                    })),

                    // Tag selector (shown when image button toggled)
                    tagSelector,

                    // Input area
                    m("div", {class: "sg-chat-input"}, [
                        // Image token button
                        window.am7imageTokens ? m("button", {
                            class: "sg-btn sg-btn-small sg-btn-icon",
                            style: chatShowTagSelector ? "color: #9333ea;" : "",
                            onclick: function() {
                                chatShowTagSelector = !chatShowTagSelector;
                                if (!chatShowTagSelector) chatSelectedImageTags = [];
                            },
                            title: "Insert image token"
                        }, [
                            m("span", {class: "material-symbols-outlined"}, chatShowTagSelector ? "image" : "add_photo_alternate")
                        ]) : null,
                        chatPending ?
                            m("div", {class: "sg-chat-pending-bar"},
                                m("div", {class: "sg-chat-pending-progress"})
                            ) :
                            m("input", {
                                type: "text",
                                placeholder: "Type a message...",
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
                                let input = e.target.closest(".sg-chat-input").querySelector("input");
                                if (input && input.value.trim()) {
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

    // NPC Chat Request Notifications
    let NpcChatNotifications = {
        view: function() {
            if (!pendingNpcChats || !Array.isArray(pendingNpcChats) || pendingNpcChats.length === 0) return null;

            return m("div", {class: "sg-npc-chat-notifications"},
                pendingNpcChats.filter(function(npcChat) { return npcChat != null; }).map(function(npcChat) {
                    return m("div", {class: "sg-npc-chat-notification", key: npcChat.interactionId || Math.random()}, [
                        m("div", {class: "sg-npc-chat-icon"},
                            m("span", {class: "material-symbols-outlined"}, "chat_bubble")
                        ),
                        m("div", {class: "sg-npc-chat-content"}, [
                            m("div", {class: "sg-npc-chat-name"}, npcChat.npcFirstName || npcChat.npcName),
                            m("div", {class: "sg-npc-chat-reason"}, npcChat.reason || "wants to talk")
                        ]),
                        m("div", {class: "sg-npc-chat-actions"}, [
                            m("button", {
                                class: "sg-btn sg-btn-sm sg-btn-primary",
                                onclick: function() { acceptNpcChat(npcChat); }
                            }, "Talk"),
                            m("button", {
                                class: "sg-btn sg-btn-sm",
                                onclick: function() { dismissNpcChat(npcChat); }
                            }, "Ignore")
                        ])
                    ]);
                })
            );
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
        // Clean up streaming state if active
        if (chatStreaming) {
            clearChatStream();
        }
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

                // If we have a pre-selected character, load characters and claim
                if (preSelectedCharId) {
                    initMessage = "Loading characters...";
                    m.redraw();
                    await loadAvailableCharacters();

                    if (availableCharacters.length > 0) {
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
                            page.toast("warn", "Selected character not found in world population");
                        }
                    }
                }

                // Check for saved games first
                initMessage = "Checking for saved games...";
                m.redraw();
                await loadSavedGames();

                let mostRecent = getMostRecentSave();
                if (mostRecent) {
                    // Auto-load the most recent save
                    initMessage = "Loading saved game: " + mostRecent.name + "...";
                    m.redraw();
                    await loadGame(mostRecent);
                    initializing = false;
                    return;
                }

                // No saved games - open the new game dialog
                initMessage = "Loading characters...";
                m.redraw();
                await loadAvailableCharacters();
                characterSelectOpen = true;

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
                m(NpcChatNotifications),
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
