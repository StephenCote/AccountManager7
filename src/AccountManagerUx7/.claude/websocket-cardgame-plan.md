# Comprehensive WebSocket Integration Plan for Card Game

## Executive Summary

This plan covers **all game actions** (N actions) and **server-side push notifications** from the Overwatch simulation engine. The design uses a generic action streaming pattern that scales to any action type, plus server-push events for world state changes.

---

## Current Architecture Analysis

### Communication Patterns

| Component | Current | Proposed |
|-----------|---------|----------|
| **Card Game** | REST only (`m.request()`) | WebSocket streaming |
| **Chat** | WebSocket streaming | No change |
| **Audio** | WebSocket streaming | No change |
| **World Events** | Not pushed | WebSocket push |

### Existing WebSocket Infrastructure

**Client** (`pageClient.js`):
```javascript
page.wss = { connect, close, send }
page.chatStream = { onchatstart, onchatupdate, onchatcomplete, onchaterror }
```

**Server** (`WebSocketService.java`):
```java
@ServerEndpoint("/wss")
// Routes by message.name: "chat", "audio"
// Sends chirps: ["chatUpdate", id, data]
```

---

## Complete Action Inventory

### REST Endpoints (GameService.java)

| Category | Endpoint | Method | Streaming Benefit |
|----------|----------|--------|-------------------|
| **Movement** | `/game/move/{id}` | POST | Progress updates |
| | `/game/moveTo/{id}` | POST | Multi-cell progress |
| **Actions** | `/game/resolve/{id}` | POST | Step-by-step resolution |
| | `/game/consume/{id}` | POST | Consumption progress |
| | `/game/investigate/{id}` | POST | Discovery events |
| | `/game/interact` | POST | Interaction phases |
| | `/game/advance` | POST | Turn progression |
| **Chat** | `/game/chat` | POST | LLM streaming |
| | `/game/concludeChat` | POST | Evaluation result |
| **State** | `/game/situation/{id}` | GET | Push updates |
| | `/game/state/{id}` | GET | Push updates |
| | `/game/status/{id}` | GET | Push updates |
| **Character** | `/game/claim/{id}` | POST | Confirmation |
| | `/game/release/{id}` | POST | Confirmation |
| | `/game/adopt/{id}` | POST | Confirmation |
| **Save/Load** | `/game/save` | POST | Progress |
| | `/game/load/{id}` | GET | Progress |
| | `/game/saves` | GET | N/A |
| | `/game/deleteSave/{id}` | POST | Confirmation |
| **Misc** | `/game/newGame` | GET | N/A |
| | `/game/outfit/generate` | POST | Generation progress |
| | `/game/tile/{terrain}` | GET | N/A |

### Action Classes (IAction implementations)

| Action | Purpose | Streamable Events |
|--------|---------|-------------------|
| `Walk` | Single-step movement | Position updates |
| `WalkTo` | Multi-step pathfinding | Position per meter |
| `Consume` | Eat/drink items | State changes |
| `Gather` | Collect resources | Items found |
| `Look` | Examine environment | Discoveries |
| `Peek` | Investigation check | Perception results |
| `Sleep` | Rest/fatigue recovery | State updates |
| `Wake` | End sleep | State transition |
| `Dress` | Equip apparel | Inventory changes |
| `Undress` | Remove apparel | Inventory changes |
| `Transfer` | Move items | Inventory changes |
| `InteractionAction` | Social/combat | Interaction phases |

### Interaction Types (37 total)

**Positive**: ACCOMMODATE, ALLY, BEFRIEND, COOPERATE, DATE, ENTERTAIN, EXPRESS_GRATITUDE, HELP, INTIMATE, MENTOR, RECREATE, ROMANCE

**Neutral**: COMMERCE, COMPETE, CORRESPOND, DEBATE, DEFEND, EXCHANGE, INVESTIGATE, NEGOTIATE, SOCIALIZE

**Negative**: BREAK_UP, COERCE, COMBAT, CONFLICT, CRITICIZE, EXPRESS_INDIFFERENCE, OPPOSE, PEER_PRESSURE, SHUN, THREATEN

**Other**: RELATE, SEPARATE, UNKNOWN, NONE

### Threat Types (26 total)

PHYSICAL, PSYCHOLOGICAL, SOCIAL, ECONOMIC, HEALTH, ENVIRONMENTAL, VERBAL, IDEOLOGICAL, PERSONAL, POLITICAL, EXISTENTIAL, ANIMAL, HOLLOW (+ target variants)

---

## Overwatch Engine Analysis

### Processing Loop

```
Overwatch.process()
├── pruneCompleted()           // Clean up finished actions
├── processActions()           // Execute pending actions
│   └── action.executeAction()
│   └── action.concludeAction()
├── processInteractions()      // Resolve character interactions
├── processGroups()            // Group behavior
├── processProximity()         // Nearby reactions
├── processSchedules()         // Timed events
├── processEvents()            // Environmental events
└── syncClocks()               // Time progression
```

### Watched Items (OverwatchEnumType)

| Type | Purpose | Push Events |
|------|---------|-------------|
| `ACTION` | Pending/in-progress actions | Action progress, completion |
| `INTERACTION` | Character interactions | Interaction phases, outcomes |
| `TIME` | Scheduled events | Time-triggered events |
| `PROXIMITY` | Nearby character reactions | Threat detection, encounters |
| `RESPONSE` | Counter-actions/reactions | Reaction notifications |
| `GROUP` | Group behavior | Group state changes |
| `EVENT` | Environmental events | World events |

### Event Hierarchy

```
Realm
└── Epoch (long time period)
    └── RealmEvent (location-specific segment)
        └── Increment (atomic time unit, ~1 hour)
            ├── Actions occur here
            ├── Interactions resolved here
            └── State changes tracked here
```

---

## WebSocket Message Architecture

### Message Categories

```
┌─────────────────────────────────────────────────────────────────┐
│                    WEBSOCKET MESSAGE TYPES                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  CLIENT → SERVER (Requests)                                      │
│  ─────────────────────────────────────────────────────────────── │
│  game.action.{type}     - Execute any action                     │
│  game.subscribe         - Subscribe to push events               │
│  game.unsubscribe       - Unsubscribe from events                │
│                                                                   │
│  SERVER → CLIENT (Responses & Push)                              │
│  ─────────────────────────────────────────────────────────────── │
│  game.action.start      - Action initiated                       │
│  game.action.progress   - Action progress update                 │
│  game.action.complete   - Action finished                        │
│  game.action.error      - Action failed                          │
│  game.action.interrupt  - Action interrupted                     │
│                                                                   │
│  game.situation.update  - Full situation refresh                 │
│  game.state.update      - Character state changed                │
│                                                                   │
│  game.threat.detected   - New threat nearby                      │
│  game.threat.removed    - Threat eliminated                      │
│  game.threat.changed    - Threat status changed                  │
│                                                                   │
│  game.npc.moved         - NPC position changed                   │
│  game.npc.action        - NPC performed action                   │
│  game.npc.died          - NPC died/incapacitated                 │
│                                                                   │
│  game.interaction.start - Interaction began                      │
│  game.interaction.phase - Interaction phase update               │
│  game.interaction.end   - Interaction resolved                   │
│                                                                   │
│  game.time.advanced     - Clock progressed                       │
│  game.increment.end     - Time increment ended                   │
│  game.event.occurred    - World event happened                   │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Chirp Format

All messages use the existing chirp array format:

```javascript
// Server sends:
{
  "chirps": ["game.action.progress", actionId, progressData]
}

// Where progressData is JSON string:
{
  "actionType": "move",
  "progress": 50,
  "currentState": { ... },
  "metadata": { ... }
}
```

---

## Generic Action Streaming Pattern

### Client-Side Pattern

```javascript
// gameStream.js - Generic action streaming for ANY action type

const gameStream = {
    // Active action tracking
    activeActions: new Map(),  // actionId → callbacks

    // Generic action execution
    executeAction: function(actionType, params, callbacks) {
        const actionId = generateUUID();

        // Register callbacks for this action
        this.activeActions.set(actionId, {
            onStart: callbacks.onStart || (() => {}),
            onProgress: callbacks.onProgress || (() => {}),
            onComplete: callbacks.onComplete || (() => {}),
            onError: callbacks.onError || (() => {}),
            onInterrupt: callbacks.onInterrupt || (() => {})
        });

        // Send action request
        page.wss.send("game", JSON.stringify({
            actionId: actionId,
            actionType: actionType,
            params: params
        }), undefined, "game.action." + actionType);

        return actionId;
    },

    // Cancel action
    cancelAction: function(actionId) {
        page.wss.send("game", JSON.stringify({
            actionId: actionId,
            cancel: true
        }), undefined, "game.action.cancel");
    },

    // Route incoming messages
    routeMessage: function(chirpType, ...args) {
        const [actionId, data] = args;
        const callbacks = this.activeActions.get(actionId);

        if (callbacks) {
            switch(chirpType) {
                case "game.action.start":
                    callbacks.onStart(actionId, JSON.parse(data));
                    break;
                case "game.action.progress":
                    callbacks.onProgress(actionId, JSON.parse(data));
                    break;
                case "game.action.complete":
                    callbacks.onComplete(actionId, JSON.parse(data));
                    this.activeActions.delete(actionId);
                    break;
                case "game.action.error":
                    callbacks.onError(actionId, JSON.parse(data));
                    this.activeActions.delete(actionId);
                    break;
                case "game.action.interrupt":
                    callbacks.onInterrupt(actionId, JSON.parse(data));
                    break;
            }
        }
    },

    // Push event subscriptions
    subscriptions: {
        onSituationUpdate: null,
        onStateUpdate: null,
        onThreatDetected: null,
        onThreatRemoved: null,
        onNpcMoved: null,
        onNpcAction: null,
        onInteractionStart: null,
        onInteractionEnd: null,
        onTimeAdvanced: null,
        onEventOccurred: null
    },

    // Subscribe to push events for a character
    subscribe: function(charId) {
        page.wss.send("game", JSON.stringify({
            subscribe: true,
            charId: charId
        }), undefined, "game.subscribe");
    },

    unsubscribe: function(charId) {
        page.wss.send("game", JSON.stringify({
            unsubscribe: true,
            charId: charId
        }), undefined, "game.unsubscribe");
    }
};
```

### Usage in cardGame.js

```javascript
// Movement - streaming
function moveCharacter(direction, distance) {
    if (actionInProgress) return;

    currentActionId = gameStream.executeAction("move", {
        charId: playerId,
        direction: direction,
        distance: distance || 1
    }, {
        onStart: (id, data) => {
            actionInProgress = true;
            moveProgress = 0;
            eventLog.push({ type: "move_start", data });
            m.redraw();
        },
        onProgress: (id, data) => {
            moveProgress = data.progress;
            situation.state.currentEast = data.currentEast;
            situation.state.currentNorth = data.currentNorth;
            m.redraw();
        },
        onComplete: (id, data) => {
            situation = data.situation;
            actionInProgress = false;
            moveProgress = 100;
            eventLog.push({ type: "move_complete", data });
            m.redraw();
        },
        onError: (id, data) => {
            actionInProgress = false;
            page.toast("error", data.message);
            m.redraw();
        }
    });
}

// Combat - streaming
function initiateInteraction(targetId, interactionType) {
    if (actionInProgress) return;

    currentActionId = gameStream.executeAction("interact", {
        charId: playerId,
        targetId: targetId,
        interactionType: interactionType
    }, {
        onStart: (id, data) => {
            actionInProgress = true;
            eventLog.push({ type: "interaction_start", ...data });
            m.redraw();
        },
        onProgress: (id, data) => {
            // Combat round, skill check, dialogue turn, etc.
            eventLog.push({ type: "interaction_round", ...data });
            m.redraw();
        },
        onComplete: (id, data) => {
            situation = data.situation;
            actionInProgress = false;
            eventLog.push({ type: "interaction_complete", ...data });
            m.redraw();
        },
        onInterrupt: (id, data) => {
            eventLog.push({ type: "interaction_interrupt", ...data });
            m.redraw();
        }
    });
}

// Consume - streaming
function consumeItem(itemId) {
    gameStream.executeAction("consume", {
        charId: playerId,
        itemId: itemId
    }, {
        onStart: (id, data) => { /* update UI */ },
        onProgress: (id, data) => {
            // Hunger/thirst bar animating
            situation.state.hunger = data.hunger;
            situation.state.thirst = data.thirst;
            m.redraw();
        },
        onComplete: (id, data) => {
            situation = data.situation;
            m.redraw();
        }
    });
}

// Investigate - streaming
function investigateLocation() {
    gameStream.executeAction("investigate", {
        charId: playerId
    }, {
        onProgress: (id, data) => {
            // Discovery events as they happen
            if (data.discovered) {
                eventLog.push({ type: "discovered", item: data.discovered });
            }
            m.redraw();
        },
        onComplete: (id, data) => {
            situation = data.situation;
            m.redraw();
        }
    });
}

// Generic pattern for ANY action
function executeGameAction(actionType, params) {
    return gameStream.executeAction(actionType, {
        charId: playerId,
        ...params
    }, {
        onStart: (id, data) => {
            actionInProgress = true;
            eventLog.push({ type: `${actionType}_start`, ...data });
            m.redraw();
        },
        onProgress: (id, data) => {
            eventLog.push({ type: `${actionType}_progress`, ...data });
            m.redraw();
        },
        onComplete: (id, data) => {
            situation = data.situation;
            actionInProgress = false;
            eventLog.push({ type: `${actionType}_complete`, ...data });
            m.redraw();
        },
        onError: (id, data) => {
            actionInProgress = false;
            page.toast("error", data.message);
            eventLog.push({ type: `${actionType}_error`, ...data });
            m.redraw();
        }
    });
}
```

---

## Server-Side Push Notifications

### Overwatch Integration

```java
// GameStreamHandler.java - Push notifications from Overwatch

public class GameStreamHandler {

    // Character → Session mapping for push notifications
    private static Map<Long, Session> characterSessions = new ConcurrentHashMap<>();

    // Subscribe character to push notifications
    public static void subscribe(Session session, long charId) {
        characterSessions.put(charId, session);
    }

    public static void unsubscribe(long charId) {
        characterSessions.remove(charId);
    }

    // Push notification to subscribed character
    public static void pushToCharacter(long charId, String eventType, Object data) {
        Session session = characterSessions.get(charId);
        if (session != null && session.isOpen()) {
            String[] chirps = new String[] {
                eventType,
                String.valueOf(charId),
                JSONUtil.exportObject(data)
            };
            WebSocketService.sendMessage(session, chirps, true, false, true);
        }
    }

    // Push to all characters in area
    public static void pushToArea(BaseRecord location, String eventType, Object data) {
        // Find all subscribed characters in this location
        for (Map.Entry<Long, Session> entry : characterSessions.entrySet()) {
            // Check if character is in affected area
            // Push if relevant
        }
    }
}
```

### Overwatch Event Hooks

```java
// Overwatch.java modifications

public ActionResultEnumType processOne(BaseRecord actionResult) {
    ActionResultEnumType status = action.executeAction(actionResult);

    // Push progress during execution
    if (status == ActionResultEnumType.IN_PROGRESS) {
        GameStreamHandler.pushActionProgress(actionResult);
    }

    status = action.concludeAction(actionResult);

    // Push completion
    GameStreamHandler.pushActionComplete(actionResult, status);

    // Push any resulting interactions
    for (BaseRecord interaction : getResultingInteractions(actionResult)) {
        GameStreamHandler.pushInteractionEvent(interaction);
    }

    return status;
}

// Hook into increment processing
private void endRealmIncrement() {
    // Push time advancement to all subscribed characters
    for (Long charId : GameStreamHandler.getSubscribedCharacters()) {
        GameStreamHandler.pushToCharacter(charId, "game.increment.end",
            getCurrentIncrementData());
    }
}

// Hook into threat detection
private void processProximity() {
    for (BaseRecord proximity : map.get(OverwatchEnumType.PROXIMITY)) {
        List<BaseRecord> threats = ThreatUtil.evaluateImminentThreats(proximity);
        for (BaseRecord threat : threats) {
            long charId = proximity.get("id");
            GameStreamHandler.pushToCharacter(charId, "game.threat.detected", threat);
        }
    }
}
```

### Event Types to Push

| Event Source | Chirp Type | Trigger |
|--------------|------------|---------|
| Action execution | `game.action.progress` | Every progress tick |
| Action completion | `game.action.complete` | `concludeAction()` returns |
| Action failure | `game.action.error` | Exception or failed status |
| Interaction start | `game.interaction.start` | `processInteractions()` |
| Interaction phase | `game.interaction.phase` | Each interaction step |
| Interaction end | `game.interaction.end` | Interaction resolved |
| Threat detection | `game.threat.detected` | `processProximity()` |
| Threat removal | `game.threat.removed` | Threat eliminated |
| NPC movement | `game.npc.moved` | NPC Walk action completes |
| NPC action | `game.npc.action` | Any NPC action completes |
| Time progression | `game.time.advanced` | `syncClocks()` |
| Increment end | `game.increment.end` | `endRealmIncrement()` |
| World event | `game.event.occurred` | `processEvents()` |
| State change | `game.state.update` | Any state modification |
| Situation change | `game.situation.update` | Significant situation change |

---

## Client Push Event Handling

### Subscription Setup

```javascript
// cardGame.js - Initialize push subscriptions

function initGameStream() {
    // Subscribe to push events for current character
    gameStream.subscribe(playerId);

    // Situation updates
    gameStream.subscriptions.onSituationUpdate = (charId, newSituation) => {
        if (charId === playerId) {
            situation = newSituation;
            m.redraw();
        }
    };

    // State updates (hunger, thirst, health, position)
    gameStream.subscriptions.onStateUpdate = (charId, statePatch) => {
        if (charId === playerId) {
            Object.assign(situation.state, statePatch);
            m.redraw();
        }
    };

    // Threat detection
    gameStream.subscriptions.onThreatDetected = (charId, threat) => {
        if (charId === playerId) {
            situation.threats.push(threat);
            eventLog.push({ type: "threat_detected", threat });
            page.toast("warn", `Threat detected: ${threat.name}`);
            m.redraw();
        }
    };

    // Threat removal
    gameStream.subscriptions.onThreatRemoved = (charId, threatId) => {
        if (charId === playerId) {
            situation.threats = situation.threats.filter(t => t.id !== threatId);
            eventLog.push({ type: "threat_removed", threatId });
            m.redraw();
        }
    };

    // NPC movement (for map awareness)
    gameStream.subscriptions.onNpcMoved = (npcId, from, to) => {
        // Update NPC position on map
        let npc = situation.nearbyPeople?.find(p => p.id === npcId);
        if (npc) {
            npc.position = to;
            m.redraw();
        }
    };

    // NPC actions
    gameStream.subscriptions.onNpcAction = (npcId, actionType, result) => {
        eventLog.push({ type: "npc_action", npcId, actionType, result });
        m.redraw();
    };

    // Time progression
    gameStream.subscriptions.onTimeAdvanced = (clockData) => {
        situation.clock = clockData;
        m.redraw();
    };

    // World events
    gameStream.subscriptions.onEventOccurred = (event) => {
        eventLog.push({ type: "world_event", event });
        page.toast("info", event.description);
        m.redraw();
    };

    // Interactions involving player
    gameStream.subscriptions.onInteractionStart = (interaction) => {
        if (interaction.participants.includes(playerId)) {
            eventLog.push({ type: "interaction_incoming", interaction });
            m.redraw();
        }
    };
}

// Cleanup on game exit
function cleanupGameStream() {
    gameStream.unsubscribe(playerId);
    gameStream.subscriptions = {};
}
```

### Message Router Update

```javascript
// pageClient.js - Enhanced message routing

function routeMessage(msg) {
    if (msg.chirps) {
        let c1 = msg.chirps[0] || "";

        // Chat messages
        if (c1.match(/^chat/i)) {
            routeChatMessage(c1, msg.chirps.slice(1));
        }
        // Audio messages
        else if (c1.match(/^audio/i)) {
            routeAudioMessage(c1, msg.chirps.slice(1));
        }
        // Game action messages
        else if (c1.match(/^game\.action\./)) {
            if (page.gameStream) {
                page.gameStream.routeMessage(c1, ...msg.chirps.slice(1));
            }
        }
        // Game push messages
        else if (c1.match(/^game\./)) {
            routeGamePushMessage(c1, msg.chirps.slice(1));
        }
    }
}

function routeGamePushMessage(type, args) {
    if (!page.gameStream) return;

    const handlers = {
        "game.situation.update": "onSituationUpdate",
        "game.state.update": "onStateUpdate",
        "game.threat.detected": "onThreatDetected",
        "game.threat.removed": "onThreatRemoved",
        "game.npc.moved": "onNpcMoved",
        "game.npc.action": "onNpcAction",
        "game.interaction.start": "onInteractionStart",
        "game.interaction.end": "onInteractionEnd",
        "game.time.advanced": "onTimeAdvanced",
        "game.increment.end": "onIncrementEnd",
        "game.event.occurred": "onEventOccurred"
    };

    const handler = handlers[type];
    if (handler && page.gameStream.subscriptions[handler]) {
        const data = args.map(a => {
            try { return JSON.parse(a); }
            catch { return a; }
        });
        page.gameStream.subscriptions[handler](...data);
    }
}
```

---

## Implementation Phases

### Phase 1: Infrastructure
**Files to create/modify:**

| File | Action | Purpose |
|------|--------|---------|
| `AccountManagerUx7/client/gameStream.js` | CREATE | Client-side game streaming module |
| `AccountManagerUx7/client/pageClient.js` | MODIFY | Add game message routing |
| `AccountManagerService7/.../sockets/WebSocketService.java` | MODIFY | Route "game" messages |
| `AccountManagerService7/.../sockets/GameStreamHandler.java` | CREATE | Handle game WebSocket messages |

### Phase 2: Action Streaming
**Convert REST actions to WebSocket streaming:**

| Action | Complexity | Notes |
|--------|------------|-------|
| `move` | Medium | Progress per meter moved |
| `moveTo` | Medium | Progress per cell crossed |
| `resolve` | High | Multi-phase resolution |
| `interact` | High | Combat rounds, dialogue turns |
| `consume` | Low | State changes |
| `investigate` | Low | Discovery events |
| `advance` | Low | Turn progression |
| `chat` | DEFERRED | Keep REST for now, implement after core actions |
| `claim/release` | Low | Confirmation only |
| `save/load` | Low | Progress bar |

### Phase 3: Overwatch Integration
**Add push notifications:**

| Source | Events |
|--------|--------|
| `Overwatch.processOne()` | Action progress, completion |
| `Overwatch.processProximity()` | Threat detection |
| `Overwatch.processInteractions()` | Interaction events |
| `Overwatch.syncClocks()` | Time progression |
| `Overwatch.endRealmIncrement()` | Increment transitions |
| `ThreatUtil` | Threat changes |
| `StateUtil` | State modifications |

### Phase 4: cardGame.js Integration
**Replace REST calls with streaming:**

| Function | Current | Streaming |
|----------|---------|-----------|
| `loadSituation()` | REST GET | Keep REST + subscribe to push |
| `moveCharacter()` | REST POST | `gameStream.executeAction("move")` |
| `resolveAction()` | REST POST | `gameStream.executeAction("resolve")` |
| `consumeItem()` | REST POST | `gameStream.executeAction("consume")` |
| `investigate()` | REST POST | `gameStream.executeAction("investigate")` |
| `interact()` | REST POST | `gameStream.executeAction("interact")` |
| `advanceTurn()` | REST POST | `gameStream.executeAction("advance")` |
| `gameChat()` | REST POST | DEFERRED - Keep REST for now |
| `saveGame()` | REST POST | Optional streaming |
| `loadGame()` | REST GET | Optional streaming |

---

## File Summary

### Files to Create

| File | Purpose |
|------|---------|
| `AccountManagerUx7/client/gameStream.js` | Client-side game streaming module |
| `AccountManagerService7/.../sockets/GameStreamHandler.java` | Server-side game message handler |
| `AccountManagerService7/.../sockets/IGameStreamListener.java` | Callback interface for game events |

### Files to Modify

| File | Changes |
|------|---------|
| `AccountManagerUx7/client/pageClient.js` | Add `routeGamePushMessage()`, expose `page.gameStream` |
| `AccountManagerUx7/client/view/cardGame.js` | Replace REST with `gameStream.executeAction()` |
| `AccountManagerService7/.../sockets/WebSocketService.java` | Route "game" messages to `GameStreamHandler` |
| `AccountManagerObjects7/.../olio/Overwatch.java` | Add push notification hooks |
| `AccountManagerObjects7/.../olio/GameUtil.java` | Add progress callback variants |
| `AccountManagerObjects7/.../olio/actions/Actions.java` | Add streaming support to action lifecycle |

---

## Benefits Summary

| Aspect | REST (Current) | WebSocket (Proposed) |
|--------|----------------|----------------------|
| **Action feedback** | None during execution | Real-time progress |
| **Combat visibility** | Final result only | Round-by-round |
| **Threat awareness** | Polling required | Instant push |
| **NPC awareness** | On situation refresh | Live updates |
| **Time progression** | Manual refresh | Automatic push |
| **Network efficiency** | Full payload each time | Incremental updates |
| **Cancellation** | Not possible | Supported |
| **Multiplayer ready** | Requires polling | Native push |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT (Browser)                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  cardGame.js                              gameStream.js              │
│  ┌─────────────────────┐                  ┌─────────────────────┐   │
│  │ moveCharacter()     │─────────────────▶│ executeAction()     │   │
│  │ resolveAction()     │                  │ activeActions Map   │   │
│  │ consumeItem()       │◀─────────────────│ subscriptions       │   │
│  │ investigate()       │  callbacks       │ routeMessage()      │   │
│  └─────────────────────┘                  └──────────┬──────────┘   │
│                                                       │              │
│  pageClient.js                                        │              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ page.wss.send()              routeMessage()                 │    │
│  │      │                            │                         │    │
│  │      │         WebSocket          │                         │    │
│  │      ▼         Connection         ▼                         │    │
│  └──────┼────────────────────────────┼─────────────────────────┘    │
│         │                            │                               │
└─────────┼────────────────────────────┼───────────────────────────────┘
          │                            │
          │    ws://server/wss         │
          │                            │
┌─────────┼────────────────────────────┼───────────────────────────────┐
│         │      SERVER                │                               │
├─────────┼────────────────────────────┼───────────────────────────────┤
│         ▼                            ▲                               │
│  WebSocketService.java               │                               │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ @OnMessage                        │                         │    │
│  │   └─▶ handleGameRequest() ────────┼─────────────────────┐   │    │
│  │                                   │                     │   │    │
│  │ sendMessage() ◀───────────────────┘                     │   │    │
│  └─────────────────────────────────────────────────────────┼───┘    │
│                                                             │        │
│  GameStreamHandler.java                                     │        │
│  ┌─────────────────────────────────────────────────────────▼───┐    │
│  │ handleAction(session, user, actionType, params)             │    │
│  │   ├─▶ move      → GameUtil.moveWithProgress(callback)       │    │
│  │   ├─▶ resolve   → Actions.executeWithProgress(callback)     │    │
│  │   ├─▶ interact  → InteractionUtil.resolveStreaming(cb)      │    │
│  │   ├─▶ consume   → GameUtil.consumeWithProgress(callback)    │    │
│  │   └─▶ ...                                                   │    │
│  │                                                             │    │
│  │ pushToCharacter(charId, eventType, data) ◀── Overwatch     │    │
│  │ subscribe(session, charId)                                  │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  Overwatch.java (World Simulation)                                   │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ process()                                                   │    │
│  │   ├─▶ processActions()    ──push──▶ game.action.*          │    │
│  │   ├─▶ processInteractions() ─push─▶ game.interaction.*     │    │
│  │   ├─▶ processProximity()  ──push──▶ game.threat.*          │    │
│  │   ├─▶ syncClocks()        ──push──▶ game.time.advanced     │    │
│  │   └─▶ processEvents()     ──push──▶ game.event.occurred    │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## Questions to Resolve

1. **Subscription scope**: Should push events be scoped to character, location, or realm?
2. **State sync granularity**: Full situation pushes vs. incremental patches?
3. **Action cancellation**: Support mid-action cancellation for all action types?
4. **Fallback behavior**: Keep REST endpoints functional for non-WebSocket clients?
5. **Rate limiting**: Throttle push notifications during high-activity periods?

## Deferred Items

- **Game chat streaming**: Keep REST-based for now, implement WebSocket streaming after core actions are complete

---

## Implementation Status

### Completed (Phase 1)

| File | Status | Description |
|------|--------|-------------|
| `AccountManagerUx7/client/gameStream.js` | CREATED | Client-side game streaming module with action callbacks and push subscriptions |
| `AccountManagerUx7/client/pageClient.js` | MODIFIED | Added game message routing in `routeMessage()`, added `page.gameStream` |
| `AccountManagerService7/.../sockets/WebSocketService.java` | MODIFIED | Added routing for "game" messages to `handleGameRequest()` |
| `AccountManagerService7/.../sockets/GameStreamHandler.java` | CREATED | Server-side game message handler with all action types |
| `AccountManagerUx7/client/view/cardGame.js` | MODIFIED | Added `USE_WEBSOCKET_STREAMING` flag, updated `moveCharacter()`, `resolveAction()`, `advanceTurn()` |
| `AccountManagerObjects7/.../olio/IGameEventHandler.java` | CREATED | Interface for game event callbacks |
| `AccountManagerObjects7/.../olio/GameEventNotifier.java` | CREATED | Singleton notifier for push events from Overwatch |

### Ready for Testing

The WebSocket streaming infrastructure is complete. To test:

1. Set `USE_WEBSOCKET_STREAMING = true` in cardGame.js (default)
2. Ensure WebSocket connection is established (check browser console)
3. Try movement, actions, and turn advancement
4. Check browser console for streaming callbacks

### Next Steps (Optional Enhancements)

1. **Wire Overwatch to GameEventNotifier**: Add calls to `GameEventNotifier.getInstance().notifyXxx()` in Overwatch processing loops
2. **Add progress callbacks to GameUtil**: Modify move/action methods to report intermediate progress
3. **Implement action cancellation**: Handle cancel requests in GameStreamHandler
