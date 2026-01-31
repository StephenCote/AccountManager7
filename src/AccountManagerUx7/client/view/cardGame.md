# Card Game Interface

Situation-driven RPG UI for the Olio simulation. Spatial awareness, threat detection, context-based actions, LLM-powered character chat with interaction memory.

---

## Architecture

### Three-Column Layout

```
+-------------------+-------------------+-------------------+
|  CHARACTER PANEL  |  SITUATION PANEL  |  THREAT/ACTION    |
|  (Left)           |  (Center)         |  PANEL (Right)    |
+-------------------+-------------------+-------------------+
```

- **Character Panel**: Player portrait (with reimage), needs bars, location, terrain info
- **Situation Panel**: Narrative text, spatial grid (cell or zoomed meter view), movement pad
- **Threat/Action Panel**: Threat radar, nearby people (with portraits), available actions, event log

### State

```javascript
// Core
let player = null;              // Current player character (full record)
let situation = null;           // /rest/game/situation response
let selectedEntity = null;      // Selected nearby entity
let selectedEntityType = null;  // 'threat', 'person', 'poi'
let eventLog = [];

// Movement
let moveMode = false;           // Movement selection active
let zoomedView = false;         // Meter-level cell view vs cell grid
let pendingMove = null;         // Long-running move (walkTo) that can be aborted
let moveProgress = 0;           // Progress bar 0-100
let actionInProgress = false;   // Prevents concurrent actions

// Chat
let chatDialogOpen = false;
let chatMessages = [];          // [{role, content, time}, ...] + {role:'history', interactions:[]}
let chatSession = null;         // {actor, target, chatConfigId, chatRequestId, streamEnabled}
let chatStreaming = false;
const USE_CHAT_STREAMING = true;

// NPC chat requests
let pendingNpcChats = [];

// Reimage
let reimaging = {};             // {objectId: true} while generating

// Image tokens
let chatShowTagSelector = false;
let chatSelectedImageTags = [];

// Save/Load
let currentSave = null;
let savedGames = [];
```

---

## Chat System

### Discrete Chat Model

Each conversation is a **discrete event** — no multi-turn server-side memory. Context comes from:
1. **Interaction history** — previous interaction records (outcomes, descriptions) queried from the database and injected into both the LLM system prompt and the UI
2. **System prompt** — character identity, setting, relationship context built server-side in `GameUtil.chat()`

### Chat Flow

```
startChat(target)
  ├── Find or create chatConfig (from ~/Chat "Open Chat" template)
  ├── Create ChatRequest (for streaming)
  ├── Load interaction history → display in chat dialog
  └── Open dialog

sendChatMessage(text)
  ├── [streaming] WebSocket via page.wss.send("chat", ...)
  │     onchatstart → push empty assistant message
  │     onchatupdate → append text chunks
  │     onchatcomplete → clear stream state
  └── [REST fallback] POST /rest/game/chat
        → response.content extracted to chatMessages

endChat()
  ├── POST /rest/game/concludeChat (LLM evaluates conversation → creates interaction record)
  └── Close dialog, clear state
```

### ChatConfig Creation

New chatConfigs are built from the "Open Chat" template by copying only value fields (model, serverUrl, serviceType, apiKey, chatOptions values). This avoids cloning stale identity/organization references from the template's nested objects. Characters are assigned: target → systemCharacter (AI), player → userCharacter.

### Chat Token Processing

Messages are processed before display via `processChatContent()`:
- Strip `<think>` / `<thought>` tags
- Strip `--- CITATION ... END CITATIONS ---` blocks
- Strip `<|reserved_special_token...` markers
- Strip `[interrupted]`, `(Metrics...`, `(Reminder...`, `(KeyFrame...`
- Assistant messages rendered as markdown via `marked.parse()` + `m.trust()`

### Image Token Support

Users can insert `${image.tag1,tag2}` tokens into messages via a tag selector toggle. Uses `window.am7imageTokens` global (`.tags`, `.parse()`, `.resolve()`, `.cache`).

### Interaction History

When chat opens, the client fetches previous interactions via `POST /rest/game/interactions` and displays them as a compact history block at the top of the chat dialog. Each interaction shows:
- Outcome icon (green ↑ favorable, red ↓ unfavorable, gray ○ equilibrium)
- Interaction type and description
- Result state

The server also injects this history into the LLM system prompt via `GameUtil.getInteractionHistoryContext()` and into the streaming path via `ChatListener.sendMessageToServer()`.

### Chat Conclude / Interaction Creation

When the user ends a chat, `POST /rest/game/concludeChat` sends the conversation messages to the server. `GameUtil.concludeChat()` uses an LLM evaluation prompt (from the chatConfig's evalPromptConfig) to assess the conversation and create an `olio.interaction` record with outcomes stored in the database.

---

## Movement System

### Cell Movement

Arrow keys / WASD / direction pad move one full cell (100m):
```javascript
POST /rest/game/move/{characterId}
{ direction: "NORTH", distance: 100 }
```

Directions: `NORTH`, `SOUTH`, `EAST`, `WEST`, `NORTHEAST`, `NORTHWEST`, `SOUTHEAST`, `SOUTHWEST`

### Walk-To

Long-distance movement toward a target entity with progress bar:
```javascript
POST /rest/game/moveTo/{characterId}
{ targetId: "uuid" }
```

Shows animated progress bar during movement. Can be aborted, which sends an abandon request.

### Coordinate System

| Field | Meaning |
|-------|---------|
| `state.currentEast` / `state.currentNorth` | Meters within current cell (0–99) |
| `location.eastings` / `location.northings` | Cell grid coordinates (0–9) |

Each cell is 100m × 100m. Grid displays 5×5 cells centered on player.

### Cell View / Zoomed View

Toggle between cell grid view (5×5 cells, terrain tiles) and zoomed meter-level view of the current cell showing entity positions within the cell.

---

## Situation Awareness

`GET /rest/game/situation/{characterId}` returns:

| Field | Content |
|-------|---------|
| `character` | Player character record |
| `state` | Character state (position, epoch) |
| `location` | Current cell (terrainType, name/MGRS, eastings, northings) |
| `adjacentCells` | Flat list of nearby cells → built into grid |
| `nearbyPeople` | Flat list with profile portrait refs, sorted by distance |
| `threats` | Threat objects with type, source, sourceName, isAnimal, etc. |
| `needs` | health, energy, hunger, thirst, fatigue (0.0–1.0) |

---

## Action System

### Lifecycle

```
BEGIN  →  EXECUTE (cycles)  →  CONCLUDE
```

Only one action per name per actor can be PENDING or IN_PROGRESS.

### UI Actions

Context-sensitive based on selection:

| Selection | Actions |
|-----------|---------|
| Threat | COMBAT, FLEE, WATCH |
| Person | TALK, BARTER, HELP, COMBAT |
| None | INVESTIGATE, WATCH |

Executed via `POST /rest/game/resolve/{characterId}` with `{ targetId, actionType }`.

---

## Character Panel

- **Portrait**: Player image from profile.portrait, with reimage button (shows spinner overlay while generating via `GET /rest/olio/charPerson/{id}/reimage/false`)
- **Needs bars**: Health (red), Energy (yellow), Hunger (orange, inverted), Thirst (blue, inverted), Fatigue (purple, inverted) — 0.0 to 1.0 scale
- **Location**: Terrain icon + name + climate info

---

## Nearby People & Threats

- **Nearby list**: Profile portraits, name, age, gender, distance — sorted by distance (closest first), client-side sort after population cache merge
- **Threat radar**: Priority-ordered threat cards with source info, animal flag
- **Entity detail dialog**: Expanded view of selected entity with portrait, stats, and action buttons (Talk, Walk To, Reimage)

---

## NPC-Initiated Chat

Server can create chat requests that NPCs initiate. Client polls via `POST /rest/game/chat/pending` and shows notification badges. Player can accept/dismiss via `POST /rest/game/chat/resolve`.

---

## Save/Load System

```
GET  /rest/game/saves           → list saved games
POST /rest/game/save            → { name, characterId, eventLog }
GET  /rest/game/load/{saveId}   → restore game state
```

Auto-loads most recent save on game start.

---

## Reimage

Portrait regeneration for both player (character panel) and entities (detail dialog):
- Shows loading overlay with spinner and "Generating..." label
- Button disabled during generation
- Supports both `charPerson` and `animal` reimage endpoints

---

## API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/rest/game/situation/{id}` | GET | Load current situation |
| `/rest/game/move/{id}` | POST | Move character by direction/distance |
| `/rest/game/moveTo/{id}` | POST | Walk toward target entity |
| `/rest/game/resolve/{id}` | POST | Execute context action |
| `/rest/game/investigate/{id}` | POST | Investigate area |
| `/rest/game/advance` | POST | Advance game turn |
| `/rest/game/claim/{id}` | POST | Claim character as player |
| `/rest/game/chat` | POST | Send chat message (REST) |
| `/rest/game/concludeChat` | POST | End chat, create interaction via LLM eval |
| `/rest/game/interactions` | POST | Get interaction history between two characters |
| `/rest/game/chat/pending` | POST | Poll for NPC-initiated chat requests |
| `/rest/game/chat/resolve` | POST | Accept/dismiss NPC chat request |
| `/rest/game/saves` | GET | List saved games |
| `/rest/game/save` | POST | Save game |
| `/rest/game/load/{id}` | GET | Load saved game |
| `/rest/olio/charPerson/{id}/reimage/false` | GET | Regenerate character portrait |
| `/rest/olio/animal/{id}/reimage` | GET | Regenerate animal portrait |
| `/rest/chat/new` | POST | Create ChatRequest for streaming |

---

## CSS Classes

All game styles use the `sg-` prefix:

| Category | Classes |
|----------|---------|
| Layout | `sg-layout`, `sg-layout-full`, `sg-panel` |
| Panels | `sg-character-panel`, `sg-situation-panel`, `sg-action-panel` |
| Needs | `sg-need-row`, `sg-need-bar`, `sg-need-fill` |
| Grid | `sg-grid`, `sg-grid-row`, `sg-grid-cell` |
| Movement | `sg-move-pad`, `sg-move-btn` |
| Entities | `sg-threat-card`, `sg-person-card` |
| Dialogs | `sg-dialog`, `sg-dialog-overlay`, `sg-dialog-header`, `sg-dialog-close`, `sg-dialog-chat` |
| Buttons | `sg-btn`, `sg-btn-primary`, `sg-btn-small`, `sg-btn-icon` |
| Chat | `sg-chat-messages`, `sg-chat-msg`, `sg-chat-msg-row`, `sg-chat-row-user`, `sg-chat-avatar`, `sg-chat-role`, `sg-chat-content`, `sg-chat-input`, `sg-chat-system` |
| Chat history | `sg-chat-history`, `sg-chat-history-label`, `sg-chat-history-item`, `sg-chat-history-type`, `sg-chat-history-desc`, `sg-chat-history-state` |
| Chat extras | `sg-chat-tag-selector`, `sg-chat-pending-bar`, `sg-chat-pending-progress` |
| Reimage | `sg-reimage-overlay`, `sg-reimage-spinner`, `sg-reimage-label` |

---

## Configuration

```javascript
let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";
const GRID_SIZE = 5;
const USE_CHAT_STREAMING = true;
```

### External Interface

```javascript
window.am7cardGame = {
    setSelectedCharacter: function(charId) { ... },
    startNewGame: function() { ... }
};
```

### Route Parameters

- `character`: Pre-select by objectId
- Also checks `sessionStorage.olio_selected_character`

---

## Key Server-Side Classes

| Class | Role |
|-------|------|
| `GameUtil` | Core game logic — situation, chat, interactions, movement, save/load |
| `GameService` | REST endpoints delegating to GameUtil |
| `ChatListener` | WebSocket streaming — injects interaction history context before `chat.continueChat()` |
| `Chat` | LLM client — configured from chatConfig (serverUrl, model, serviceType, apiKey) |
| `ActionUtil` / `Actions` | Action lifecycle (begin, execute, conclude) |

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `[object Object]` in chat | REST `resp.response` is an object | Use `resp.getMessage().getContent()` on server; extract `.content` on client |
| Chat config encrypt error | Cloned template carries stale identity refs | Build new object with value fields only, not deep clone |
| Movement fails silently | Action already PENDING/IN_PROGRESS | Check/abandon existing action first |
| Terrain tiles show "?" | terrainType not in query response | Check `FULL_PLAN_FILTER` |
| Interactions not saving | `findCharacter()` used for chatConfig lookup | Use `findChatConfig()` instead |
| Chat LLM eval fails | `new Chat()` no-arg has null serverUrl | Use `new Chat(user, chatConfig, evalPromptConfig)` |
