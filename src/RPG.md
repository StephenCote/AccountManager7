# AM7 Turn-Based RPG — Design & Build Plan

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Platform Recommendation](#platform-recommendation)
3. [Existing AM7 Capabilities Inventory](#existing-am7-capabilities-inventory)
4. [Current Design Shortcomings](#current-design-shortcomings)
5. [Intra-Cell Visual Grid System](#intra-cell-visual-grid-system)
6. [Asset Library Strategy](#asset-library-strategy)
7. [Game Design Document](#game-design-document)
8. [Technical Architecture](#technical-architecture)
9. [Implementation Phases](#implementation-phases)
10. [File Manifest](#file-manifest)

---

## Executive Summary

AccountManager7 already contains the core systems needed for a turn-based RPG: a deep character model with D&D-style statistics, alignment, and personality; an MGRS-inspired spatial coordinate system with terrain generation; an inventory/crafting/equipment system; an interaction and combat framework; a time/event simulation engine (Overwatch); and a working game client (cardGame.js) with WebSocket streaming. This plan extends those systems into a fully realized turn-based RPG with tile-based visual navigation, party management, quest progression, and public asset integration.

**What exists today (reuse):** ~85% of the backend model, service layer, and communication infrastructure — including needs-driven behavior, personality-based compatibility, social influence tracking, skill/combat resolution, and the tabletop-derived skill decay rules.
**What needs to be built:** Visual tile renderer, intra-cell sub-grid system, persistent reputation calculator, skill leveling/decay implementation, tile art pipeline, and a dedicated RPG game client. Most "missing" systems are extensions of existing infrastructure rather than greenfield work.

---

## Platform Recommendation

**Recommendation: Web-based (browser)**

The existing UX is a Mithril.js SPA with esbuild bundling, WebSocket real-time messaging, Canvas rendering (HypnoCanvas), and a REST client talking to the Java backend. A turn-based RPG with 2D pixel art on a grid is trivially within browser performance limits.

| Factor | Web (Browser) | Electron | Tauri |
|--------|--------------|----------|-------|
| Migration effort | None | High | High |
| Distribution | URL | Installer | Installer |
| Cross-platform | All (incl. mobile) | Desktop only | Desktop + mobile |
| 2D RPG performance | Excellent | Excellent | Excellent |
| Existing code reuse | 100% | ~95% | ~90% |
| Offline play | Service worker | Native | Native |
| Build complexity | Current (esbuild) | + Electron | + Rust toolchain |

**Rationale:** Every alternative adds complexity, build overhead, and distribution burden while providing zero performance benefit for a 2D turn-based game. The heavy computation (world simulation, AI, combat resolution) already runs on the Java backend. If offline play is ever needed, a service worker can be added without leaving the browser.

---

## Existing AM7 Capabilities Inventory

### Objects Layer (AccountManagerObjects7)

#### Character System — Ready to Use
| Capability | Model/Class | Status |
|-----------|-------------|--------|
| Character entity | `olio.charPerson` | Complete |
| Base statistics (16 attrs, 0-20 scale) | `olio.statistics` | Complete |
| Computed stats (willpower, reaction, magic, etc.) | `ComputeProvider` priority system | Complete |
| D&D alignment (9-point scale) | `AlignmentEnumType` | Complete |
| Big Five personality (OCEAN) | `identity.personality` | Complete |
| Instinct system (15 drives, -100 to +100) | `olio.instinct` + `InstinctEnumType` | Complete |
| Character roles (protagonist, antagonist, etc.) | `CharacterRoleEnumType` (18 types) | Complete |
| Race/species with fantasy types | `RaceEnumType` (Elf, Dwarf, Vampire, Robot, etc.) | Complete |
| Ethnicity system (40+ origins) | `EthnicityEnumType` | Complete |
| Narrative descriptions (multi-level) | `olio.narrative` | Complete |
| Character state (alive, awake, health, needs) | `olio.state` | Complete |
| Physiological needs (hunger, thirst, fatigue) | `olio.state` (0-1 scale) | Complete |
| SD image generation prompts | `olio.narrative` (sdPrompt, sdNegativePrompt) | Complete |

#### Spatial/Geography System — Ready to Use (with extension needed)
| Capability | Model/Class | Status |
|-----------|-------------|--------|
| MGRS coordinate hierarchy | `data.geoLocation` (GZD → Kident → Feature → Cell) | Complete |
| Terrain generation | `prepareMapGrid()` / `prepareK100()` / `prepareCells()` | Complete |
| 8-way directional movement | `DirectionEnumType` | Complete |
| Distance calculation | `GeoLocationUtil.getDistanceToState()` | Complete |
| Cell-level positioning (1m resolution) | `state.currentEast` / `state.currentNorth` (0-99) | Complete |
| Cell crossing with FK update | `StateUtil.moveByOneMeterInCell()` | Complete (bug fixed Jan 2026) |
| Points of interest | `olio.pointOfInterest` (12 types) | Complete |
| Terrain tile images | `/rest/game/tile/{terrain}` endpoint | Complete |
| **Intra-cell 10x10 visual sub-grid** | **Not implemented** | **Needed** |

#### Inventory & Economy — Ready to Use
| Capability | Model/Class | Status |
|-----------|-------------|--------|
| Item model with materials, perks, features | `olio.item` | Complete |
| Physical quality properties (20+ attrs) | `olio.quality` | Complete |
| Combat properties (offensive/defensive) | `olio.quality` (0-1 scale) | Complete |
| Apparel/clothing system | `olio.apparel` + `olio.wearable` | Complete |
| Body part equipment slots (30+ locations) | `WearLevelEnumType` body locations | Complete |
| Personal storage | `olio.store` | Complete |
| Inventory entries with quantity tracking | `olio.inventoryEntry` | Complete |
| Crafting/builder system | `olio.builder` (materials, skills, templates) | Complete |
| Price/value tracking | `olio.priceExt` | Complete |

#### Interaction & Combat — Ready to Use
| Capability | Model/Class | Status |
|-----------|-------------|--------|
| 37 interaction types | `InteractionEnumType` (combat, befriend, commerce, etc.) | Complete |
| 5-point outcome scale | `OutcomeEnumType` (very favorable → very unfavorable) | Complete |
| 26 threat types | `ThreatEnumType` | Complete |
| 37 motivation reasons | `ReasonEnumType` | Complete |
| Combat utility | `CombatUtil` | Complete |
| Interaction tracking (actor/interactor) | `olio.interaction` model | Complete |
| Action system with difficulty, duration, counters | `olio.action` + `olio.actionResult` | Complete |
| Needs satisfaction tracking (Maslow) | `NeedsUtil` | Complete |

#### World Simulation — Ready to Use
| Capability | Model/Class | Status |
|-----------|-------------|--------|
| World container with 40+ group types | `olio.world` | Complete |
| Realm management (geographic regions) | `olio.realm` | Complete |
| Time hierarchy (Epoch → Event → Increment) | `olio.event` + Clock system | Complete |
| Overwatch simulation engine | `Overwatch` (7 processing stages) | Complete |
| Schedule system (recurring events) | `olio.schedule` | Complete |
| Animal/creature model | `olio.animal` | Complete |
| Trait/skill system | `data.trait` | Complete |

### Service Layer (AccountManagerService7)

#### Game Endpoints — Ready to Use
| Endpoint | Purpose | Status |
|----------|---------|--------|
| `POST /rest/game/move/{id}` | Directional movement | Complete |
| `POST /rest/game/moveTo/{id}` | Coordinate movement | Complete |
| `GET /rest/game/situation/{id}` | Full situation report | Complete |
| `GET /rest/game/state/{id}` | Character state | Complete |
| `POST /rest/game/interact` | Character interaction | Complete |
| `POST /rest/game/resolve/{id}` | Action resolution with threat check | Complete |
| `POST /rest/game/investigate/{id}` | Perception-based discovery | Complete |
| `POST /rest/game/consume/{id}` | Item consumption | Complete |
| `POST /rest/game/advance` | Turn advancement | Complete |
| `POST /rest/game/chat` | NPC dialogue (LLM) | Complete |
| `POST /rest/game/concludeChat` | Chat evaluation | Complete |
| `GET /rest/game/chat/pending` | NPC-initiated chat polling | Complete |
| `POST /rest/game/outfit/generate` | Context-aware outfit generation | Complete |
| `POST /rest/game/claim/{id}` | Claim character for player | Complete |
| `POST /rest/game/save` / `load` / `saves` | Game persistence | Complete |
| `GET /rest/game/tile/{terrain}` | Terrain tile images | Complete |

#### Olio Endpoints — Ready to Use
| Endpoint | Purpose | Status |
|----------|---------|--------|
| `GET /rest/olio/roll` | Random character generation | Complete |
| `GET /rest/olio/profile/{id}` | Personality profile | Complete |
| `GET /rest/olio/compare/{id1}/{id2}` | Character comparison | Complete |
| `POST /rest/olio/{type}/{id}/narrate` | Narrative generation | Complete |
| `POST /rest/olio/{type}/{id}/reimage` | Portrait regeneration (SD) | Complete |
| `POST /rest/olio/landscape/{id}/reimage` | Landscape generation (SD) | Complete |

#### Infrastructure — Ready to Use
| Capability | Implementation | Status |
|-----------|---------------|--------|
| JWT authentication | `TokenFilter` + `AM7SigningKeyLocator` | Complete |
| PBAC authorization | `AccessPoint` | Complete |
| WebSocket real-time | `WebSocketService` at `/wss` | Complete |
| Game stream handler | `GameStreamHandler` (Phase 1 complete) | Complete |
| Generic CRUD API | `ModelService`, `ListService`, `PathService` | Complete |
| Media serving | `MediaServlet`, `ThumbnailServlet` | Complete |
| SD image generation | `SDUtil` integration | Complete |
| Voice synthesis/transcription | `VoiceService` | Complete |
| Vector embeddings | `VectorService` | Complete |

### UX Layer (AccountManagerUx7)

#### Game Client — Partial (Extend for RPG)
| Capability | Implementation | Status |
|-----------|---------------|--------|
| Three-panel game layout | `cardGame.js` (3,261 lines) | Complete |
| Character panel (stats, needs bars) | `cardGame.js` CharacterPanel | Complete |
| 10x10 area grid view | `cardGame.js` SituationPanel | Complete |
| Zoomed cell view (meter-level) | `cardGame.js` SituationPanel | Complete |
| Terrain rendering (PNG + emoji fallback) | `cardGame.js` grid renderer | Complete |
| Movement controls (d-pad + click) | `cardGame.js` movement system | Complete |
| Action system with distance thresholds | `cardGame.js` action panel | Complete |
| Chat/dialogue with LLM streaming | `cardGame.js` chat system | Complete |
| WebSocket game streaming | `gameStream.js` + `pageClient.js` routing | Complete (Phase 1) |
| Save/load system | `cardGame.js` persistence | Complete |
| Compass/direction system | `cardGame.js` 8-point compass | Complete |
| Event log | `cardGame.js` (max 50 events) | Complete |
| Mithril.js SPA framework | `am7client.js`, `pageClient.js` | Complete |
| Schema-driven model utilities | `modelDef.js`, `am7view.js` | Complete |

---

## Current Design Shortcomings

### 1. Intra-Cell Visual Resolution (Critical)

**Problem:** The current cell system provides 1m x 1m positioning within a 100m x 100m cell (`state.currentEast`/`state.currentNorth`, 0-99), but there is no visual sub-grid backing. The zoomed cell view in `cardGame.js` renders a 10x10 grid where each block represents 10m, but these blocks are not discrete data entities — they are purely visual divisions calculated on the fly. There is no way to assign specific terrain features, obstacles, props, or tile art to individual 10m blocks within a cell.

**Impact:** Cannot place trees, rocks, buildings, paths, or decorative elements at specific positions within a cell. The entire cell renders as a single terrain type. Navigation feels abstract — the player moves through an undifferentiated 100m square.

**Solution:** See [Intra-Cell Visual Grid System](#intra-cell-visual-grid-system) section below.

### 2. No Party/Group Management

**Problem:** The current game is strictly single-character. There is no concept of a player party, companion management, party formation, shared inventory, or group movement. The `community.group` model exists for population grouping but is not wired for tactical party mechanics.

**Impact:** Cannot implement party-based RPG gameplay (tank/healer/DPS roles, formation positioning, party inventory).

**Solution:** New `olio.party` model with member roster, formation, shared store, and turn ordering. Extend `GameService` with party endpoints.

### 3. No Quest/Objective System

**Problem:** No quest model, journal, objective tracking, quest givers, or reward system. The event system tracks world events but not player-directed objectives.

**Impact:** No narrative progression structure. The game is open-ended sandbox without directed goals.

**Solution:** New `olio.quest` and `olio.questObjective` models. Quest types: fetch, kill, escort, explore, dialogue, craft. Integrate with event system for trigger conditions.

### 4. No Dialogue Tree System

**Problem:** NPC dialogue is handled through free-form LLM chat. While powerful for open conversation, there is no structured dialogue tree for quest-giving, branching choices with gameplay consequences, or scripted story beats.

**Impact:** Cannot create authored narrative content with meaningful player choices that affect game state. LLM responses are unpredictable for critical story moments.

**Solution:** Hybrid system — structured dialogue trees (JSON-defined) for quest/story moments with fallback to LLM chat for ambient NPC conversation. Dialogue nodes can trigger state changes, give items, start quests, or alter alignment.

### 5. No Experience/Leveling System

**Problem:** Statistics are fixed at generation time (0-20 scale). There is no XP accumulation, leveling, stat point allocation, or skill progression. The `potential` field on statistics exists but is unused.

**Impact:** No character progression, which is fundamental to RPG design.

**Solution:** Use the existing `statistics.potential` field as unallocated stat points. Add XP tracking to `olio.state`. Define level thresholds and stat point grants per level. Wire XP rewards into interaction outcomes, quest completion, and exploration.

### 6. No Skill/Ability System

**Problem:** The `data.trait` model is used for character traits and item features, but there is no active ability system — no spells, special attacks, or activated skills with cooldowns, costs, or targeting.

**Impact:** Combat is limited to basic interaction types. No tactical depth from ability selection.

**Solution:** New `olio.ability` model extending `olio.action` with resource costs (energy/mana), cooldown tracking, targeting rules, area-of-effect definitions, and prerequisite statistics.

### 7. Limited Turn Structure

**Problem:** The `advance` endpoint progresses time by one increment (~1 hour), but there is no fine-grained turn system for tactical combat. Turns are coarse — the Overwatch engine processes all pending actions in bulk.

**Impact:** Cannot implement round-by-round tactical combat where turn order, initiative, and action economy matter.

**Solution:** Combat-mode turn manager that pauses Overwatch bulk processing and switches to initiative-ordered sequential turns. Derive initiative from `reaction` computed stat. Each turn allows one action + one movement.

### 8. No Fog of War / Visibility System

**Problem:** The `investigate` action reveals nearby entities based on perception, but there is no persistent fog of war. Previously explored areas are not tracked. The 10x10 area grid shows all terrain regardless of whether the player has visited those cells.

**Impact:** No exploration reward. No hidden areas. No surprise encounters.

**Solution:** Per-character visibility map stored as a bitfield on `olio.state`. Mark cells as explored when entered or observed. Unexplored cells render as black/fog. Perception stat determines observation radius.

### 9. No Map Tile Art Pipeline

**Problem:** Terrain rendering uses basic PNG tiles served from `/rest/game/tile/{terrain}` with emoji fallback. There is no support for tile variations, animated tiles, transition tiles between terrain types, or layered rendering (ground + objects + entities).

**Impact:** Maps look monotonous — every forest cell is the same forest tile. No visual variety or environmental storytelling.

**Solution:** Multi-layer tile renderer with: (1) base terrain tiles with variations per terrain type, (2) transition/edge tiles for terrain boundaries, (3) object layer for props/decorations mapped to the intra-cell sub-grid, (4) entity layer for characters/NPCs/monsters.

### 10. No Audio / Music System for Game

**Problem:** The Magic8 application has a full audio engine (binaural beats, TTS), but the card game has no audio. No background music, no sound effects for combat, movement, or UI interactions.

**Impact:** Silent gameplay reduces immersion.

**Solution:** Lightweight audio manager using Web Audio API. Context-aware music selection by terrain/situation. Sound effects for actions. Can reuse `AudioEngine` patterns from Magic8.

### 11. No Multiplayer Turn Coordination

**Problem:** The WebSocket infrastructure supports push notifications and the game stream handler exists, but there is no turn coordination for multiple players in the same realm. The Overwatch engine processes all characters simultaneously.

**Impact:** Cannot support cooperative or competitive multiplayer RPG sessions.

**Solution:** Deferred to Phase 4. The WebSocket push infrastructure (Phase 1 complete) provides the foundation. Add turn queue manager and player notification system.

---

## Intra-Cell Visual Grid System

### Current State

```
Hierarchy today:
GZD (30K) → Kident (100km²) → Feature/Cell (1km², type="feature")
    → Interior Cell (100m², type="cell", 10x10 grid within feature)
        → Meter position (currentEast/currentNorth 0-99 within cell)
```

The `prepareCells(feature)` method creates 10x10 interior cells (100m x 100m each) within a feature. Character position is tracked at 1m resolution via `state.currentEast`/`state.currentNorth`. The zoomed view in `cardGame.js` divides the current 100m cell into a 10x10 visual grid (each block = 10m), but these blocks have no backing data.

### Proposed Extension: Sub-Cell Tile Grid

Add a 10x10 sub-cell grid to each `data.geoLocation` record of type `cell`. Each sub-cell tile represents a 10m x 10m area and can hold:

- A tile type/variant for visual rendering
- An occupancy flag (passable/impassable)
- An optional reference to a decoration or prop
- An optional reference to a point of interest

#### Data Model Extension

**Option A — Packed field on geoLocation (Recommended)**

Add a `tileData` field to `data.geoLocation` containing a JSON-encoded 10x10 grid:

```json
{
  "tiles": [
    [{"t": "grass_1", "p": true}, {"t": "grass_2", "p": true}, ...],
    [{"t": "tree_oak", "p": false}, {"t": "grass_1", "p": true}, ...],
    ...
  ],
  "props": [
    {"x": 3, "y": 7, "type": "campfire", "id": "poi-123"},
    {"x": 5, "y": 2, "type": "chest", "id": "store-456"}
  ]
}
```

Where:
- `t` = tile asset key (maps to sprite in asset atlas)
- `p` = passable (boolean)
- `props` = positioned objects at specific sub-cell coordinates

**Rationale:** Avoids creating 100 new database records per cell. The 10x10 grid is a rendering concern — it needs fast bulk reads, not individual CRUD. A single JSON field on the existing cell record is efficient.

**Option B — New model type (Higher fidelity, higher cost)**

New `olio.subCell` model with eastings (0-9), northings (0-9), parent cell FK, tile type, passability, and optional POI reference. This creates 100 records per cell (10,000 per feature). Only viable if sub-cells need independent access control or complex relationships.

**Recommendation:** Option A. The sub-cell grid is a visual/navigation concern, not an authorization boundary. Packed JSON keeps the record count manageable and allows the entire grid to be loaded in a single fetch.

#### Position Mapping

```
Player position (currentEast=47, currentNorth=23) maps to:
  Sub-cell: (4, 2)     — integer division by 10
  Within sub-cell: (7, 3) — modulo 10

Movement from (47,23) to (48,23):
  Same sub-cell (4,2) → no visual tile change

Movement from (49,23) to (50,23):
  Sub-cell changes from (4,2) to (5,2) → visual tile transition
```

#### Generation Pipeline

When `prepareCells(feature)` creates a cell, also generate its `tileData`:

1. **Base fill:** Set all 100 tiles to the cell's terrain type with random variation (e.g., `grass_1`, `grass_2`, `grass_3`)
2. **Edge blending:** For cells on terrain boundaries, blend adjacent terrain types along edges
3. **Feature placement:** Place terrain-appropriate features (trees in forest, rocks in mountain, cacti in desert)
4. **Path carving:** If the cell is on a route between POIs, carve a path through the sub-grid
5. **POI integration:** Map existing `olio.pointOfInterest` entries (which already have `east`/`north` meter positions) to sub-cell coordinates by dividing by 10
6. **Passability:** Mark tiles with obstacles as impassable. Pathfinding in the zoomed view respects passability.

#### API Extension

New endpoint or extension to existing:

```
GET /rest/game/cellGrid/{cellObjectId}
→ Returns tileData JSON for the specified cell

POST /rest/game/cellGrid/generate/{cellObjectId}
→ Generates tileData for a cell (lazy generation on first visit)
```

Or, include `tileData` in the existing `/rest/game/situation/{charId}` response for the current cell (and optionally adjacent cells for smooth scrolling).

#### Client Rendering

Replace the flat-color 10x10 blocks in `cardGame.js` zoomed view with sprite-based rendering:

```javascript
// For each sub-cell tile:
// 1. Draw base terrain sprite from atlas
// 2. Draw prop/decoration sprite if present
// 3. Draw entity sprite if character/NPC/animal is in this sub-cell
// 4. Draw fog overlay if sub-cell is unexplored
```

Use HTML5 Canvas for sprite rendering (already proven with HypnoCanvas). Tile atlas loaded as a single spritesheet image; individual tiles drawn via `drawImage()` with source rectangle.

---

## Asset Library Strategy

### Primary Asset Pack: Ninja Adventure (CC0)

**Source:** https://pixel-boy.itch.io/ninja-adventure-asset-pack
**License:** CC0 (Public Domain — no attribution required)
**Why:** The most complete free RPG asset pack available. Single cohesive art style. Actively maintained.

**Included:**
- 50+ characters with walk/idle animations and facesets
- 30+ monsters with animations
- 9 bosses with animations
- Complete tilesets (floor autotiling, exterior/interior elements)
- 60+ items
- 50+ spell/action icons
- UI theme elements
- VFX/particle effects
- 100+ sound effects
- 37 music tracks
- 16x16 base tile size (scales cleanly to 32x32, 48x48, 64x64)

**Mapping to AM7 terrain types:**

| AM7 Terrain | Ninja Adventure Tileset | Notes |
|-------------|------------------------|-------|
| CLEAR / PLAINS | Overworld grass tiles | Base terrain |
| FOREST | Tree/foliage overlays on grass | Multiple tree variants |
| DESERT | Sand tiles | With cactus/rock props |
| MOUNTAIN | Rock/cliff tiles | Elevation transitions |
| MEADOW | Flower grass variants | Seasonal variations |
| LAKE | Water tiles (animated) | Shore transitions |
| MARSH | Swamp tiles | Murky water + vegetation |
| CAVE | Dungeon floor tiles | Wall/passage system |
| JUNGLE | Dense vegetation tiles | Layered canopy |
| GLACIER / TUNDRA | Snow/ice tiles | Frozen water transitions |
| VALLEY | Mixed grass/dirt paths | Rolling terrain |
| SAVANNA | Dry grass tiles | Sparse tree placement |

### Supplementary Asset Packs

| Pack | License | Purpose | Source |
|------|---------|---------|--------|
| **LPC Spritesheet Generator** | CC-BY-SA 3.0 | Deep character customization (body, hair, armor combos) | https://liberatedpixelcup.github.io/Universal-LPC-Spritesheet-Character-Generator/ |
| **Kenney UI Pack** | CC0 | Polished UI elements (buttons, panels, sliders) | https://kenney.nl/assets |
| **Shikashi's Fantasy Icons** | Free (attribution) | 284+ item/spell icons at 32x32 | https://shikashipx.itch.io/shikashis-fantasy-icons-pack |
| **DCSS Tiles** | CC0 | 3,000+ supplemental dungeon tiles and monsters | https://opengameart.org/content/dungeon-crawl-32x32-tiles |

### Asset Pipeline

```
Asset Source (PNG spritesheets)
    ↓
Build step: spritesheet → individual tile extraction + atlas JSON manifest
    ↓
Served via: /media/ endpoint (static files) or bundled with esbuild
    ↓
Client loads: atlas image + JSON manifest → Canvas drawImage() with source rects
```

**Tile Atlas Format:**
```json
{
  "tileSize": 32,
  "atlas": "terrain-atlas.png",
  "tiles": {
    "grass_1": {"x": 0, "y": 0},
    "grass_2": {"x": 32, "y": 0},
    "grass_3": {"x": 64, "y": 0},
    "tree_oak": {"x": 0, "y": 32},
    "tree_pine": {"x": 32, "y": 32},
    "rock_1": {"x": 64, "y": 32},
    "water_1": {"x": 0, "y": 64},
    "path_h": {"x": 32, "y": 64},
    "path_v": {"x": 64, "y": 64}
  },
  "animations": {
    "water_1": {"frames": [{"x": 0, "y": 64}, {"x": 96, "y": 64}], "speed": 500}
  }
}
```

---

## Game Design Document

### Core Loop

```
Explore → Encounter → Act → Resolve → Progress → Explore
   ↑                                        |
   └────────────────────────────────────────┘
```

1. **Explore:** Navigate the tile map. Discover POIs, NPCs, resources. Fog of war reveals as you move.
2. **Encounter:** Proximity triggers threats, NPC meetings, environmental events. Overwatch pushes alerts.
3. **Act:** Choose action — fight, talk, trade, flee, investigate, consume, craft, rest.
4. **Resolve:** Turn-based resolution. Combat uses initiative order. Dialogue uses LLM + structured trees. Outcomes modify state.
5. **Progress:** Gain XP, complete quest objectives, acquire items, improve relationships. Level up at thresholds.

### Character Creation

Leverage existing `GET /rest/olio/roll` with additions:

1. **Roll attributes** — Server generates random statistics (existing)
2. **Choose race** — Select from `RaceEnumType` (human, elf, dwarf, fairy, etc.)
3. **Choose alignment** — Pick from 9-point alignment grid (existing `AlignmentEnumType`)
4. **Customize appearance** — Use LPC Spritesheet Generator for visual; existing hair/eye color fields for data
5. **Allocate bonus points** — Distribute `statistics.potential` across attributes
6. **Generate portrait** — SD image generation (existing `/rest/olio/{type}/{id}/reimage`)
7. **Name & background** — Free text + narrative generation (existing `/rest/olio/{type}/{id}/narrate`)

### Party System

**Party Model (`olio.party`):**
- Leader (charPerson FK)
- Members (list of charPerson, max 4-6)
- Formation (enum: line, wedge, circle, scatter)
- Shared store (olio.store FK)
- Active (boolean)

**Mechanics:**
- Party moves together (leader position, members follow in formation)
- Combat: each member gets a turn per round, ordered by initiative (`reaction` stat)
- Shared inventory with leader approval for withdrawals
- Members can be dismissed or lost (death, desertion based on alignment/loyalty)
- Recruit NPCs through dialogue (BEFRIEND → ALLY → invite to party)

### Combat System

Extends existing `InteractionEnumType.COMBAT` and `CombatUtil`:

**Turn Order:**
1. Calculate initiative: `reaction` stat (AVG of agility, speed, wisdom, perception) + d20 roll
2. Sort all combatants (party + enemies) by initiative, descending
3. Each turn: one action + one movement (within sub-cell grid)

**Actions Available Per Turn:**
| Action | Cost | Description |
|--------|------|-------------|
| Attack (melee) | 1 action | Physical attack, uses `physicalStrength` + weapon offensive |
| Attack (ranged) | 1 action | Ranged attack, uses `manualDexterity` + weapon offensive |
| Cast ability | 1 action | Use learned ability, costs energy/mana |
| Defend | 1 action | +50% defensive until next turn |
| Use item | 1 action | Consume potion, throw item, etc. |
| Flee | 1 action + movement | Attempt escape, `speed` vs. enemy `speed` |
| Talk | 1 action | Attempt to de-escalate (charisma check) |

**Damage Calculation:**
```
Base damage = attacker.physicalStrength + weapon.offensive * 20
Defense = defender.physicalEndurance + armor.defensive * 20
Roll = random(1, 20)
Hit = (Roll + attacker.agility) > (10 + defender.agility)
Damage = max(0, baseDamage - defense + roll)
Health reduction = damage / maximumHealth (normalized to 0-1 scale)
```

**Outcome:**
- Health reaches 0 → incapacitated (existing `state.incapacitated`)
- All enemies incapacitated → VERY_FAVORABLE outcome
- Party flees → UNFAVORABLE outcome
- All party incapacitated → VERY_UNFAVORABLE (game over or rescue scenario)

### Quest System

**Quest Model (`olio.quest`):**
- name, description
- type (QuestTypeEnumType: FETCH, KILL, ESCORT, EXPLORE, DIALOGUE, CRAFT, DISCOVER)
- giver (charPerson FK — the quest NPC)
- state (QuestStateEnumType: AVAILABLE, ACTIVE, COMPLETED, FAILED, EXPIRED)
- objectives (list of olio.questObjective)
- rewards: XP (int), items (list of olio.item), alignment shift (int)
- prerequisites: minimum level, required quests completed, minimum alignment
- expiry (olio.event FK — optional time limit)
- location (data.geoLocation FK — where to find the quest giver)

**Quest Objective Model (`olio.questObjective`):**
- description
- type (ObjectiveTypeEnumType: COLLECT, DEFEAT, REACH_LOCATION, TALK_TO, CRAFT_ITEM, DISCOVER_POI)
- target (flex model — item type, NPC, location, POI)
- quantity (int — how many to collect/defeat)
- progress (int — current count)
- completed (boolean)
- optional (boolean — for bonus objectives)

**Quest Flow:**
1. Player approaches NPC with available quest (indicated by icon in sub-cell grid)
2. Structured dialogue tree presents quest details and accept/decline choice
3. On accept: quest state → ACTIVE, objectives appear in journal
4. Progress updates via Overwatch event hooks (kill enemy → check kill objectives, pick up item → check collect objectives)
5. On all required objectives complete: return to giver for reward
6. Reward: XP, items, alignment shift, potential follow-up quest unlock

### Dialogue System

**Hybrid approach:**

1. **Structured Trees** for quest/story moments:
```json
{
  "id": "blacksmith_quest_1",
  "npc": "Blacksmith Varga",
  "nodes": {
    "start": {
      "text": "My forge has gone cold. The iron shipment from the northern mines never arrived.",
      "options": [
        {"text": "I'll look into it.", "next": "accept", "action": "startQuest:iron_shipment"},
        {"text": "What's in it for me?", "next": "negotiate"},
        {"text": "Not my problem.", "next": "decline"}
      ]
    },
    "negotiate": {
      "text": "I can offer you a fine blade, forged by my own hand.",
      "options": [
        {"text": "Deal.", "next": "accept", "action": "startQuest:iron_shipment"},
        {"text": "I need more than that.", "next": "haggle", "check": {"stat": "charisma", "min": 12}}
      ]
    }
  }
}
```

2. **LLM Chat** for ambient conversation (existing system):
   - Free-form dialogue with any NPC
   - Personality-aware responses (existing personality/instinct/alignment data feeds into prompt)
   - Interaction outcomes evaluated by LLM (existing `concludeChat`)

3. **Transition:** Structured tree can hand off to LLM chat and vice versa. A dialogue node can set `"action": "startLLMChat"` to switch to free-form.

### Progression System

**Experience Points:**
| Source | XP Reward |
|--------|-----------|
| Combat victory | 10-100 based on enemy difficulty |
| Quest completion | 50-500 based on quest complexity |
| Exploration (new cell discovered) | 5 per cell |
| POI discovery | 20-50 based on POI type |
| Successful social interaction | 5-25 based on outcome |
| Crafting | 10-30 based on item complexity |

**Level Thresholds:**
| Level | Total XP | Stat Points Gained |
|-------|----------|--------------------|
| 1 | 0 | — (starting) |
| 2 | 100 | +2 |
| 3 | 300 | +2 |
| 4 | 600 | +2 |
| 5 | 1000 | +3 |
| 6 | 1500 | +3 |
| 7 | 2100 | +3 |
| 8 | 2800 | +4 |
| 9 | 3600 | +4 |
| 10 | 4500 | +5 |

Stat points are allocated by the player into any base statistic (capped at 20). Computed stats recalculate automatically via `ComputeProvider`.

### World Design

Leverage existing world generation:

```
World (olio.world)
└── Realm: "The Shattered Kingdoms" (olio.realm)
    ├── Region: Starting Village Kident (100km²)
    │   ├── Feature: Village Square (1km²) — MEADOW terrain
    │   │   └── 10x10 cells, each with 10x10 sub-grid tiles
    │   │       ├── POI: Blacksmith (COMMERCIAL)
    │   │       ├── POI: Inn (COMMERCIAL)
    │   │       ├── POI: Elder's Hall (LANDMARK)
    │   │       └── NPCs: villagers, merchants
    │   ├── Feature: Dark Forest (1km²) — FOREST terrain
    │   │   └── Threats: wolves, bandits
    │   ├── Feature: Old Mine (1km²) — MOUNTAIN terrain
    │   │   └── POI: Mine Entrance (RUIN), quest location
    │   └── Feature: River Crossing (1km²) — LAKE terrain
    │       └── POI: Bridge (STRUCTURE)
    ├── Region: Northern Wastes Kident
    │   └── Higher-level content, locked by quest progression
    └── Region: Sunken Ruins Kident
        └── End-game content
```

---

## Technical Architecture

### System Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     BROWSER (Client)                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  rpgGame.js (Main RPG Client)                                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ TileRenderer  │ │ PartyPanel   │ │ CombatManager            │ │
│  │ (Canvas 2D)   │ │              │ │ (turn queue, animations) │ │
│  ├──────────────┤ ├──────────────┤ ├──────────────────────────┤ │
│  │ AssetLoader   │ │ QuestJournal │ │ DialogueUI               │ │
│  │ (atlas mgr)   │ │              │ │ (trees + LLM chat)      │ │
│  ├──────────────┤ ├──────────────┤ ├──────────────────────────┤ │
│  │ FogOfWar      │ │ Inventory    │ │ AudioManager             │ │
│  │ (visibility)  │ │ (drag/drop)  │ │ (music + SFX)           │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
│                                                                   │
│  Shared: am7client.js, pageClient.js, gameStream.js, modelDef.js│
│                                                                   │
│  ◀──── WebSocket ────▶          ◀──── REST/HTTP ────▶            │
└─────────┬───────────────────────────────────┬───────────────────┘
          │                                   │
          │         ws://server/wss           │    /rest/*
          │                                   │
┌─────────┴───────────────────────────────────┴───────────────────┐
│                     JAVA SERVER                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  GameService.java (extended)                                     │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Existing: move, interact, chat, situation, save/load      │   │
│  │ New: party/*, quest/*, combat/turn, cellGrid/*            │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  GameStreamHandler.java (extended)                               │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Existing: action streaming, push events                    │   │
│  │ New: combat turn notifications, quest updates              │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  Overwatch.java (extended)                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Existing: 7-stage processing loop                          │   │
│  │ New: combat mode pause, quest trigger evaluation           │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  New modules:                                                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ CellGridUtil  │ │ QuestUtil    │ │ CombatTurnManager        │ │
│  │ (sub-grid gen)│ │ (quest mgr)  │ │ (initiative, turns)     │ │
│  ├──────────────┤ ├──────────────┤ ├──────────────────────────┤ │
│  │ PartyUtil     │ │ DialogueUtil │ │ ProgressionUtil          │ │
│  │ (party mgmt)  │ │ (tree eval)  │ │ (XP, leveling)         │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
│                                                                   │
│  Data Layer: AccountManagerObjects7 (existing + new models)      │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### New Data Models

```
olio.party
├── leader (charPerson FK)
├── members (list of charPerson, participation: party.member)
├── formation (FormationEnumType)
├── sharedStore (olio.store FK)
└── active (boolean)

olio.quest
├── name, description
├── type (QuestTypeEnumType)
├── giver (charPerson FK)
├── state (QuestStateEnumType)
├── objectives (list of olio.questObjective)
├── rewards: xp (int), items (list), alignmentShift (int)
├── prerequisites: level (int), quests (list), alignment (int)
├── expiry (olio.event FK)
└── location (data.geoLocation FK)

olio.questObjective
├── description
├── type (ObjectiveTypeEnumType)
├── target (flex model)
├── quantity, progress (int)
├── completed, optional (boolean)

olio.ability
├── inherits: olio.action
├── resourceCost (double, 0-1 of energy)
├── cooldownTurns (int)
├── targetType (TargetTypeEnumType: SELF, SINGLE, AREA, LINE)
├── range (int, sub-cell units)
├── areaSize (int, sub-cell radius)
├── prerequisiteStats (map of stat name → minimum value)
├── effects (list of olio.actionResult templates)
└── level (int, unlock level)
```

### New Enums

```
FormationEnumType: LINE, WEDGE, CIRCLE, SCATTER, COLUMN
QuestTypeEnumType: FETCH, KILL, ESCORT, EXPLORE, DIALOGUE, CRAFT, DISCOVER
QuestStateEnumType: AVAILABLE, ACTIVE, COMPLETED, FAILED, EXPIRED
ObjectiveTypeEnumType: COLLECT, DEFEAT, REACH_LOCATION, TALK_TO, CRAFT_ITEM, DISCOVER_POI
TargetTypeEnumType: SELF, SINGLE_ALLY, SINGLE_ENEMY, ALL_ALLIES, ALL_ENEMIES, AREA, LINE
```

### New REST Endpoints

```
Party:
  POST /rest/game/party/create          — Create party with leader
  POST /rest/game/party/invite/{charId} — Invite NPC to party
  POST /rest/game/party/dismiss/{charId}— Remove member
  GET  /rest/game/party/{partyId}       — Get party state
  POST /rest/game/party/formation       — Set formation

Quest:
  GET  /rest/game/quests/available      — List available quests near player
  POST /rest/game/quest/accept/{questId}— Accept quest
  GET  /rest/game/quest/journal         — Player's active/completed quests
  POST /rest/game/quest/abandon/{questId}— Abandon quest

Combat:
  POST /rest/game/combat/start          — Enter combat mode
  GET  /rest/game/combat/turnOrder      — Get initiative order
  POST /rest/game/combat/action         — Submit turn action
  POST /rest/game/combat/end            — Exit combat mode (flee/victory)

Cell Grid:
  GET  /rest/game/cellGrid/{cellId}     — Get sub-grid tile data
  POST /rest/game/cellGrid/generate/{cellId} — Generate sub-grid (lazy)

Progression:
  POST /rest/game/levelUp              — Allocate stat points on level up
  GET  /rest/game/abilities/{charId}   — List available abilities
  POST /rest/game/ability/learn/{abilityId} — Learn new ability

Dialogue:
  GET  /rest/game/dialogue/{npcId}     — Get dialogue tree for NPC
  POST /rest/game/dialogue/choose      — Select dialogue option
```

---

## Implementation Phases

### Phase 1: Foundation — Tile Rendering & Sub-Grid

**Goal:** Visual tile-based map with intra-cell sub-grid navigation.

**Backend:**
- Add `tileData` JSON field to `data.geoLocation` model (or dedicated sub-model)
- Create `CellGridUtil.java` — sub-grid generation from terrain type + POI data
- Add `/rest/game/cellGrid/*` endpoints to `GameService`
- Wire sub-grid generation into `prepareCells()` pipeline

**Frontend:**
- Create `rpgTileRenderer.js` — Canvas-based tile renderer
  - Load tile atlas (Ninja Adventure spritesheet)
  - Render 10x10 sub-grid with proper tile sprites
  - Support tile animations (water, fire)
  - Layer: ground → props → entities → fog
- Create `rpgAssetLoader.js` — spritesheet/atlas management
  - Parse atlas JSON manifest
  - Cache loaded atlases
  - Provide `drawTile(ctx, tileKey, x, y, size)` API
- Modify zoomed cell view rendering to use tile sprites instead of flat colors
- Add passability check to movement (cannot move into impassable sub-cells)

**Assets:**
- Download Ninja Adventure asset pack
- Extract terrain tilesets → build atlas PNG + JSON manifest
- Map AM7 terrain enum values to tile keys
- Create tile variation sets per terrain type (min 3 variants each)

**Deliverable:** Player can navigate a visually rich tile map with trees, rocks, water, and paths rendered at the sub-cell level.

### Phase 2: Combat & Party

**Goal:** Turn-based tactical combat and party management.

**Backend:**
- Create `CombatTurnManager.java` — initiative calculation, turn queue, action validation
- Create `PartyUtil.java` — party CRUD, formation positioning, shared inventory
- Add `olio.party` model + schema
- Add `olio.ability` model + schema
- Extend `GameService` with combat and party endpoints
- Modify Overwatch to pause bulk processing during active combat

**Frontend:**
- Create `rpgCombatUI.js` — combat overlay on tile map
  - Turn order display
  - Action selection panel (attack, defend, ability, item, flee)
  - Movement range highlighting on sub-grid
  - Attack range/area visualization
  - Damage numbers and health bar animations
  - Combat log panel
- Create `rpgPartyPanel.js` — party management sidebar
  - Member portraits and status
  - Formation editor (drag members to formation positions)
  - Quick-swap active character
- Add combat animations using Ninja Adventure VFX sprites

**Deliverable:** Party-based tactical combat on the tile grid with initiative order, abilities, and visual feedback.

### Phase 3: Quests & Dialogue

**Goal:** Structured quest system with hybrid dialogue.

**Backend:**
- Add `olio.quest` and `olio.questObjective` models + schemas
- Create `QuestUtil.java` — quest lifecycle, objective tracking, reward distribution
- Create `DialogueUtil.java` — dialogue tree evaluation, stat checks, action triggers
- Extend Overwatch with quest trigger hooks (kill/collect/explore events check quest objectives)
- Add quest endpoints to `GameService`
- Create initial quest content (JSON dialogue trees + quest definitions)

**Frontend:**
- Create `rpgQuestJournal.js` — quest list, objective tracking, completion status
- Create `rpgDialogueUI.js` — dialogue box with portrait, text, and choice buttons
  - Structured tree mode (scripted)
  - LLM chat mode (free-form, existing system)
  - Seamless transition between modes
- Add quest indicators to tile map (exclamation mark over quest givers, question mark for turn-in)
- Add objective HUD (current quest objective displayed on screen)

**Deliverable:** Players can accept quests from NPCs, track objectives, and experience branching dialogue with gameplay consequences.

### Phase 4: Progression, Polish & Audio

**Goal:** Character progression, fog of war, audio, and UX polish.

**Backend:**
- Create `ProgressionUtil.java` — XP tracking, level calculation, stat point allocation
- Add XP field to `olio.state`, level field derived from XP
- Wire XP rewards into combat outcomes, quest completion, exploration
- Add fog of war tracking (explored cells bitfield on state)
- Extend save/load to include quest state, party, explored map

**Frontend:**
- Create `rpgAudioManager.js` — background music + SFX
  - Terrain-based music selection (forest → peaceful, cave → tense)
  - Combat music trigger
  - SFX for: footstep, attack, spell, item pickup, level up, quest complete
  - Use Ninja Adventure sound/music assets
- Create `rpgFogOfWar.js` — visibility overlay
  - Unexplored cells: opaque black
  - Previously explored but out of range: 50% darkened
  - Currently visible (perception radius): full brightness
- Level-up UI (stat point allocation screen)
- Inventory UI improvements (drag-and-drop, equipment slots, item tooltips)
- Character sheet screen (full stat display with computed values)
- Mini-map overlay showing explored area

**Deliverable:** Complete RPG experience with progression, atmosphere, and polish.

### Phase 5 (Future): Multiplayer & Content

**Goal:** Cooperative multiplayer and expanded content.

- Turn coordination for multiple players in same realm
- Shared quest progress for party members
- PvP arena system
- Procedural quest generation using LLM
- Dungeon instances (instanced sub-realms)
- Trading system between players
- Achievement/title system
- World events (realm-wide, time-limited)

---

## File Manifest

### New Files to Create

**Backend (AccountManagerObjects7):**
| File | Purpose |
|------|---------|
| `src/main/resources/models/olio/partyModel.json` | Party schema |
| `src/main/resources/models/olio/questModel.json` | Quest schema |
| `src/main/resources/models/olio/questObjectiveModel.json` | Quest objective schema |
| `src/main/resources/models/olio/abilityModel.json` | Ability schema |
| `src/main/java/org/cote/accountmanager/olio/CellGridUtil.java` | Sub-grid generation |
| `src/main/java/org/cote/accountmanager/olio/CombatTurnManager.java` | Turn-based combat |
| `src/main/java/org/cote/accountmanager/olio/PartyUtil.java` | Party management |
| `src/main/java/org/cote/accountmanager/olio/QuestUtil.java` | Quest lifecycle |
| `src/main/java/org/cote/accountmanager/olio/DialogueUtil.java` | Dialogue tree evaluation |
| `src/main/java/org/cote/accountmanager/olio/ProgressionUtil.java` | XP and leveling |
| `src/main/java/org/cote/accountmanager/olio/schema/FormationEnumType.java` | Formation enum |
| `src/main/java/org/cote/accountmanager/olio/schema/QuestTypeEnumType.java` | Quest type enum |
| `src/main/java/org/cote/accountmanager/olio/schema/QuestStateEnumType.java` | Quest state enum |
| `src/main/java/org/cote/accountmanager/olio/schema/ObjectiveTypeEnumType.java` | Objective type enum |
| `src/main/java/org/cote/accountmanager/olio/schema/TargetTypeEnumType.java` | Ability target enum |

**Backend (AccountManagerService7):**
| File | Purpose |
|------|---------|
| Extensions to `GameService.java` | New REST endpoints for party, quest, combat, cellGrid, progression |

**Frontend (AccountManagerUx7):**
| File | Purpose |
|------|---------|
| `client/view/rpgGame.js` | Main RPG game view (new, extends cardGame patterns) |
| `client/view/rpg/rpgTileRenderer.js` | Canvas tile rendering engine |
| `client/view/rpg/rpgAssetLoader.js` | Spritesheet/atlas management |
| `client/view/rpg/rpgCombatUI.js` | Combat overlay and turn management |
| `client/view/rpg/rpgPartyPanel.js` | Party management sidebar |
| `client/view/rpg/rpgQuestJournal.js` | Quest tracking UI |
| `client/view/rpg/rpgDialogueUI.js` | Dialogue box (tree + LLM hybrid) |
| `client/view/rpg/rpgAudioManager.js` | Music and sound effects |
| `client/view/rpg/rpgFogOfWar.js` | Visibility overlay |
| `client/view/rpg/rpgInventoryUI.js` | Enhanced inventory with equipment slots |
| `client/view/rpg/rpgCharacterSheet.js` | Full character stats display |
| `client/view/rpg/rpgMiniMap.js` | Explored area mini-map |

**Assets:**
| Directory | Contents |
|-----------|----------|
| `AccountManagerUx7/assets/rpg/terrain/` | Terrain tile atlases |
| `AccountManagerUx7/assets/rpg/characters/` | Character spritesheets |
| `AccountManagerUx7/assets/rpg/monsters/` | Monster spritesheets |
| `AccountManagerUx7/assets/rpg/items/` | Item/equipment icons |
| `AccountManagerUx7/assets/rpg/ui/` | UI elements (panels, buttons, bars) |
| `AccountManagerUx7/assets/rpg/effects/` | Combat/spell VFX |
| `AccountManagerUx7/assets/rpg/audio/music/` | Background music tracks |
| `AccountManagerUx7/assets/rpg/audio/sfx/` | Sound effects |
| `AccountManagerUx7/assets/rpg/atlases/` | Generated atlas JSON manifests |

**Content:**
| File | Purpose |
|------|---------|
| `AccountManagerUx7/content/quests/` | Quest definition JSON files |
| `AccountManagerUx7/content/dialogues/` | Dialogue tree JSON files |
| `AccountManagerUx7/content/abilities/` | Ability definition data |

### Existing Files to Modify

| File | Changes |
|------|---------|
| `AccountManagerObjects7` model schemas | Add `tileData` field to geoLocation, XP/level to state |
| `AccountManagerObjects7` Overwatch.java | Combat mode pause, quest trigger hooks |
| `AccountManagerObjects7` GameUtil.java | Party-aware movement, combat turn support |
| `AccountManagerService7` GameService.java | New endpoints (party, quest, combat, cellGrid, progression) |
| `AccountManagerService7` GameStreamHandler.java | Combat turn push events, quest update events |
| `AccountManagerUx7` applicationRouter.js | Add `/rpg/:id` route |
| `AccountManagerUx7` modelDef.js | Add new model schemas (party, quest, ability) |
| `AccountManagerUx7` build.js / esbuild config | Include new RPG modules and asset directories |

---

## Appendix A: Terrain Tile Mapping Reference

| AM7 TerrainEnumType | Base Tiles (Ninja Adventure) | Props | Passability Pattern |
|---------------------|------------------------------|-------|---------------------|
| CLEAR | grass_1, grass_2, grass_3 | flowers, stones | 100% passable |
| PLAINS | grass_dry_1, grass_dry_2 | tall grass, hay bales | 95% passable |
| FOREST | grass + tree overlays | oak, pine, birch, stumps, mushrooms | 40-60% passable (trees block) |
| DESERT | sand_1, sand_2, sand_3 | cacti, bones, skulls, oasis | 90% passable |
| MOUNTAIN | rock_1, rock_2, cliff | boulders, ore veins, goat paths | 30-50% passable |
| MEADOW | grass_flower_1, _2, _3 | wildflowers, butterflies, pond | 95% passable |
| LAKE | water_1 (animated), shore | reeds, lily pads, dock | 20% passable (shore only) |
| MARSH | mud_1, mud_2, swamp_water | dead trees, fog, cattails | 60% passable (slow) |
| CAVE | stone_floor_1, _2 | stalagmites, torches, crystals | 50-70% passable |
| JUNGLE | grass_dark + dense vegetation | vines, ferns, ancient ruins | 30-50% passable |
| GLACIER | ice_1, ice_2, snow | icicles, frozen pools | 70% passable (slippery) |
| TUNDRA | snow_1, snow_2, frozen_grass | dead bushes, wolf tracks | 80% passable |
| VALLEY | mixed grass/dirt/path | bridges, fences, signposts | 85% passable |
| SAVANNA | dry_grass_1, _2, _3 | acacia trees, termite mounds | 90% passable |

## Appendix B: Asset License Summary

| Asset Pack | License | Attribution Required | Share-Alike | Commercial Use |
|-----------|---------|---------------------|-------------|----------------|
| Ninja Adventure | CC0 | No | No | Yes |
| Kenney UI Pack | CC0 | No | No | Yes |
| DCSS Tiles | CC0 | No | No | Yes |
| LPC Spritesheet Generator | CC-BY-SA 3.0 | Yes | Yes | Yes |
| Shikashi's Fantasy Icons | Custom free | Yes ("Matt Firth") | No | Yes |

## Appendix C: Coordinate System Quick Reference

```
World Coordinate Hierarchy:

GZD: "30K" (fixed)
  └── Kident: 100km x 100km grid square (e.g., "AB")
      └── Feature: 1km x 1km terrain cell
          Addressed by: eastings (0-99), northings (0-99) within kident
          └── Interior Cell: 100m x 100m
              Addressed by: eastings (0-9), northings (0-9) within feature
              └── [NEW] Sub-Cell Tile: 10m x 10m
                  Addressed by: index in tileData[row][col] (0-9, 0-9)
                  └── Meter Position: 1m x 1m (existing)
                      Addressed by: state.currentEast (0-99), state.currentNorth (0-99)

Mapping meter position to sub-cell tile:
  subCellX = floor(currentEast / 10)   // 0-9
  subCellY = floor(currentNorth / 10)  // 0-9
  meterWithinSubCell_X = currentEast % 10   // 0-9
  meterWithinSubCell_Y = currentNorth % 10  // 0-9
```
