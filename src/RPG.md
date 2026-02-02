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
10. [Test Strategy](#test-strategy)
11. [File Manifest](#file-manifest)

---

## Executive Summary

AccountManager7 already contains the core systems needed for a turn-based RPG: a deep character model with D&D-style statistics, alignment, and personality; an MGRS-inspired spatial coordinate system with terrain generation; an inventory/crafting/equipment system; an interaction and combat framework; a time/event simulation engine (Overwatch); and a working game client (cardGame.js) with WebSocket streaming. This plan extends those systems into a fully realized turn-based RPG with tile-based visual navigation, party management, quest progression, and public asset integration.

**Architecture Principle — REST-First:** The RPG client communicates with AM7 **exclusively through the REST API and WebSocket endpoints**. No direct model access, no embedded Java calls, no server-side rendering dependencies. Every game action — movement, combat, dialogue, inventory, party management — flows through the existing and extended `/rest/game/*` and `/rest/olio/*` endpoints. This strict API boundary means the client is a pure consumer of a well-defined service contract, making it trivial to swap, extend, or supplement with alternative frontends.

**Architecture Principle — App-Ready:** The RPG client is built as an **installable cross-platform App** targeting desktop, tablet, and mobile from a single codebase. The web-based implementation (PWA) provides native-app-like experience — installable from the browser, full-screen capable, responsive across screen sizes, and touch-optimized. Because all game logic runs server-side behind the REST API, the client is a lightweight rendering and input layer with no platform-specific dependencies.

**What exists today (reuse):** ~85% of the backend model, service layer, and communication infrastructure — including needs-driven behavior, personality-based compatibility, social influence tracking, skill/combat resolution, and the tabletop-derived skill decay rules. The REST API already exposes 25+ game endpoints covering movement, interaction, chat, situation reports, save/load, and asset serving.
**What needs to be built:** Visual tile renderer, intra-cell sub-grid system, persistent reputation calculator, skill leveling/decay implementation, tile art pipeline, responsive/adaptive UI layout, PWA shell, and a dedicated RPG game client. Most "missing" systems are extensions of existing infrastructure rather than greenfield work. New backend features are exposed as REST endpoints first, then consumed by the client — never wired directly.

---

## Platform Recommendation

**Recommendation: Progressive Web App (PWA) — App-ready for desktop, tablet, and mobile**

The RPG client is built as a **Progressive Web App** — a single codebase that installs and runs as a native-feeling App on any platform. Users launch it from a URL during development, then install it to their home screen or desktop for a full-screen, app-like experience. The existing UX is a Mithril.js SPA with esbuild bundling, WebSocket real-time messaging, Canvas rendering, and a REST client talking to the Java backend — all of which carry forward unchanged.

| Factor | PWA (Recommended) | Electron | Tauri | Native (iOS/Android) |
|--------|-------------------|----------|-------|----------------------|
| Migration effort | Low (add manifest + SW) | High | High | Very High |
| Distribution | URL + install prompt | Installer | Installer | App Store |
| Desktop support | Yes (installable) | Yes | Yes | No |
| Tablet support | Yes (responsive) | No | Partial | Yes |
| Mobile support | Yes (responsive + touch) | No | Yes | Yes |
| 2D RPG performance | Excellent | Excellent | Excellent | Excellent |
| Existing code reuse | 100% | ~95% | ~90% | ~30% |
| Offline play | Service worker | Native | Native | Native |
| Build complexity | Current (esbuild) | + Electron | + Rust toolchain | + Swift/Kotlin |
| REST API boundary | Clean (same origin) | Clean (localhost) | Clean (localhost) | Clean (remote) |

**Rationale:** The RPG client is a thin rendering and input layer — all game logic, world simulation, AI, and combat resolution run on the Java backend behind the REST API. This means the client has no platform-specific computational requirements. A PWA delivers:

- **Desktop:** Installable via Chrome/Edge "Install App" prompt. Runs in its own window, no browser chrome. Indistinguishable from a native desktop app.
- **Tablet:** Responsive layout adapts to landscape/portrait. Touch controls for movement and action selection. Add-to-home-screen for full-screen play.
- **Mobile:** Same responsive layout with compact UI mode. Touch d-pad for navigation. VATS overlay sized for thumb reach. Installable from Safari/Chrome.

Because the client communicates exclusively through REST + WebSocket, wrapping it in a native shell later (Capacitor, TWA, Electron) requires zero code changes — the PWA *is* the app, the shell is just a distribution mechanism. Build the PWA first; wrap it only if App Store distribution becomes a requirement.

**PWA Requirements:**
- `manifest.json` — app name, icons (192px, 512px), display: standalone, theme color, orientation: any
- Service worker — cache static assets (tile atlases, spritesheets, audio), network-first for REST API calls
- Responsive viewport meta tag — `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`
- Touch event handling — tap, swipe, pinch-to-zoom on tile map
- Orientation support — landscape preferred on mobile, both on tablet

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

## Current Design Shortcomings & Gaps

### 1. Intra-Cell Visual Resolution (Critical)

**Problem:** The current cell system provides 1m x 1m positioning within a 100m x 100m cell (`state.currentEast`/`state.currentNorth`, 0-99), but there is no visual sub-grid backing. The zoomed cell view in `cardGame.js` renders a 10x10 grid where each block represents 10m, but these blocks are not discrete data entities — they are purely visual divisions calculated on the fly. There is no way to assign specific terrain features, obstacles, props, or tile art to individual 10m blocks within a cell.

**Impact:** Cannot place trees, rocks, buildings, paths, or decorative elements at specific positions within a cell. The entire cell renders as a single terrain type. Navigation feels abstract — the player moves through an undifferentiated 100m square.

**Solution:** See [Intra-Cell Visual Grid System](#intra-cell-visual-grid-system) section below.

### 2. Persistent Char-to-Char Reputation Calculator (Extension Needed)

**What exists:**
- `ProfileComparison` — dynamic compatibility assessment between any two characters using MBTI compatibility, racial/romantic compatibility, charisma/intelligence/strength/wisdom margins, dark triad diffs (Machiavellianism, psychopathy, narcissism), wealth gap, and leadership contest
- `InteractionUtil.calculateSocialInfluence()` — updates `instinct.cooperate` and `instinct.resist` based on in-group/out-group interaction outcomes (+0.1 per favorable, -0.15 per conflict)
- `InteractionUtil.guessReasonToInteract()` — reconstructs relationship disposition dynamically from personality profiles + alignment + context each time
- `ProfileUtil.getProfile()` / `ProfileUtil.getGroupProfile()` — cached personality profiling with need analysis
- Interaction outcome history stored on `olio.actionResult` and `olio.interaction` records

**What's missing:** A unified **persistent reputation score** between specific character pairs. Today, relationships are reconstructed dynamically from personality and context — there is no cumulative reputation ledger that remembers "Character A helped Character B three times and betrayed them once." The cooperate/resist instincts capture group-level social influence but not pair-specific rapport.

**Solution:** Create a `ReputationUtil` that computes a composite reputation score from:
1. **Base compatibility** (from `ProfileComparison`): personality match, alignment margin, stat differentials, dark triad gaps
2. **Interaction history** (from stored `olio.interaction` records between the pair): count and weight of outcomes by type (FAVORABLE combat together = high trust, UNFAVORABLE betrayal = low trust)
3. **Instinct modifiers** (from `instinct.cooperate`/`instinct.resist`): current social disposition
4. **Alignment drift**: accumulated alignment shifts from interactions

The reputation score gates party recruitment (NPCs won't join hostile players), NPC dialogue disposition (LLM system prompt includes reputation context), trade pricing, quest availability, and betrayal probability. Party management is entirely reputation-driven — no explicit "invite" mechanic, but rather a reputation threshold that, when crossed, makes an NPC willing to follow.

### 3. Needs-Driven Objectives (Extend, Not Replace)

**What exists:**
- `NeedsUtil` implements Maslow's hierarchy: PHYSIOLOGICAL → SAFETY → LOVE → ESTEEM → SELF
- `PhysiologicalNeedsEnumType`: AIR, WATER, FOOD, SHELTER, SLEEP, CLOTHING, REPRODUCTION
- `SafetyNeedsEnumType`: SECURITY, EMPLOYMENT, RESOURCES, HEALTH, PROPERTY
- `LoveNeedsEnumType`: FRIENDSHIP, INTIMACY, FAMILY, CONNECTION
- `EsteemNeedsEnumType`: RESPECT, SELF_ESTEEM, STATUS, RECOGNITION, STRENGTH, FREEDOM
- `NeedsUtil.recommend()` evaluates group needs and generates action recommendations
- `ProfileUtil.analyzePhysiologicalNeeds()` checks inventory for food/water/clothing/shelter
- `GroupDynamicUtil.delegateActions()` assigns needs-based actions to individuals
- The survive/thrive distinction is implicit: lower-level needs (physiological, safety) = survive; higher-level needs (love, esteem, self) = thrive

**What's missing:** A player-facing objective tracker. The needs system drives NPC behavior but the player has no journal, progress indicators, or explicit goals. The needs themselves *are* the quest system — "find water," "build shelter," "earn respect" — but they need UI representation and milestone tracking.

**Solution:** Expose the needs assessment as a player-visible **Needs Journal** rather than a traditional quest log. Each unmet need becomes a trackable objective with progress indicators. Higher-level needs (ESTEEM, SELF) unlock as lower needs are satisfied, creating natural progression. Supplement with LLM-generated contextual objectives: the LLM receives the current needs assessment and generates narrative framing ("The village elder won't speak with a stranger — earn the trust of the community first").

### 4. Reputation-Gated LLM Dialogue (Extend, Not Replace)

**What exists:**
- Full LLM chat system with WebSocket streaming
- Personality-aware NPC responses (personality/instinct/alignment data feeds into system prompt)
- `concludeChat` evaluates interaction outcomes via LLM
- NPC-initiated chat requests with polling
- `ProfileComparison.compare()` generates detailed compatibility narrative

**What's missing:** Reputation context in the LLM prompt. Today, the LLM receives character personality data but not the cumulative relationship history between the player and this specific NPC.

**Solution:** Feed the reputation score (from #2) and recent interaction summary into the LLM system prompt. High reputation → NPC is cooperative, offers help, shares secrets. Low reputation → NPC is guarded, charges more, may refuse to engage. Extremely low → NPC is hostile, may initiate combat. No scripted dialogue trees needed — the LLM handles branching naturally when given proper reputation context. The `concludeChat` evaluation feeds back into the reputation ledger, creating a closed loop.

### 5. Skill Leveling & Decay (Implementation Needed — Design Exists)

**What exists (from `olio.txt` tabletop rules):**
- Skills are percentage-based (0-100%)
- Initial allocation cap: 50%
- Fae/magic skills cost 4x normal points
- **Decay formula:** `(100 - skill%) = days before 1% decay`
  - A skill at 70% decays after 30 days of disuse (1% per 30 days)
  - A skill at 90% decays after 10 days of disuse (1% per 10 days)
  - Higher skills decay faster — use it or lose it
- **Decay floor:** Skills do not decay below 60%
- `data.trait` model with `TraitEnumType.SKILL` and `policy.score` inheritance provides the storage structure
- `statistics.potential` (0-150) tracks unallocated attribute points
- `CombatUtil` already defines base skill percentages: `DEFAULT_FIGHT_SKILL = 50`, `DEFAULT_WEAPON_SKILL = 40`
- `CombatUtil.getFightSkill()`, `getDodgeSkill()`, `getParrySkill()` compute derived skill values from stats
- Skills capped at 95% maximum effectiveness

**What's missing:** Runtime implementation of skill leveling through use and decay over time. The `data.trait` records exist but don't track `lastUsedDate` or `proficiencyScore` as persistent fields.

**Solution:**
- Add `lastUsed` (zonetime) and `proficiency` (double, 0-100) fields to `data.trait` (or a `skill.score` extension)
- On successful skill use during action resolution: increase proficiency by `(100 - proficiency) * 0.05` (diminishing returns)
- During Overwatch time advancement: evaluate decay for all character skills based on the `(100 - skill%)` day formula
- Wire into `InteractionAction.executeAction()` — after resolution, update proficiency on the skills used (stat names from the interaction's `positiveStatistics` + any weapon/ability skills)
- Expose in UI: skill list with proficiency bars, decay warning indicators

### 6. Active Ability System (Extension of Skill + Action)

**What exists:**
- `olio.action` model with difficulty (0-20), duration, positiveStatistics/negativeStatistics, counterActions, requiredSkills
- `IAction` interface with full lifecycle: `beginAction → executeAction → concludeAction`
- `InteractionAction` maps interaction types to component actions with stat overrides and personality/instinct modifiers
- `CommonAction.applyActionEffects()` handles state/stat/instinct changes from outcomes
- 2d20 resolution system with stat + personality + instinct modifiers

**What's missing:** Active abilities that a player can select during action evaluation — spells, special attacks, or skills with resource costs and targeting.

**Solution:** Extend `olio.action` with ability-specific fields (resourceCost, cooldown, targetType, range, areaSize). Abilities are unlocked by skill proficiency thresholds (e.g., "Fireball" requires magic skill ≥ 60%). During VATS-style action selection (see #7), the player picks from available abilities. Resource cost deducted from `state.energy`. Cooldown tracked per-ability on `olio.state.actions`.

### 7. Hybrid Real-Time / Turn-Based Combat (VATS-Style)

**What exists:**
- Overwatch processes actions through a 7-stage loop: `prune → processInteractions → processActions → processGroup → processProximity → processTimedSchedules → processEvents → syncClocks`
- `Overwatch.processOne()` executes single actions: `executeAction → concludeAction → attach interactions → save`
- Actions resolve through the `IAction` lifecycle with time costs calculated by `calculateCostMS()`
- `InteractionAction.executeAction()` uses 2d20 rolls + stat/personality/instinct modifiers → OutcomeEnumType
- Threat detection via `processProximity()` can trigger interrupts
- `GameService.resolve()` already checks for threats before action execution

**Design clarification:** The system is **not** a traditional turn-based or real-time system — it's a **hybrid** like Fallout's VATS:
- The world runs in **real-time** through Overwatch's processing loop
- When the player encounters a decision point (threat detected, interaction initiated, proximity event), the game **pauses** for player input
- The player selects an action (fight, talk, flee, use ability, consume item) — this is the VATS moment
- The action is submitted to Overwatch, which resolves it through the normal `executeAction → concludeAction` pipeline
- Resolution includes the 2d20 roll, stat modifiers, personality modifiers, instinct modifiers, and outcome application
- After resolution, the world resumes real-time until the next decision point

**What's missing:** Client-side "pause and choose" UX during action evaluation. The backend flow already supports this — `GameService.resolve()` checks for interrupts, and actions queue through Overwatch. The gap is purely in the client presenting the action selection as a deliberate moment.

**Solution:** When a decision point occurs (threat detected, NPC proximity, etc.), the client enters a **VATS overlay** on the tile grid:
- Time display freezes (cosmetic — the server doesn't advance until the player acts)
- Available actions highlight based on context (combat actions if threat, social if NPC, survival if needs)
- Player selects action → submitted via `gameStream.executeAction()` → resolved by Overwatch → outcome streamed back
- Overlay dismisses, world resumes
- No separate "combat mode" needed — the Overwatch loop IS the combat engine, the VATS overlay is just the player's window into it

### 8. Fog of War / Visibility (Needs Solution)

**Problem:** The `investigate` action reveals nearby entities based on perception, but there is no persistent fog of war. Previously explored areas are not tracked. The 10x10 area grid shows all terrain regardless of whether the player has visited those cells.

**Impact:** No exploration reward. No hidden areas. No surprise encounters.

**Solution:** Per-character visibility map stored as a bitfield on `olio.state` (or a packed JSON field, similar to the intra-cell `tileData` approach). Mark cells as explored when entered or observed. Unexplored cells render as black/fog. Previously explored but out-of-range cells render darkened. Perception stat determines observation radius. The `investigate` action expands visibility range temporarily.

### 9. Map Tile Art Pipeline (Needs Solution)

**Problem:** Terrain rendering uses basic PNG tiles served from `/rest/game/tile/{terrain}` with emoji fallback. There is no support for tile variations, animated tiles, transition tiles between terrain types, or layered rendering (ground + objects + entities).

**Impact:** Maps look monotonous — every forest cell is the same forest tile. No visual variety or environmental storytelling.

**Solution:** Multi-layer tile renderer with: (1) base terrain tiles with variations per terrain type (min 3 per terrain from asset pack), (2) transition/edge tiles for terrain boundaries using autotile rules, (3) object layer for props/decorations mapped to the intra-cell sub-grid, (4) entity layer for characters/NPCs/monsters with sprite animations. See [Asset Library Strategy](#asset-library-strategy) for specific asset mapping.

### 10. Audio / Music System (Straightforward Addition)

**What exists:** Magic8 has a full `AudioEngine` (binaural beats, isochronic tones), `VoiceSequenceManager` (TTS), and the backend `VoiceService` supports synthesis/transcription. The Ninja Adventure asset pack includes 37 music tracks and 100+ sound effects.

**Solution:** Lightweight `rpgAudioManager.js` using Web Audio API, borrowing patterns from Magic8's `AudioEngine`. Terrain-keyed background music (forest → ambient, cave → tense, combat → battle track). SFX triggers on action resolution (attack hit/miss, item pickup, level up). Volume/mute toggle in game UI. Low implementation risk.

### 11. Multiplayer Turn Coordination (Deferred)

**Status:** Save for later. The WebSocket push infrastructure (Phase 1 complete via `GameStreamHandler`) provides the foundation. The Overwatch engine would need a player-notification gate in its processing loop to coordinate multiple players in the same realm. Not required for the initial RPG implementation.

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
Explore → Encounter → VATS Pause → Act → Resolve → Reputation/Skill Update → Explore
   ↑          ↑                                              |
   │    Overwatch pushes                                     │
   │    proximity/threat                                     │
   └─────────────────────────────────────────────────────────┘
```

1. **Explore:** Navigate the tile map in real-time (Overwatch runs). Discover POIs, NPCs, resources. Fog of war reveals as you move. Needs accumulate (hunger, thirst, fatigue).
2. **Encounter:** Overwatch's `processProximity()` detects threats, NPC meetings, environmental events. Push notification pauses for player input.
3. **VATS Pause:** Client enters action selection overlay. Available actions context-sensitive (combat if threat, social if NPC, survival if needs critical). World frozen until player commits.
4. **Act + Resolve:** Player submits action → Overwatch `executeAction()` → 2d20 roll + stat/personality/instinct modifiers → `OutcomeEnumType` → `applyActionEffects()` → state/inventory/instinct changes.
5. **Update:** Reputation ledger updated from interaction outcome. Skills used gain proficiency. Needs satisfied or worsened. Overwatch resumes.

### Character Creation

Leverage existing `GET /rest/olio/roll` with additions:

1. **Roll attributes** — Server generates random statistics (existing)
2. **Choose race** — Select from `RaceEnumType` (human, elf, dwarf, fairy, etc.)
3. **Choose alignment** — Pick from 9-point alignment grid (existing `AlignmentEnumType`)
4. **Customize appearance** — Use LPC Spritesheet Generator for visual; existing hair/eye color fields for data
5. **Allocate bonus points** — Distribute `statistics.potential` across attributes
6. **Generate portrait** — SD image generation (existing `/rest/olio/{type}/{id}/reimage`)
7. **Name & background** — Free text + narrative generation (existing `/rest/olio/{type}/{id}/narrate`)

### Party System (Reputation-Gated)

Party formation is entirely driven by the reputation system — there is no explicit "invite to party" button. NPCs organically join or leave based on their cumulative relationship with the player.

**Party Model (`olio.party`):**
- Leader (charPerson FK)
- Members (list of charPerson, max 4-6)
- Formation (enum: line, wedge, circle, scatter)
- Shared store (olio.store FK)
- Active (boolean)

**Reputation-Based Recruitment:**
| Reputation Level | NPC Disposition | Party Behavior |
|-----------------|-----------------|----------------|
| < -50 | Hostile | Will attack on sight |
| -50 to -10 | Guarded | Refuses interaction, charges premium |
| -10 to +10 | Neutral | Standard interaction, no follow |
| +10 to +30 | Friendly | Willing to trade favorably, share info |
| +30 to +60 | Allied | Willing to follow as party member |
| > +60 | Devoted | Loyal, will defend player, share secrets |

**Reputation Calculation (per pair):**
```
reputation(A, B) =
    baseCompatibility(ProfileComparison)        // -20 to +20
  + interactionHistory(A, B)                     // weighted sum of past outcomes
  + instinctModifier(A.cooperate - A.resist)     // -10 to +10
  + alignmentAffinity(A.alignment, B.alignment)  // -8 to +8
```

Where `interactionHistory` sums: VERY_FAVORABLE = +5, FAVORABLE = +2, EQUILIBRIUM = 0, UNFAVORABLE = -3, VERY_UNFAVORABLE = -7 per interaction. Weighted by recency (recent interactions count more).

**Mechanics:**
- Party moves together (leader position, members follow in formation)
- Each member acts during VATS pauses, ordered by `reaction` stat
- Shared inventory with leader control
- Members desert if reputation drops below +10 (checked during Overwatch increment)
- Members may betray if dark triad personality traits are high and reputation is borderline
- NPC-initiated join: when reputation crosses +30, NPC may request to follow (via `game/chat/pending`)

### Combat System (VATS-Style Hybrid)

Combat is not a separate mode — it happens within the Overwatch real-time loop. When a threat enters proximity, the client presents a VATS-style pause for the player to select their action. Resolution uses the existing `InteractionAction` pipeline.

**How it flows:**
1. `Overwatch.processProximity()` detects threat → push event `game.threat.detected`
2. Client enters VATS overlay: time display freezes, action options appear
3. Player selects action (attack, defend, flee, talk, use item, cast ability)
4. Action submitted → `Actions.beginAction()` → queued to Overwatch
5. `Overwatch.processOne()` → `InteractionAction.executeAction()`:
   - Get stat names for interaction type (e.g., COMBAT → fight/defend actions)
   - Calculate modifiers: stat modifier `(avg_stat - 10.0)` + personality modifier + instinct modifier
   - Roll 2d20: `actorScore = roll + statMod + personalityMod + instinctMod`
   - Resolve outcome: `actorScore - interactorScore` → OutcomeEnumType
     - ≥ 8.0 = VERY_FAVORABLE, ≥ 3.0 = FAVORABLE, ≥ -3.0 = EQUILIBRIUM, ≥ -8.0 = UNFAVORABLE, < -8.0 = VERY_UNFAVORABLE
6. `CommonAction.applyActionEffects()` → health/energy/instinct changes
7. Result streamed back → UI shows outcome → Overwatch resumes

**Existing damage model (from `olio.txt` tabletop rules):**
```
Hit roll: fight/weapon skill % (e.g., 75%)
Dodge: (agility + speed) / 2 * 5 = dodge skill %
Parry: fight/weapon skill against equal or lesser weapon class
  ParryModifier = skill% - attack%
  Minimum parry = ParrySkill - PM

Armor Damaging System (ADS):
  (a) Total armor hit points
  (b) Armor stress point (pierce/break threshold)
  (c) Armor absorption percentage
  (d) Effective attack skill = fight/weapon skill - absorption
  Below 5%: target AND armor damaged
  5% to absorption%: armor only damaged
  Above skill%: miss

Critical determination (additional roll):
  0-50%: Regular outcome
  51-85%: Double outcome
  86-95%: Triple/messed-up outcome
  96-00%: Deadly/total outcome
```

**Saving throws:** `((physicalStrength + physicalEndurance + willpower) / 3) * 5 = save%`

**After combat:** Skills used gain proficiency (fight skill, weapon skill, dodge). Reputation updated between combatants. Defeated enemies drop inventory items per their `olio.store`.

### Needs-Driven Objectives (Survive → Thrive)

The game does not use a traditional quest log with authored fetch/kill quests. Instead, objectives emerge organically from the Maslow-hierarchy needs system and reputation relationships.

**Needs Hierarchy (existing `NeedsUtil` + `AssessmentEnumType`):**

| Level | Need Category | Examples | Player Experience |
|-------|--------------|----------|-------------------|
| 1 (Survive) | PHYSIOLOGICAL | FOOD, WATER, SHELTER, SLEEP, CLOTHING | "Find water before nightfall" |
| 2 (Survive) | SAFETY | SECURITY, HEALTH, RESOURCES | "The wolves are circling — find a defensible position" |
| 3 (Thrive) | LOVE | FRIENDSHIP, INTIMACY, FAMILY, CONNECTION | "Earn the villagers' trust" (reputation threshold) |
| 4 (Thrive) | ESTEEM | RESPECT, STATUS, RECOGNITION, STRENGTH | "The elder will only speak to proven warriors" |
| 5 (Thrive) | SELF | Morality, creativity, self-actualization | Open-ended goals, LLM-narrated |

**How needs become objectives:**
1. `ProfileUtil.analyzePhysiologicalNeeds()` checks inventory/state → identifies unmet needs
2. `NeedsUtil.evaluateNeeds()` prioritizes across Maslow levels
3. Client receives needs assessment as part of situation update
4. **Needs Journal UI** displays unmet needs as trackable objectives with progress indicators:
   - "Find food" → progress bar based on food items in store
   - "Build shelter" → requires builder materials + skill check
   - "Earn friendship" → reputation score with nearest NPC community
5. Higher-level needs only surface when lower levels are satisfied (Maslow gate)
6. LLM generates contextual narrative framing for each need based on location, nearby NPCs, and reputation state

**LLM-Augmented Objective Generation:**
The LLM receives the player's current needs assessment + location + nearby NPC profiles + reputation scores and generates narrative context:
- Low food + forest terrain → "The berry bushes along the eastern ridge look promising, but something has been tracking you through the underbrush"
- Low friendship + village proximity → "The blacksmith eyes you warily. Perhaps if you dealt with the wolves threatening the village outskirts..."

This creates emergent quest-like experiences without authored content.

### Dialogue System (Reputation + LLM)

All dialogue is LLM-driven with reputation context — no scripted dialogue trees. The NPC's disposition, willingness to help, information shared, and trade terms are all functions of the reputation score.

**LLM System Prompt Construction:**
```
You are {npc.narrative.fullName}, a {npc.narrative.physicalDescription}.
Personality: {npc.narrative.mbtiDescription}, {npc.narrative.alignmentDescription}
Dark traits: {npc.narrative.darkTetradDescription}

Your relationship with {player.name}:
- Reputation score: {reputation(npc, player)} ({reputationTier})
- Recent interactions: {last 3 interaction summaries}
- Alignment compatibility: {AlignmentEnumType.margin(npc, player)}
- Personality compatibility: {MBTIUtil.getCompatibility(npc, player)}

Your current needs: {npc needs assessment}
Your current mood: {instinct-derived mood}

Respond in character. Your willingness to help, share information, or
trade favorably should reflect the reputation score:
- Below -10: You are hostile or dismissive
- -10 to +10: You are cautious and transactional
- +10 to +30: You are friendly and helpful
- Above +30: You are loyal and forthcoming with secrets
```

**Dialogue → Reputation Feedback Loop:**
1. Player initiates chat (existing `/rest/game/chat`)
2. LLM responds with reputation-appropriate disposition
3. Player converses freely (WebSocket streaming)
4. `concludeChat` evaluates interaction outcome via LLM → OutcomeEnumType
5. Outcome feeds back into reputation ledger
6. Next conversation reflects updated reputation

**Key advantage over scripted trees:** Every NPC is unique based on their generated personality, alignment, instincts, and accumulated relationship history. The same "quest" (e.g., dealing with wolves) might be presented differently by every NPC depending on their personality and rapport with the player.

### Progression System (Interaction-Driven Skill Leveling + Decay)

Progression is not XP-based — it is **skill-based through use**. Characters improve at what they do and atrophy at what they neglect. This comes directly from the tabletop rules in `olio.txt`.

**Skill Leveling (through use):**
- Every action resolution identifies the skills involved (via `InteractionAction`'s stat override mapping + action's `positiveStatistics`)
- On FAVORABLE or VERY_FAVORABLE outcome: skill proficiency increases by `(100 - currentProficiency) * gainRate`
  - `gainRate` = 0.05 base, modified by intelligence (higher INT = faster learning)
  - Diminishing returns: a 90% skill improves slower than a 30% skill
- On EQUILIBRIUM: minor skill gain (half rate)
- On UNFAVORABLE/VERY_UNFAVORABLE: no gain (you failed, you didn't learn)
- Skills cannot exceed 95% (existing `CombatUtil` cap)
- Initial allocation cap: 50% at character creation

**Skill Decay (from disuse — `olio.txt` rules):**
```
Days before 1% decay = (100 - skill%)

Examples:
  Skill at 95%: decays 1% every 5 days of disuse
  Skill at 80%: decays 1% every 20 days
  Skill at 70%: decays 1% every 30 days
  Skill at 65%: decays 1% every 35 days
  Skill at 60%: STOPS DECAYING (floor)

Fae/magic skills: cost 4x to learn, same decay rules
```

Decay is evaluated during Overwatch time advancement (`syncClocks`). Each skill's `lastUsed` timestamp is compared against game time. If elapsed days exceed `(100 - proficiency)`, the skill loses 1% and the timer resets.

**Attribute Growth:**
- `statistics.potential` (0-150) holds unallocated attribute points from character creation
- Additional attribute points come from milestone achievements (not XP thresholds):
  - First time reaching a new Maslow level (survive → thrive) = +2 attribute points
  - First time entering a new region (kident) = +1 attribute point
  - Reaching reputation +60 with any NPC = +1 attribute point
  - Each combat survival against a superior opponent = +1 attribute point
- Attribute points allocated by player into base statistics (capped at 20)
- Computed stats (`willpower`, `reaction`, `magic`, etc.) recalculate automatically via `ComputeProvider`

**Skill ↔ Stat Relationship:**
Skills are percentage-based proficiencies. Stats are 0-20 attributes. Both contribute to action resolution:
- Stat modifier: `(avg_relevant_stats - 10.0)` → contributes to the 2d20 roll
- Skill check: `roll under skill%` → determines hit/success for specific actions
- Higher stats make skill *usage* more effective; higher skills make *specific actions* more reliable

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

### REST-First Architecture Principle

The RPG client treats the AM7 backend as a **remote service accessed exclusively through its REST API and WebSocket endpoints**. This is a hard boundary, not a guideline:

**Client ONLY uses:**
- `REST /rest/game/*` — all game actions (move, interact, chat, save/load, situation, party, reputation, skills, cellGrid, needs)
- `REST /rest/olio/*` — character generation, profiles, comparison, narration, image generation
- `REST /rest/resource/*`, `/rest/list/*`, `/rest/path/*` — generic model CRUD when needed
- `REST /media/*`, `/thumbnail/*` — asset and image serving
- `WebSocket /wss` — real-time push events (VATS triggers, reputation updates, chat streaming, game state sync)

**Client NEVER:**
- Imports or references Java classes, model definitions, or backend utilities directly
- Manipulates database records or AM7 object graph outside REST calls
- Implements game logic that belongs on the server (combat resolution, reputation calculation, skill decay, needs evaluation)
- Caches authoritative state — the server is the source of truth; the client renders and sends input

**Why this matters:**
1. **App-ready:** Any client that speaks REST + WebSocket can play the game — browser, PWA, native iOS/Android wrapper, desktop Electron shell, or a future Unity/Godot frontend. The API contract is the product.
2. **Testable:** Backend endpoints are tested independently (Tier 3 tests). Client code is tested against mock REST responses. No integration coupling.
3. **Deployable:** Client and server can be deployed, scaled, and updated independently. The client is a static asset bundle; the server is a Java WAR.
4. **Secure:** PBAC authorization enforced at the REST layer. No client-side bypass possible.

### System Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│              APP CLIENT (Desktop / Tablet / Mobile)                │
│              PWA — installable, responsive, touch-ready           │
│              Communicates ONLY via REST API + WebSocket            │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  rpgGame.js (Main RPG Client)                                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ TileRenderer  │ │ PartyPanel   │ │ CombatManager            │ │
│  │ (Canvas 2D)   │ │              │ │ (VATS overlay, anims)    │ │
│  ├──────────────┤ ├──────────────┤ ├──────────────────────────┤ │
│  │ AssetLoader   │ │ NeedsJournal │ │ DialogueUI               │ │
│  │ (atlas mgr)   │ │              │ │ (LLM chat streaming)    │ │
│  ├──────────────┤ ├──────────────┤ ├──────────────────────────┤ │
│  │ FogOfWar      │ │ Inventory    │ │ AudioManager             │ │
│  │ (visibility)  │ │ (touch drag) │ │ (music + SFX)           │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
│                                                                   │
│  Responsive Layout Engine (desktop / tablet / mobile breakpoints)│
│  Touch Input Manager (tap, swipe, d-pad, pinch-zoom)             │
│  Service Worker (asset caching, offline fallback)                 │
│                                                                   │
│  Shared: am7client.js, pageClient.js, gameStream.js, modelDef.js│
│                                                                   │
│  ═══════════════════ REST API BOUNDARY ══════════════════════    │
│  ◀──── WebSocket /wss ────▶      ◀──── REST /rest/* ────▶       │
└─────────┬───────────────────────────────────────────┬───────────┘
          │  Push: VATS triggers,                     │
          │  reputation updates,                      │  Request/Response:
          │  chat streaming,                          │  all game actions,
          │  state sync                               │  situation, party, etc.
          │                                           │
┌─────────┴───────────────────────────────────────────┴───────────┐
│                     AM7 JAVA SERVER                               │
│                     (All game logic lives here)                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  REST Endpoints (GameService.java — the client's sole interface)  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Existing: move, moveTo, interact, resolve, chat, state    │   │
│  │           situation, save, load, investigate, consume     │   │
│  │ New: party/*, reputation/*, cellGrid/*, skills/*, needs/* │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  GameStreamHandler.java (extended)                               │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Existing: action streaming, push events                    │   │
│  │ New: VATS pause notifications, reputation updates          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  Overwatch.java (extended)                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Existing: 7-stage processing loop                          │   │
│  │ New: skill decay eval, reputation triggers, VATS pause     │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                   │
│  New modules:                                                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ CellGridUtil  │ │ ReputationUtil│ │ SkillProgressionUtil    │ │
│  │ (sub-grid gen)│ │ (pair rep calc)│ │ (leveling + decay)    │ │
│  ├──────────────┤ ├──────────────┤ ├──────────────────────────┤ │
│  │ PartyUtil     │ │ VATSUtil     │ │ NeedsJournalUtil        │ │
│  │ (rep-gated)   │ │ (pause/resume)│ │ (objective tracking)   │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
│                                                                   │
│  Auth: JWT (TokenFilter) + PBAC (AccessPoint) at REST boundary   │
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

olio.reputation (NEW — persistent pair reputation)
├── actor (charPerson FK)
├── target (charPerson FK, flex model to support animals)
├── score (double, -100 to +100)
├── interactionCount (int)
├── lastInteraction (zonetime)
├── lastOutcome (OutcomeEnumType)
└── history (list of olio.interaction, participation: reputation.interaction)

olio.ability (extends olio.action)
├── resourceCost (double, 0-1 of energy)
├── cooldownTurns (int)
├── targetType (TargetTypeEnumType: SELF, SINGLE, AREA, LINE)
├── range (int, sub-cell units)
├── areaSize (int, sub-cell radius)
├── prerequisiteSkillProficiency (double, 0-100)
├── effects (list of olio.actionResult templates)

Extensions to existing models:
  data.trait (add fields):
  ├── proficiency (double, 0-100)  — current skill proficiency %
  ├── lastUsed (zonetime)          — for decay calculation
  └── usageCount (int)             — total times used

  olio.state (add fields):
  ├── exploredCells (string)       — packed bitfield of explored cell IDs
  └── milestones (list of string)  — milestone keys for attribute point grants
```

### New Enums

```
FormationEnumType: LINE, WEDGE, CIRCLE, SCATTER, COLUMN
TargetTypeEnumType: SELF, SINGLE_ALLY, SINGLE_ENEMY, ALL_ALLIES, ALL_ENEMIES, AREA, LINE
ReputationTierEnumType: HOSTILE, GUARDED, NEUTRAL, FRIENDLY, ALLIED, DEVOTED
```

### New REST Endpoints

```
Party (reputation-gated):
  GET  /rest/game/party/{partyId}       — Get party state + member reputations
  POST /rest/game/party/formation       — Set formation
  POST /rest/game/party/dismiss/{charId}— Dismiss member (reputation drops)

Reputation:
  GET  /rest/game/reputation/{charId}/{targetId} — Get pair reputation
  GET  /rest/game/reputation/{charId}/nearby      — Reputations with all nearby NPCs
  GET  /rest/game/reputation/{charId}/party        — Reputations with party members

Cell Grid:
  GET  /rest/game/cellGrid/{cellId}     — Get sub-grid tile data
  POST /rest/game/cellGrid/generate/{cellId} — Generate sub-grid (lazy)

Skills:
  GET  /rest/game/skills/{charId}       — List all skills with proficiency + decay status
  POST /rest/game/allocatePoints        — Allocate attribute points from potential
  GET  /rest/game/abilities/{charId}    — List available abilities (gated by skill proficiency)

Needs Journal:
  GET  /rest/game/needs/{charId}        — Current needs assessment with narrative framing
  GET  /rest/game/needs/{charId}/objectives — Needs as trackable objectives with progress
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

**Tests:**
- `TestCellGridUtil.java` — all 12 tests (Tier 1 + Tier 2): grid generation, terrain mapping, passability guarantees, path existence, coordinate mapping, edge cells, transition tiles, serialization round-trip, persistence, lazy generation
- `rpgTileRenderer.test.js` — all 5 tests: coordinate conversion edge cases (0, 9, 50, 99), atlas key lookup, variation selection, layer ordering, viewport clipping
- **Gate:** Phase 1 is not complete until all 17 tests pass

**Deliverable:** Player can navigate a visually rich tile map with trees, rocks, water, and paths rendered at the sub-cell level.

### Phase 2: Reputation & VATS Combat

**Goal:** Persistent reputation system and VATS-style action selection.

**Backend:**
- Create `ReputationUtil.java` — pair reputation calculation, persistence, tier evaluation
- Add `olio.reputation` model + schema
- Wire `InteractionUtil.calculateSocialInfluence()` output into reputation records after every interaction
- Create `VATSUtil.java` — decision point detection, action option generation based on context
- Extend `GameService` with reputation endpoints
- Extend `GameStreamHandler` with VATS pause/resume push events

**Frontend:**
- Create `rpgVATSOverlay.js` — action selection overlay on tile map
  - Freeze time display on trigger
  - Context-sensitive action list (combat/social/survival)
  - Action preview (success probability from skill% + stat modifier)
  - Submit action → stream resolution → show outcome → dismiss overlay
- Create `rpgReputationPanel.js` — shows reputation with nearby NPCs
  - Reputation bars with tier labels (Hostile → Devoted)
  - Recent interaction history summary
- Add outcome animations using Ninja Adventure VFX sprites
- Wire damage/outcome display into tile renderer

**Tests:**
- `TestReputationUtil.java` — all 13 tests (Tier 1 + Tier 2): base compatibility scoring, interaction history weighting (VERY_FAVORABLE=+5 through VERY_UNFAVORABLE=-7), recency weighting, instinct modifiers, alignment affinity, composite clamping, tier evaluation, tier boundaries, persistence, update after interaction, no-history fallback, asymmetry validation, zero-division safety
- `TestVATSUtil.java` — all 10 tests (Tier 1 + Tier 2): threat/NPC proximity triggers, context-sensitive action options (combat/social/survival), skill-gated abilities, success probability calculation, action submission pipeline, spurious pause prevention, multi-threat handling
- `TestOverwatchRPGExtensions.java` — VATS + reputation tests (2 of 5): proximity push event, reputation trigger during processInteractions
- `TestGameServiceRPGEndpoints.java` — reputation + cellGrid endpoints (5 of 15): GET reputation, GET reputation/nearby, GET cellGrid, POST cellGrid/generate, invalid charId 404
- `rpgReputationPanel.test.js` — all 4 tests: tier label mapping, tier colors, bar percentage scaling, boundary display
- **Gate:** Phase 2 is not complete until all 34 tests pass

**Deliverable:** Player experiences VATS-style pauses at decision points. Reputation with NPCs tracked and visible. Actions resolve through existing Overwatch pipeline with visual feedback.

### Phase 3: Reputation-Gated Party & Needs Journal

**Goal:** Party system gated by reputation + player-facing needs objectives.

**Backend:**
- Create `PartyUtil.java` — reputation-gated party formation, member loyalty check during Overwatch increments
- Add `olio.party` model + schema
- Extend `NeedsUtil` to generate player-facing objective descriptions
- Create `NeedsJournalUtil.java` — needs-to-objective mapping with progress calculation
- Add party and needs journal endpoints to `GameService`
- Wire reputation check into Overwatch: members desert if reputation drops below threshold

**Frontend:**
- Create `rpgPartyPanel.js` — party management sidebar
  - Member portraits with reputation bar overlay
  - Formation editor (drag members to formation positions)
  - Loyalty warnings when reputation is borderline
- Create `rpgNeedsJournal.js` — needs-as-objectives display
  - Maslow hierarchy visualization (survive at bottom, thrive at top)
  - Per-need progress bars (food inventory level, reputation scores, etc.)
  - LLM-generated narrative framing for each unmet need
- Reputation-based NPC indicators on tile map (color-coded by disposition tier)

**Tests:**
- `TestPartyUtil.java` — all 13 tests (Tier 1 + Tier 2): reputation ≥+30 join gate, reputation <+30 refusal, desertion below +10, boundary value +10, max party size, formation assignment, leader movement, shared store access, dismiss reputation drop, dark triad betrayal trigger, high-reputation no-betrayal, party save/load persistence, NPC-initiated join request
- `TestNeedsJournalUtil.java` — all 8 tests (Tier 2): physiological needs → objectives, Maslow level gating, progress calculation, reputation-based objectives, milestone tracking, needs reassessment on depletion, multiple simultaneous needs, narrative framing non-null
- `TestGameServiceRPGEndpoints.java` — party + needs endpoints (5 of 15): GET party, POST formation, POST dismiss, GET needs, GET needs/objectives
- `rpgNeedsJournal.test.js` — all 4 tests: Maslow ordering, progress bars, gated level display, objective counts
- **Gate:** Phase 3 is not complete until all 30 tests pass

**Deliverable:** NPCs organically join party when reputation is high enough. Player has visible needs-based objectives that drive exploration and interaction.

### Phase 4: Skill Progression, Fog of War & Audio

**Goal:** Skill leveling/decay, visibility system, and audio.

**Backend:**
- Create `SkillProgressionUtil.java` — skill gain on use, decay on disuse per `olio.txt` rules
- Add `proficiency`, `lastUsed`, `usageCount` fields to `data.trait`
- Wire skill updates into `InteractionAction.executeAction()` conclusion
- Wire decay evaluation into `Overwatch.syncClocks()`
- Add fog of war tracking: `exploredCells` packed field on `olio.state`
- Milestone-based attribute point grants (new region, new Maslow level, reputation threshold)
- Extend save/load to include reputation, explored map, skill proficiencies

**Frontend:**
- Create `rpgSkillPanel.js` — skill list with proficiency bars and decay indicators
  - Decay warning (amber) when skill is approaching decay threshold
  - Active decay (red) when skill has decayed since last use
  - Proficiency gain animation on successful skill use
- Create `rpgFogOfWar.js` — visibility overlay
  - Unexplored cells: opaque black
  - Previously explored but out of range: 50% darkened
  - Currently visible (perception radius): full brightness
- Create `rpgAudioManager.js` — background music + SFX
  - Terrain-keyed music (Ninja Adventure tracks)
  - Combat music trigger on VATS activation
  - SFX on action resolution, item pickup, skill gain
- Attribute allocation UI when milestone grants points
- Character sheet with full stat + skill + reputation display
- Mini-map overlay showing explored area

**Tests:**
- `TestSkillProgressionUtil.java` — all 17 tests (Tier 1 + Tier 2): gain on favorable/very-favorable/equilibrium, no gain on unfavorable, diminishing returns curve, cap at 95%, initial cap at 50%, Fae 4x cost, decay formula verification (70%→30d, 90%→10d, 95%→5d), floor at 60%, decay at 61%→60% then stop, decay accumulation over time, decay reset on use, intelligence gain rate modifier, decay during Overwatch syncClocks, skill gain after combat interaction, independent multi-skill decay
- `TestOverwatchRPGExtensions.java` — skill decay tests (3 of 5): skill decay during syncClocks, member desertion during processGroup, full cycle with gain-then-decay
- `TestGameServiceRPGEndpoints.java` — remaining endpoints (5 of 15): GET skills, POST allocatePoints, allocatePoints exceeds potential 400, GET abilities, unauthorized access 403
- `rpgSkillPanel.test.js` — all 4 tests: proficiency bar width, decay warning threshold, active decay indicator, skill sort order
- **Gate:** Phase 4 is not complete until all 29 tests pass

**Cumulative:** All 110 tests across Phases 1-4 must pass for the RPG to be considered feature-complete.

**Deliverable:** Complete RPG experience with organic skill progression, atmospheric audio, and exploration reward through fog of war.

### Phase 5 (Future): Multiplayer & Content

**Goal:** Cooperative multiplayer and expanded content. (Deferred)

- Turn coordination for multiple players in same realm via WebSocket push
- Shared party reputation (group reputation with NPCs)
- PvP reputation and combat
- LLM-generated contextual objectives beyond basic needs
- Dungeon instances (instanced sub-realms with unique POI configurations)
- Trading system gated by mutual reputation
- Reputation-based faction system (communities with collective disposition)
- World events affecting reputation across regions

---

## Test Strategy

### Principles

Every new utility class gets a dedicated test class. Every modified existing class gets new test methods covering the modifications. Tests are written **before or alongside** implementation — not deferred. The existing project uses **JUnit 4 (4.13.2)** with **Maven Surefire** (`surefire-junit47`), the `BaseTest` parent class for DB lifecycle, and `OlioTestUtil` for character/context setup. All new tests follow these conventions.

### Test Tiers

**Tier 1 — Pure Logic (No DB, No External Services):**
Unit tests for calculations, formulas, and enum logic. These run in the default Maven `test` phase with no exclusions. Fast, deterministic, no setup dependencies.

**Tier 2 — Integration (DB Required, OlioContext):**
Tests that create characters, run Overwatch cycles, and verify state persistence. Use `BaseTest` setup with H2 or PostgreSQL. May be excluded from CI default run but **must** pass before merge.

**Tier 3 — Service Layer (REST Endpoint, WebSocket):**
Tests for GameService endpoints using the Service7 `BaseTest` with H2. Verify request/response contracts, error handling, and authorization.

**Tier 4 — Frontend (JS Unit Tests):**
The UX7 project currently has no JS test framework. Introduce **Vitest** (compatible with the existing esbuild/Vite toolchain) for testing rpg module logic (tile coordinate math, reputation tier display, needs journal state). Canvas rendering and DOM are not unit-tested — those are validated manually or via integration playtesting.

### Backend Test Classes

#### TestReputationUtil.java (Phase 2)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testBaseCompatibilityScore` | 1 | `ProfileComparison` inputs produce correct base score range (-20 to +20) |
| `testInteractionHistoryWeighting` | 1 | VERY_FAVORABLE = +5, FAVORABLE = +2, EQUILIBRIUM = 0, UNFAVORABLE = -3, VERY_UNFAVORABLE = -7 |
| `testRecencyWeighting` | 1 | Recent interactions weighted more heavily than old ones |
| `testInstinctModifierContribution` | 1 | cooperate/resist instinct maps to -10..+10 range |
| `testAlignmentAffinityContribution` | 1 | Same alignment = +8, opposed = -8, partial match scales linearly |
| `testCompositeScoreClamping` | 1 | Final score clamped to -100..+100 regardless of input extremes |
| `testTierEvaluation` | 1 | Score → tier mapping: <-50 HOSTILE, -50..-10 GUARDED, -10..+10 NEUTRAL, +10..+30 FRIENDLY, +30..+60 ALLIED, >+60 DEVOTED |
| `testTierBoundaryValues` | 1 | Exact boundary values (-50, -10, +10, +30, +60) map correctly |
| `testReputationPersistence` | 2 | Create two characters, run interactions, verify reputation record persists across context reload |
| `testReputationUpdateAfterInteraction` | 2 | Run `calculateSocialInfluence()`, verify reputation record updated |
| `testReputationWithNoHistory` | 2 | New character pair returns base compatibility only |
| `testSymmetry` | 1 | reputation(A,B) may differ from reputation(B,A) due to personality asymmetry — verify both are valid |
| `testZeroDivision` | 1 | Characters with identical stats/personality produce valid (not NaN/Infinity) score |

#### TestSkillProgressionUtil.java (Phase 4)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testSkillGainOnFavorable` | 1 | FAVORABLE outcome: proficiency increases by `(100 - current) * 0.05` |
| `testSkillGainOnVeryFavorable` | 1 | VERY_FAVORABLE outcome: same gain formula applies |
| `testSkillGainOnEquilibrium` | 1 | EQUILIBRIUM: half rate gain `(100 - current) * 0.025` |
| `testNoGainOnUnfavorable` | 1 | UNFAVORABLE / VERY_UNFAVORABLE: proficiency unchanged |
| `testDiminishingReturns` | 1 | Skill at 90% gains less per use than skill at 30% |
| `testCapAt95` | 1 | Proficiency cannot exceed 95% regardless of gains |
| `testInitialCapAt50` | 1 | New character skills cannot be allocated above 50% |
| `testFaeCost4x` | 1 | Magic/Fae skills require 4x allocation cost |
| `testDecayFormula` | 1 | `(100 - skill%) = days before 1% decay`. Verify: 70% → 30 days, 90% → 10 days, 95% → 5 days |
| `testDecayFloorAt60` | 1 | Skill at 60% does not decay further regardless of time elapsed |
| `testDecayAt61` | 1 | Skill at 61% decays to 60% after 39 days, then stops |
| `testDecayAccumulation` | 1 | 100 days of disuse on 80% skill → 5 decay events (every 20 days) → 75% |
| `testDecayResetOnUse` | 1 | Using a skill resets the `lastUsed` timestamp; no decay from prior disuse |
| `testIntelligenceModifiesGainRate` | 1 | Higher intelligence → faster gain rate (modifier to base 0.05) |
| `testSkillDecayInOverwatch` | 2 | Advance Overwatch clock by N days, verify skill proficiency decreases correctly |
| `testSkillGainAfterCombat` | 2 | Run combat interaction, verify fight/weapon skills gain proficiency |
| `testMultipleSkillsDecayIndependently` | 2 | Two skills with different lastUsed dates decay at different rates |

#### TestCellGridUtil.java (Phase 1)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testGridGeneration` | 1 | 100m cell → 10x10 sub-grid with valid tile data for each position |
| `testTerrainMapping` | 1 | Each `TerrainEnumType` maps to at least 1 valid tile key |
| `testTerrainVariation` | 1 | Repeated generation for same terrain type produces variation (not all tiles identical) |
| `testPOIPlacement` | 2 | POI at known meter position generates correct prop tile in corresponding sub-cell |
| `testPassabilityGeneration` | 1 | Generated grid respects terrain passability percentages (e.g., FOREST 40-60% passable) |
| `testPassabilityGuaranteesPath` | 1 | Generated grid always has at least one passable path across (no walled-off grids) |
| `testMeterToSubCellMapping` | 1 | `floor(currentEast / 10)` and `floor(currentNorth / 10)` produce correct sub-cell indices for all 100 positions |
| `testEdgeCells` | 1 | Sub-cells at (0,0), (0,9), (9,0), (9,9) generate valid tile data |
| `testTransitionTiles` | 1 | Adjacent cells with different terrain types generate edge/transition tiles at boundaries |
| `testTileDataSerialization` | 1 | `tileData` JSON field round-trips through serialization without data loss |
| `testGridPersistence` | 2 | Generate grid, persist to `geoLocation.tileData`, reload, verify identical |
| `testLazyGeneration` | 2 | Grid not generated until first request; subsequent requests return cached grid |

#### TestPartyUtil.java (Phase 3)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testJoinRequiresReputation30` | 2 | NPC with reputation < +30 refuses to join party |
| `testJoinSucceedsAtReputation30` | 2 | NPC with reputation ≥ +30 joins party |
| `testDesertionBelowReputation10` | 2 | Member deserts during Overwatch increment when reputation drops below +10 |
| `testDesertionBoundary` | 2 | Member at exactly +10 reputation does NOT desert |
| `testMaxPartySize` | 2 | Cannot exceed max party members (4-6) |
| `testFormationAssignment` | 1 | Each `FormationEnumType` value assigns valid positions |
| `testLeaderMovement` | 2 | Party members follow leader position in formation |
| `testSharedStoreAccess` | 2 | All party members can access shared `olio.store` |
| `testDismissDropsReputation` | 2 | Dismissing a member reduces reputation with that NPC |
| `testBetrayalOnHighDarkTriad` | 2 | Member with high Machiavellianism/psychopathy and borderline reputation may betray |
| `testBetrayalNotGuaranteed` | 2 | High dark triad + high reputation does NOT trigger betrayal |
| `testPartyPersistence` | 2 | Party survives save/load cycle with all members and formation intact |
| `testNPCInitiatedJoin` | 2 | NPC crossing +30 reputation generates pending join request |

#### TestVATSUtil.java (Phase 2)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testThreatTriggersPause` | 2 | Hostile entity entering proximity triggers VATS decision point |
| `testNPCProximityTriggersPause` | 2 | Non-hostile NPC entering proximity triggers decision point |
| `testActionOptionsForCombat` | 1 | Threat context generates combat actions (attack, defend, flee) |
| `testActionOptionsForSocial` | 1 | NPC context generates social actions (talk, trade, recruit) |
| `testActionOptionsForSurvival` | 1 | Critical needs context generates survival actions (eat, drink, rest) |
| `testActionOptionsGatedBySkill` | 2 | Abilities only appear if character meets skill proficiency threshold |
| `testSuccessProbabilityCalc` | 1 | Preview probability = `(skill% + statModifier) / 100` clamped to 5-95% |
| `testActionSubmission` | 2 | Selected action queues to Overwatch and resolves through normal pipeline |
| `testNoSpuriousPauses` | 2 | Neutral environment with no threats/NPCs does NOT trigger VATS |
| `testMultipleThreats` | 2 | Multiple simultaneous threats generate single pause with all threats visible |

#### TestNeedsJournalUtil.java (Phase 3)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testPhysiologicalNeedsMapping` | 2 | Unmet FOOD need → trackable "Find food" objective |
| `testMaslowGating` | 2 | LOVE needs do not surface until PHYSIOLOGICAL and SAFETY are met |
| `testProgressCalculation` | 2 | Food objective progress = foodItems.count / dailyRequirement |
| `testReputationObjective` | 2 | LOVE.FRIENDSHIP need → "Earn trust" objective with reputation score as progress |
| `testMilestoneTracking` | 2 | First time satisfying a new Maslow level → milestone recorded |
| `testNeedsReassessment` | 2 | Dropping below a met need (e.g., food consumed) reactivates lower-level objectives |
| `testMultipleUnmetNeeds` | 2 | Multiple unmet needs at same level all appear as objectives |
| `testObjectiveNarrativeNotNull` | 2 | Every objective has non-null, non-empty narrative framing |

#### TestOverwatchRPGExtensions.java (Phase 2 + 4)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testSkillDecayDuringSyncClocks` | 2 | Advancing clock triggers skill decay evaluation for all characters |
| `testReputationTriggerDuringProcessInteractions` | 2 | Completed interaction updates reputation record |
| `testVATSPauseOnProximityEvent` | 2 | `processProximity()` detecting threat generates push event |
| `testMemberDesertionDuringProcessGroup` | 2 | Party member loyalty checked during group processing |
| `testFullCycleWithSkillGainAndDecay` | 2 | Run N Overwatch increments: use skill, advance time, verify gain then decay |

#### TestGameServiceRPGEndpoints.java (Phase 2 + 3 + 4)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testGetReputation` | 3 | `GET /rest/game/reputation/{charId}/{targetId}` returns valid reputation JSON |
| `testGetReputationNearby` | 3 | `GET /rest/game/reputation/{charId}/nearby` returns list for all NPCs in range |
| `testGetParty` | 3 | `GET /rest/game/party/{partyId}` returns party with members and reputations |
| `testSetFormation` | 3 | `POST /rest/game/party/formation` updates formation and returns success |
| `testDismissMember` | 3 | `POST /rest/game/party/dismiss/{charId}` removes member and reduces reputation |
| `testGetCellGrid` | 3 | `GET /rest/game/cellGrid/{cellId}` returns 10x10 tile data |
| `testGenerateCellGrid` | 3 | `POST /rest/game/cellGrid/generate/{cellId}` creates and persists grid |
| `testGetSkills` | 3 | `GET /rest/game/skills/{charId}` returns skills with proficiency and decay status |
| `testAllocatePoints` | 3 | `POST /rest/game/allocatePoints` deducts from potential and increases stat |
| `testAllocatePointsExceedsPotential` | 3 | Allocating more points than available returns 400 error |
| `testGetAbilities` | 3 | `GET /rest/game/abilities/{charId}` returns only abilities meeting skill threshold |
| `testGetNeeds` | 3 | `GET /rest/game/needs/{charId}` returns needs assessment with narrative |
| `testGetNeedsObjectives` | 3 | `GET /rest/game/needs/{charId}/objectives` returns trackable objectives with progress |
| `testUnauthorizedAccess` | 3 | Accessing another user's character data returns 403 |
| `testInvalidCharId` | 3 | Non-existent charId returns 404 |

### Frontend Test Classes (Vitest)

#### rpgTileRenderer.test.js (Phase 1)

| Test | What It Validates |
|------|-------------------|
| `meterToSubCell conversion` | `floor(east/10)`, `floor(north/10)` for all edge cases (0, 9, 50, 99) |
| `tile atlas key lookup` | Terrain enum string → correct atlas region coordinates |
| `tile variation selection` | Same terrain + different seed → different variant (not always variant 0) |
| `layer ordering` | Ground rendered before props, props before entities, entities before fog |
| `viewport clipping` | Only visible sub-cells queried for tile data (not all 100) |

#### rpgReputationPanel.test.js (Phase 2)

| Test | What It Validates |
|------|-------------------|
| `tier label from score` | Score -60 → "Hostile", +15 → "Friendly", +45 → "Allied", etc. |
| `tier color mapping` | Each tier maps to a distinct display color |
| `reputation bar percentage` | Score -100..+100 maps to 0-100% bar width |
| `boundary display` | Score exactly at tier boundary displays correct tier |

#### rpgSkillPanel.test.js (Phase 4)

| Test | What It Validates |
|------|-------------------|
| `proficiency bar width` | Proficiency 0-100 → proportional bar width |
| `decay warning threshold` | Skill approaching decay shows amber indicator |
| `active decay indicator` | Skill that has decayed since last use shows red indicator |
| `skill sort order` | Skills sorted by proficiency descending |

#### rpgNeedsJournal.test.js (Phase 3)

| Test | What It Validates |
|------|-------------------|
| `maslow level ordering` | Physiological at bottom, Self at top |
| `progress bar calculation` | Need progress 0-1 → proportional bar width |
| `gated level display` | Unmet lower level dims/locks upper levels |
| `objective count per level` | Multiple unmet needs at same level all visible |

### Coverage Targets

| Category | Target | Rationale |
|----------|--------|-----------|
| Reputation formula | 100% branch | Core mechanic — incorrect calculation breaks party, dialogue, and NPC behavior |
| Skill decay formula | 100% branch | Tabletop-derived rules must match exactly; floor at 60%, Fae 4x, cap at 95% |
| Cell grid generation | 90%+ line | Passability guarantees and terrain mapping are visual-correctness critical |
| Party join/leave logic | 100% branch | Reputation gates are the party system — every threshold must be exact |
| VATS trigger conditions | 100% branch | False positives (spurious pauses) or false negatives (missed threats) break gameplay |
| Needs journal Maslow gating | 100% branch | Incorrect gating surfaces wrong objectives |
| REST endpoints | All happy path + all error codes | Public API contract must be reliable |
| Tile coordinate math (JS) | 100% branch | Off-by-one in coordinate conversion = visual bugs across entire map |

### Test Execution

```bash
# Tier 1 — Pure logic (no external dependencies, runs in CI)
mvn test -pl AccountManagerObjects7

# Tier 2 — Integration (requires DB)
mvn test -pl AccountManagerObjects7 -Dtest="TestReputationUtil,TestSkillProgressionUtil,TestCellGridUtil,TestPartyUtil,TestVATSUtil,TestNeedsJournalUtil,TestOverwatchRPGExtensions"

# Tier 3 — Service layer (requires H2)
mvn test -pl AccountManagerService7 -Dtest="TestGameServiceRPGEndpoints"

# Tier 4 — Frontend (requires Node.js)
cd AccountManagerUx7 && npx vitest run

# All tiers
mvn test && cd AccountManagerUx7 && npx vitest run
```

### Test-Per-Phase Checklist

Each phase is **not complete** until all tests for that phase pass:

- **Phase 1:** TestCellGridUtil (all), rpgTileRenderer.test.js (all)
- **Phase 2:** TestReputationUtil (all), TestVATSUtil (all), TestOverwatchRPGExtensions (VATS + reputation tests), TestGameServiceRPGEndpoints (reputation + cellGrid endpoints), rpgReputationPanel.test.js (all)
- **Phase 3:** TestPartyUtil (all), TestNeedsJournalUtil (all), TestGameServiceRPGEndpoints (party + needs endpoints), rpgNeedsJournal.test.js (all)
- **Phase 4:** TestSkillProgressionUtil (all), TestOverwatchRPGExtensions (skill decay tests), TestGameServiceRPGEndpoints (skills + abilities + allocatePoints endpoints), rpgSkillPanel.test.js (all)

---

## File Manifest

### New Files to Create

**Backend (AccountManagerObjects7):**
| File | Purpose |
|------|---------|
| `src/main/resources/models/olio/partyModel.json` | Party schema |
| `src/main/resources/models/olio/reputationModel.json` | Persistent pair reputation schema |
| `src/main/resources/models/olio/abilityModel.json` | Ability schema (extends action) |
| `src/main/java/org/cote/accountmanager/olio/CellGridUtil.java` | Sub-grid generation from terrain + POIs |
| `src/main/java/org/cote/accountmanager/olio/ReputationUtil.java` | Pair reputation calculation + persistence |
| `src/main/java/org/cote/accountmanager/olio/SkillProgressionUtil.java` | Skill leveling through use + decay over time |
| `src/main/java/org/cote/accountmanager/olio/PartyUtil.java` | Reputation-gated party management |
| `src/main/java/org/cote/accountmanager/olio/VATSUtil.java` | Decision point detection + action options |
| `src/main/java/org/cote/accountmanager/olio/NeedsJournalUtil.java` | Needs-to-objective mapping + progress |
| `src/main/java/org/cote/accountmanager/olio/schema/FormationEnumType.java` | Party formation enum |
| `src/main/java/org/cote/accountmanager/olio/schema/ReputationTierEnumType.java` | Reputation tier enum |
| `src/main/java/org/cote/accountmanager/olio/schema/TargetTypeEnumType.java` | Ability target enum |

**Backend Tests (AccountManagerObjects7):**
| File | Purpose |
|------|---------|
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestReputationUtil.java` | Reputation formula, tier evaluation, persistence, boundary values (13 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestSkillProgressionUtil.java` | Skill gain/decay formula, floor/cap, Fae 4x, Overwatch integration (17 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestCellGridUtil.java` | Sub-grid generation, terrain mapping, passability, serialization (12 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestPartyUtil.java` | Reputation-gated join/leave, desertion, betrayal, persistence (13 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestVATSUtil.java` | Decision point detection, action options, skill gating (10 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestNeedsJournalUtil.java` | Needs-to-objective mapping, Maslow gating, progress (8 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestOverwatchRPGExtensions.java` | Skill decay in syncClocks, reputation triggers, VATS pause (5 tests) |

**Backend Tests (AccountManagerService7):**
| File | Purpose |
|------|---------|
| `src/test/java/org/cote/accountmanager/objects/tests/TestGameServiceRPGEndpoints.java` | REST endpoint contracts for all new game endpoints (15 tests) |

**Backend (AccountManagerService7):**
| File | Purpose |
|------|---------|
| Extensions to `GameService.java` | New endpoints for reputation, party, cellGrid, skills, needs journal |

**Frontend (AccountManagerUx7):**
| File | Purpose |
|------|---------|
| `client/view/rpgGame.js` | Main RPG game view (extends cardGame patterns) |
| `client/view/rpg/rpgTileRenderer.js` | Canvas tile rendering engine |
| `client/view/rpg/rpgAssetLoader.js` | Spritesheet/atlas management |
| `client/view/rpg/rpgVATSOverlay.js` | VATS-style action selection overlay |
| `client/view/rpg/rpgReputationPanel.js` | NPC reputation display |
| `client/view/rpg/rpgPartyPanel.js` | Reputation-gated party management |
| `client/view/rpg/rpgNeedsJournal.js` | Needs-as-objectives display |
| `client/view/rpg/rpgSkillPanel.js` | Skill proficiency + decay display |
| `client/view/rpg/rpgAudioManager.js` | Music and sound effects |
| `client/view/rpg/rpgFogOfWar.js` | Visibility overlay |
| `client/view/rpg/rpgInventoryUI.js` | Enhanced inventory with equipment slots |
| `client/view/rpg/rpgCharacterSheet.js` | Full character stats + skills + reputation display |
| `client/view/rpg/rpgMiniMap.js` | Explored area mini-map |

**Frontend Tests (AccountManagerUx7):**
| File | Purpose |
|------|---------|
| `client/view/rpg/__tests__/rpgTileRenderer.test.js` | Coordinate conversion, atlas lookup, viewport clipping (5 tests) |
| `client/view/rpg/__tests__/rpgReputationPanel.test.js` | Tier labels, color mapping, bar percentage (4 tests) |
| `client/view/rpg/__tests__/rpgSkillPanel.test.js` | Proficiency bars, decay indicators, sort order (4 tests) |
| `client/view/rpg/__tests__/rpgNeedsJournal.test.js` | Maslow ordering, progress bars, level gating (4 tests) |
| `vitest.config.js` | Vitest configuration for RPG module unit tests |

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

### Existing Files to Modify

| File | Changes |
|------|---------|
| `AccountManagerObjects7` `data.traitModel.json` | Add `proficiency`, `lastUsed`, `usageCount` fields |
| `AccountManagerObjects7` `olio.stateModel.json` | Add `exploredCells`, `milestones` fields |
| `AccountManagerObjects7` `data.geoLocationModel.json` | Add `tileData` JSON field for sub-cell grid |
| `AccountManagerObjects7` `Overwatch.java` | Wire skill decay into `syncClocks()`, VATS pause support |
| `AccountManagerObjects7` `InteractionAction.java` | Wire skill proficiency gain after action resolution |
| `AccountManagerObjects7` `InteractionUtil.java` | Wire reputation persistence into `calculateSocialInfluence()` |
| `AccountManagerObjects7` `GameUtil.java` | Party-aware movement, reputation-aware NPC behavior |
| `AccountManagerService7` `GameService.java` | New endpoints (reputation, party, cellGrid, skills, needs) |
| `AccountManagerService7` `GameStreamHandler.java` | VATS pause push events, reputation update events |
| `AccountManagerUx7` `applicationRouter.js` | Add `/rpg/:id` route |
| `AccountManagerUx7` `modelDef.js` | Add new model schemas (party, reputation, ability) |
| `AccountManagerUx7` `build.js` / esbuild config | Include new RPG modules and asset directories |

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
