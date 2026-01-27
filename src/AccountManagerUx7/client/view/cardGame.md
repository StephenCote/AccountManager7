# Card Game Interface

This document describes the cardGame.js view component for the Olio simulation game interface.

## Overview

The card game interface provides a situation-driven game UI for interacting with Olio characters. It features spatial awareness, threat detection, and context-based actions.

## Architecture

### Three-Column Layout

```
+-------------------+-------------------+-------------------+
|  CHARACTER PANEL  |  SITUATION PANEL  |  THREAT/ACTION    |
|  (Left)           |  (Center)         |  PANEL (Right)    |
+-------------------+-------------------+-------------------+
```

- **Character Panel**: Player character info, portrait, needs bars, location
- **Situation Panel**: Narrative text, spatial grid, movement controls
- **Threat/Action Panel**: Threat radar, nearby people, available actions, event log

### State Management

```javascript
// Core game state
let player = null;           // Current player character (loaded via getFull)
let situation = null;        // Situation data from /rest/game/situation
let selectedEntity = null;   // Currently selected nearby entity
let selectedEntityType = null; // 'threat', 'person', 'poi'
let eventLog = [];           // Recent events

// Chat state
let chatDialogOpen = false;
let chatMessages = [];
let chatSession = null;

// Save game state
let currentSave = null;
let savedGames = [];
```

## Key Features

### Character Selection

On load, the game:
1. Queries `/Olio/Universes/My Grid Universe/Worlds/My Grid World/Population`
2. Uses `am7view.viewQuery()` with profile, store, statistics fields
3. Allows selecting a character to play as
4. Loads full character data via `am7client.getFull()`

### Movement System

Movement uses the `/rest/game/move/{characterId}` endpoint:

```javascript
// Client sends:
{ direction: "NORTH" }  // or SOUTH, EAST, WEST, NORTHEAST, etc.

// Optional distance:
{ direction: "EAST", distance: 2.0 }
```

Movement pad provides N/S/E/W directional buttons in the center panel.

### Situation Awareness

The `/rest/game/situation/{characterId}` endpoint returns:
- `character`: The character record
- `state`: Character's state record
- `location`: Current cell (`olio.locCell`) with terrainType, name (MGRS coord)
- `adjacentCells`: Flat list of nearby cells (use to build grid)
- `nearbyPeople`: Flat list of nearby characters
- `threats`: List of threat objects with `type`, `source`, `sourceName`, `isAnimal`, etc.
- `needs`: Object with `health`, `energy`, `hunger`, `thirst`, `fatigue` (0.0-1.0)

**Note:** The response uses flat lists, not nested `nearby.grid`. Client must convert `adjacentCells` to 2D grid using cell eastings/northings.

## Olio Action System (CRITICAL)

Understanding the action system is essential for making the game work correctly.

### Action Lifecycle

Actions have a strict three-phase lifecycle:

```
BEGIN  →  EXECUTE (cycles)  →  CONCLUDE
```

1. **BEGIN**: Creates ActionResult, calculates time cost, marks PENDING
2. **EXECUTE**: Performs action work per cycle, advances progress
3. **CONCLUDE**: Finalizes result (SUCCEEDED/FAILED), sets end time

### Action States

| State | Meaning | Can Start New Action? |
|-------|---------|----------------------|
| PENDING | Created, not yet executed | **NO** |
| IN_PROGRESS | Currently executing | **NO** |
| SUCCEEDED | Completed successfully | YES |
| FAILED | Encountered error | YES |
| INCOMPLETE | Auto-completed | YES |
| ABANDONED | Was abandoned | YES |

**CRITICAL**: Only ONE action per name per actor can be PENDING or IN_PROGRESS. Attempting to start a new action while one is in progress will fail with:
> "is already in the middle of a 'walk' action. Current action must be completed or abandoned."

### Action Time Cost

Time costs are calculated from actor stats:

```java
// Walk action example:
double mps = AnimalUtil.walkMetersPerSecond(actor);  // From actor's speed stat
long timeSeconds = (long)(distance / mps);
ActionUtil.edgeSecondsUntilEnd(actionResult, timeSeconds);
```

**Per-cycle cost** (during execute):
```java
long costMS = (1/mps) * 1000;  // Time to move one meter
```

### Key Action Methods

```java
// Start an action
BaseRecord actionResult = Actions.beginMove(ctx, epoch, actor, direction, distance);

// Execute it (call repeatedly or once depending on game mode)
boolean success = Actions.executeAction(ctx, actionResult);

// Finish it
ActionResultEnumType result = Actions.concludeAction(ctx, actionResult, actor, null);

// Check if action is in progress
boolean inAction = ActionUtil.isInAction(actor, "walk");

// Get existing in-progress action
BaseRecord currentAction = ActionUtil.getInAction(actor, "walk");
```

### GameUtil.moveCharacter Flow

The move endpoint calls `GameUtil.moveCharacter()` which does:
1. `Actions.beginMove()` - Setup action, calculate time
2. `Actions.executeAction()` - Perform movement
3. `Actions.concludeAction()` - Finalize result
4. `Queue.processQueue()` - Persist to database

If step 1 finds an existing PENDING/IN_PROGRESS action, it returns the existing action and logs a warning. The movement fails with "Movement failed - possibly blocked".

### Handling Pending Actions

**Option 1: Auto-complete** - Set `autocomplete=true` in beginAction to mark existing action as INCOMPLETE and start new one

**Option 2: Abandon** - Explicitly abandon the existing action:
```java
ActionUtil.setActionComplete(existingAction, ActionResultEnumType.ABANDONED);
```

**Option 3: Wait** - Check remaining time and wait for completion:
```java
long remaining = action.timeRemaining(actionResult, ChronoUnit.SECONDS);
```

### Available Actions

| Action | Class | Purpose |
|--------|-------|---------|
| walk | Walk.java | Move by direction + distance |
| walkTo | WalkTo.java | Move toward target |
| look | Look.java | Observe surroundings |
| consume | Consume.java | Eat/drink items |
| gather | Gather.java | Collect resources |
| peek | Peek.java | Observe specific target |

### Client Action Flow

```javascript
// 1. Check situation (may reveal if action is pending)
let situation = await loadSituation();

// 2. Attempt action via endpoint
try {
    let resp = await m.request({...});
} catch (e) {
    // Check for "already in action" error
    if (e.response && e.response.error.includes("already in")) {
        // Show "action in progress" to user
    }
}

// 3. Reload situation to get updated state
await loadSituation();
```

### UI Action System

UI actions are context-sensitive based on selection:

| Selection Type | Available Actions |
|----------------|-------------------|
| Threat | COMBAT, FLEE, WATCH |
| Person | TALK, BARTER, HELP, COMBAT |
| None | INVESTIGATE, WATCH |

Actions are executed via `/rest/game/resolve/{characterId}`:
```javascript
{ targetId: "uuid", actionType: "TALK" }
```

### Chat System

Chat uses game-specific endpoints:

```javascript
// Start chat - opens dialog with target character
POST /rest/game/chat
{ actorId: "player-uuid", targetId: "target-uuid", message: "text" }

// End chat
POST /rest/game/endChat
{ actorId: "player-uuid", targetId: "target-uuid", messageCount: 5 }
```

### Needs Display

Character needs are displayed as progress bars:
- **Health**: Red heart icon
- **Energy**: Yellow bolt icon
- **Hunger**: Orange restaurant icon (inverted - shows satiation)
- **Thirst**: Blue water drop icon (inverted)
- **Fatigue**: Purple bedtime icon (inverted)

### Location Display

Location info comes from `player.state.currentLocation`:
- Terrain type with icon
- Location name
- Climate info from situation

### Save/Load System

```javascript
// List saves
GET /rest/game/saves

// Save game
POST /rest/game/save
{ name: "Save Name", characterId: "uuid", eventLog: [...] }

// Load game
GET /rest/game/load/{saveId}
```

## CSS Classes (sg-* prefix)

The game uses custom CSS classes with `sg-` prefix:
- `sg-layout`, `sg-layout-full`: Main container
- `sg-panel`: Panel containers
- `sg-character-panel`, `sg-situation-panel`, `sg-action-panel`: Specific panels
- `sg-need-row`, `sg-need-bar`, `sg-need-fill`: Need bar components
- `sg-grid`, `sg-grid-row`, `sg-grid-cell`: Spatial grid
- `sg-move-pad`, `sg-move-btn`: Movement controls
- `sg-threat-card`, `sg-person-card`: Entity cards
- `sg-dialog-*`: Dialog components
- `sg-btn-*`: Button variants

## API Endpoints Used

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/rest/game/situation/{id}` | GET | Load current situation |
| `/rest/game/move/{id}` | POST | Move character |
| `/rest/game/resolve/{id}` | POST | Execute action |
| `/rest/game/investigate/{id}` | POST | Investigate area |
| `/rest/game/chat` | POST | Send chat message |
| `/rest/game/endChat` | POST | End conversation |
| `/rest/game/endTurn/{id}` | POST | Advance turn |
| `/rest/game/claim/{id}` | POST | Claim character as player |
| `/rest/game/saves` | GET | List saved games |
| `/rest/game/save` | POST | Save game |
| `/rest/game/load/{id}` | GET | Load saved game |
| `/rest/olio/charPerson/{id}/reimage/false` | GET | Regenerate portrait |

## Configuration

```javascript
let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";
const GRID_SIZE = 5;  // 5x5 grid display
```

## External Interface

The game exports functions for external control:

```javascript
window.am7cardGame = {
    setSelectedCharacter: function(charId) { ... },
    startNewGame: function() { ... }
};
```

## Route Parameters

The game accepts route parameters:
- `character`: Pre-select a character by objectId

Also checks `sessionStorage.olio_selected_character` for pre-selection.

## Differences from Original Card-Deck Version

**Note**: There was a previous implementation (`cardGame.js.original`) that used a completely different paradigm:

| Feature | Original (Card-Deck) | Current (Situation-Driven) |
|---------|---------------------|---------------------------|
| UI Metaphor | Card decks, hands, piles | Spatial grid, panels |
| Character View | Card with flip views | Panel with needs bars |
| Actions | Action cards in deck | Context-sensitive buttons |
| Items | Item deck, draw/play | Not implemented |
| Apparel | Apparel deck, draw/play | Not implemented |
| Chat | `/rest/chat/text` (generic) | `/rest/game/chat` (game-specific) |
| Movement | Not implemented | Grid-based with direction pad |
| Threats | Not implemented | Threat radar with priority |
| Drag/Drop | Extensive | None |

The original focused on **card game mechanics** (draw, play, discard).
The current focuses on **RPG-style spatial gameplay** (move, observe, interact).
