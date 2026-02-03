# AM7 Turn-Based RPG — Design & Build Plan

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Platform Recommendation](#platform-recommendation)
3. [Existing AM7 Capabilities Inventory](#existing-am7-capabilities-inventory)
4. [Current Design Shortcomings](#current-design-shortcomings)
5. [Intra-Cell Visual Grid System](#intra-cell-visual-grid-system)
6. [Asset Library Strategy](#asset-library-strategy) (incl. Theme Swappability, Dynamic Generation)
7. [Game Design Document](#game-design-document) (incl. Multiplayer, Death, Economy, Creatures, Skill Universe, Magic)
8. [Technical Architecture](#technical-architecture) (incl. WebSocket Event System)
9. [Implementation Phases](#implementation-phases) (Phases 1–5 active, Phase 6 deferred)
10. [Test Strategy](#test-strategy)
11. [File Manifest](#file-manifest)

---

## Executive Summary

AccountManager7 already contains the core systems needed for a turn-based RPG: a deep character model with D&D-style statistics, alignment, and personality; an MGRS-inspired spatial coordinate system with terrain generation; an inventory/crafting/equipment system; an interaction and combat framework; a time/event simulation engine (Overwatch); and a working game client (cardGame.js) with WebSocket streaming. This plan extends those systems into a fully realized **multiplayer** turn-based RPG with tile-based visual navigation, party management, quest progression, and public asset integration.

**Architecture Principle — REST-First:** The RPG client communicates with AM7 **exclusively through the REST API and WebSocket endpoints**. No direct model access, no embedded Java calls, no server-side rendering dependencies. Every game action — movement, combat, dialogue, inventory, party management — flows through the existing and extended `/rest/game/*` and `/rest/olio/*` endpoints. This strict API boundary means the client is a pure consumer of a well-defined service contract, making it trivial to swap, extend, or supplement with alternative frontends.

**Architecture Principle — WebSocket-Driven State:** All intra-game status updates — player movement, combat resolution, reputation changes, NPC actions, threat detection, turn advancement, and multiplayer coordination — are **pushed to clients via WebSocket** in real-time. The existing `GameStreamHandler` already implements a session-subscription model where multiple clients subscribe to character updates and receive push events. REST is used for request/response actions (move, interact, save); WebSocket is used for all asynchronous state notifications. This dual-channel design means every connected client sees world changes as they happen, which is the foundation for multiplayer.

**Architecture Principle — App-Ready:** The RPG client is built as an **installable cross-platform App** targeting desktop, tablet, and mobile from a single codebase. The web-based implementation (PWA) provides native-app-like experience — installable from the browser, full-screen capable, responsive across screen sizes, and touch-optimized. Because all game logic runs server-side behind the REST API, the client is a lightweight rendering and input layer with no platform-specific dependencies.

**Architecture Principle — Multiplayer-Native:** The game is designed for multiplayer from the ground up, not bolted on after single-player. Multiple players share a realm, see each other's movements in real-time via WebSocket push, form cross-player parties, engage in PvP or cooperative combat, and trade. The Overwatch engine coordinates turn resolution across all players in a realm. Single-player is simply multiplayer with one participant — there is no separate single-player code path.

**What exists today (reuse):** ~85% of the backend model, service layer, and communication infrastructure — including needs-driven behavior, personality-based compatibility, social influence tracking, skill/combat resolution, and the tabletop-derived skill decay rules. The REST API already exposes 25+ game endpoints covering movement, interaction, chat, situation reports, save/load, and asset serving.
**What needs to be built:** Visual tile renderer, intra-cell sub-grid system, persistent reputation calculator, skill leveling/decay implementation, tile art pipeline, responsive/adaptive UI layout, PWA shell, multiplayer session/turn coordination, and a dedicated RPG game client. Most "missing" systems are extensions of existing infrastructure rather than greenfield work. New backend features are exposed as REST endpoints first, then consumed by the client — never wired directly. The WebSocket push infrastructure (`GameStreamHandler`) already supports multi-session subscriptions; multiplayer extends this to player presence, cross-player events, and coordinated turn resolution.

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
- See [Skill Universe Template](#skill-universe-template) for the full catalog of learnable skills organized by category and world maturity tier

### 6. Active Ability System (Extension of Skill + Action)

**What exists:**
- `olio.action` model with difficulty (0-20), duration, positiveStatistics/negativeStatistics, counterActions, requiredSkills
- `IAction` interface with full lifecycle: `beginAction → executeAction → concludeAction`
- `InteractionAction` maps interaction types to component actions with stat overrides and personality/instinct modifiers
- `CommonAction.applyActionEffects()` handles state/stat/instinct changes from outcomes
- 2d20 resolution system with stat + personality + instinct modifiers

**What's missing:** Active abilities that a player can select during action evaluation — spells, special attacks, or skills with resource costs and targeting.

**Solution:** Extend `olio.action` with ability-specific fields (resourceCost, cooldown, targetType, range, areaSize). Abilities are unlocked by skill proficiency thresholds (e.g., "Fireball" requires magic skill >= 60%). During VATS-style action selection (see #7), the player picks from available abilities. Resource cost deducted from `state.energy`. Cooldown tracked per-ability on `olio.state.actions`.

**Note:** Magic and Fae abilities use the [Seal-based magic system](#magic-system-seal-based) — spells are powered by physical Seal artifacts and cast via Sorcerer (reagent) or Channeler (direct) paths. Non-magical abilities (special attacks, defensive stances, crafting techniques) derive from the [Skill Universe](#skill-universe-template) categories (COMBAT, DEFENSE, SURVIVAL, etc.).

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

### 9. Map Tile Art Pipeline (Solved -- Theme System)

**Problem:** Terrain rendering uses basic PNG tiles served from `/rest/game/tile/{terrain}` with emoji fallback. There is no support for tile variations, animated tiles, transition tiles between terrain types, or layered rendering (ground + objects + entities).

**Impact:** Maps look monotonous — every forest cell is the same forest tile. No visual variety or environmental storytelling.

**Solution:** Solved by the [Theme-Swappable Asset System](#architecture-principle--theme-swappable-assets). All tile rendering uses **concept keys** resolved through the active theme manifest. The theme system provides: (1) base terrain tiles with variations per terrain type mapped to concept keys (e.g., `terrain.FOREST.base.0`, `terrain.FOREST.base.1`), (2) transition/edge tiles for terrain boundaries using autotile rules and concept keys (e.g., `terrain.FOREST.transition.LAKE`), (3) object/structure layer for props and player-built structures mapped to the intra-cell sub-grid, (4) entity layer for characters/NPCs/monsters with sprite animations. The SD pipeline generates missing assets on demand via `POST /rest/game/asset/generate`. See [Asset Library Strategy](#asset-library-strategy) for the full theme architecture, manifest format, and resolution chain.

### 10. Audio / Music System (Straightforward Addition)

**What exists:** Magic8 has a full `AudioEngine` (binaural beats, isochronic tones), `VoiceSequenceManager` (TTS), and the backend `VoiceService` supports synthesis/transcription. The Ninja Adventure asset pack includes 37 music tracks and 100+ sound effects.

**Solution:** Lightweight `rpgAudioManager.js` using Web Audio API, borrowing patterns from Magic8's `AudioEngine`. Terrain-keyed background music (forest → ambient, cave → tense, combat → battle track). SFX triggers on action resolution (attack hit/miss, item pickup, level up). Volume/mute toggle in game UI. Low implementation risk.

### 11. Multiplayer Session & Turn Coordination (Extension Needed)

**What exists:**
- `GameStreamHandler` — full WebSocket push infrastructure with session-subscription model. Multiple sessions can subscribe to the same character (`characterSubscriptions: charId → Set<Session>`). Push events broadcast to all subscribers.
- 14+ push event types already implemented: `game.action.start/progress/complete/error`, `game.threat.detected/removed`, `game.npc.moved/action`, `game.interaction.start/phase/end`, `game.time.advanced`, `game.increment.end`, `game.event.occurred`, `game.state.changed`
- `Overwatch` 7-stage processing loop handles all game simulation (proximity, interactions, actions, time)
- JWT authentication identifies each connected user
- `olio.realm` provides the shared geographic context (all players in a realm share the world)

**What's missing:** Player session management (who is online in this realm), player-to-player visibility (seeing other players move on the tile map), coordinated turn resolution (Overwatch must wait for all active players at decision points), cross-player party formation, PvP interaction routing, and realm-scoped event broadcasting.

**Solution — Realm Session Manager:**

A `RealmSessionManager` tracks which players are currently active in each realm:
- Player joins realm → `game.player.joined` pushed to all realm subscribers
- Player moves → `game.player.moved` pushed to all nearby players (within visibility range)
- Player disconnects/idles → `game.player.left` pushed to all realm subscribers
- Session heartbeat (30s interval) detects stale connections

**Solution — Coordinated Turn Resolution:**

The Overwatch loop already processes actions sequentially. For multiplayer, extend this with a **player notification gate**:

1. `Overwatch.processProximity()` detects a decision point (threat, NPC encounter, etc.)
2. If multiple players are affected, server pushes `game.vats.pause` to ALL affected players
3. Server enters **VATS collection window** — waits for action submissions from all affected players (with configurable timeout, default 60s)
4. As each player submits their action, server pushes `game.vats.playerReady` to the others (showing who has committed)
5. When all players have submitted (or timeout expires, defaulting idle players to "defend"), Overwatch resolves all actions in initiative order (`reaction` stat)
6. Results pushed to all players via existing `game.action.complete` events
7. Overwatch resumes real-time until the next decision point

Players NOT involved in a decision point continue moving/exploring freely — only affected players pause. This avoids a global turn lock.

**Solution — Player Visibility:**

Players in the same realm see each other on the tile map. Visibility follows the same fog-of-war rules as NPCs:
- Players within perception radius appear on each other's tile grids
- `game.player.moved` events include position data (cell + sub-cell coordinates)
- Player sprites rendered on the entity layer (same as NPCs, different color/indicator)
- Player name labels shown on hover/tap

**Solution — Cross-Player Interaction:**

Players can interact with each other using the same `InteractionEnumType` system used for NPCs:
- `COMBAT` → PvP, resolved through existing `InteractionAction` pipeline
- `BEFRIEND` / `COOPERATE` → reputation-gated party formation across players
- `COMMERCE` → direct player-to-player trading via shared store
- `COMMUNICATE` → player-to-player chat (routed through WebSocket, not LLM)

Cross-player interactions trigger the same VATS pause for both players — both must commit an action.

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

### Proposed Extension: Sub-Cell Tile Grid (type: `subCell`)

Add a 10x10 sub-cell grid to each `data.geoLocation` record of type `cell`. Each sub-cell tile represents a 10m x 10m area and can hold terrain, props, and structures.

**Design Principle:** The world starts wild. Terrain is procedurally generated from noise functions seeded by cell coordinates. Structures (campfires, walls, workbenches, storage) are **player-built** — they do not exist until someone constructs them. The only pre-placed objects are terrain features (trees, rocks, water) and POIs from `olio.pointOfInterest`.

#### Data Model Extension

**Packed JSON field on geoLocation:**

Add a `tileData` field to `data.geoLocation` containing a JSON-encoded 10x10 grid. All tile keys are **concept keys** resolved through the active theme manifest:

```json
{
  "tiles": [
    [{"t": "terrain.FOREST.base", "p": true}, {"t": "terrain.FOREST.base", "p": true}, ...],
    [{"t": "terrain.FOREST.tree", "p": false}, {"t": "terrain.FOREST.base", "p": true}, ...],
    ...
  ],
  "structures": [],
  "pois": [
    {"x": 3, "y": 7, "type": "COMMERCIAL", "id": "poi-123", "name": "Blacksmith"},
    {"x": 5, "y": 2, "type": "RUIN", "id": "poi-456", "name": "Old Well"}
  ]
}
```

Where:
- `t` = concept key (resolved via theme manifest at render time)
- `p` = passable (boolean)
- `structures` = player-built objects (starts empty, populated by construction actions)
- `pois` = pre-existing points of interest from `olio.pointOfInterest`

**Rationale:** Avoids creating 100 new database records per cell. The 10x10 grid is a rendering concern — it needs fast bulk reads, not individual CRUD. A single JSON field on the existing cell record is efficient. Concept keys decouple tile data from any specific art pack.

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

#### Procedural Terrain Generation

When `prepareCells(feature)` creates a cell, generate its `tileData` using noise-based terrain:

1. **Noise-based base fill:** Use Perlin/simplex noise seeded by `(cellEast, cellNorth, worldSeed)` to generate terrain variation. The cell's `TerrainEnumType` sets the palette; noise selects among base variants (e.g., `terrain.FOREST.base` variants).
2. **Feature placement:** Noise threshold determines feature density (trees in forest, rocks in mountain, cacti in desert). Higher noise values = denser features. Features use concept keys (`terrain.FOREST.tree`, `terrain.MOUNTAIN.boulder`).
3. **Edge blending:** For cells on terrain boundaries, blend adjacent terrain types along edges using transition concept keys (`terrain.FOREST.transition.LAKE`).
4. **Path carving:** If the cell is on a route between POIs, carve a passable path through the sub-grid.
5. **POI integration:** Map existing `olio.pointOfInterest` entries (which already have `east`/`north` meter positions) to sub-cell coordinates by dividing by 10.
6. **Passability:** Mark tiles with obstacles as impassable. Pathfinding in the zoomed view respects passability. Guarantee at least one passable path across the grid.
7. **No structures:** `structures` array starts empty. All structures are player-built.

#### Player Construction

Players can build structures at their current sub-cell position using materials from inventory. Construction uses the existing `olio.builder` system:

- **Requirements:** Appropriate materials in inventory + sufficient crafting skill + valid location (not water, not occupied)
- **Process:** `POST /rest/game/build` → server validates requirements → adds structure to `tileData.structures` → updates passability → pushes `game.state.changed`
- **Structure types:** `StructureTypeEnumType`: CAMPFIRE, STORAGE_CHEST, WALL, WORKBENCH, FORGE, SHELTER, BRIDGE, DOOR, FENCE
- **Demolition:** `POST /rest/game/demolish` → removes structure, returns partial materials

#### API Extension

```
GET /rest/game/cellGrid/{cellObjectId}
→ Returns tileData JSON for the specified cell

POST /rest/game/cellGrid/generate/{cellObjectId}
→ Generates tileData for a cell (lazy generation on first visit)

POST /rest/game/build
→ Build structure at player's current sub-cell position
  Body: { "charId": "...", "structureType": "CAMPFIRE", "materials": [...] }

POST /rest/game/demolish
→ Demolish structure at player's current sub-cell position
  Body: { "charId": "...", "subCellX": 4, "subCellY": 2 }
```

Or, include `tileData` in the existing `/rest/game/situation/{charId}` response for the current cell (and optionally adjacent cells for smooth scrolling).

#### Client Rendering

Replace the flat-color 10x10 blocks in `cardGame.js` zoomed view with theme-aware sprite rendering:

```javascript
// For each sub-cell tile:
// 1. Resolve concept key (e.g., "terrain.FOREST.base") via rpgAssetLoader
// 2. Draw base terrain sprite from theme atlas
// 3. Draw structure sprite if present (player-built)
// 4. Draw prop/POI sprite if present
// 5. Draw entity sprite if character/NPC/animal is in this sub-cell
// 6. Draw fog overlay if sub-cell is unexplored
```

Use HTML5 Canvas for sprite rendering (already proven with HypnoCanvas). Theme atlas loaded via `rpgAssetLoader.js`; individual tiles drawn via `drawImage()` with source rectangle. Concept keys ensure the renderer is theme-agnostic.

---

## Asset Library Strategy

### Architecture Principle — Theme-Swappable Assets

All game code references **concept keys** (e.g., `terrain.FOREST.base`, `character.HUMAN.walkN`, `sfx.attack_hit`), never file paths or pack-specific asset names. A **theme manifest** (`theme.json`) maps concept keys to actual asset files. Switching the entire visual/audio identity of the game means changing one config value and providing a new theme directory. AI-generated assets (via the existing SD pipeline) can serve as a complete alternative theme or fill gaps in an incomplete pack.

**Theme Directory Structure:**

```
assets/rpg/themes/
├── ninja-adventure/          ← default theme (CC0 pack)
│   ├── theme.json            ← manifest mapping concept keys → files
│   ├── terrain/
│   ├── characters/
│   ├── monsters/
│   ├── items/
│   ├── ui/
│   └── audio/
├── generated/                ← AI-generated theme (SD pipeline)
│   ├── theme.json
│   └── ...                   ← same structure, generated assets
└── fallback/                 ← minimal placeholder assets
    ├── theme.json
    └── ...                   ← colored rectangles, beep SFX
```

**Theme Manifest Format (`theme.json`):**

```json
{
  "name": "Ninja Adventure",
  "version": "1.0",
  "tileSize": 32,
  "terrain": {
    "FOREST": {
      "base": ["forest_grass_1.png", "forest_grass_2.png"],
      "tree": ["tree_oak.png", "tree_pine.png", "tree_birch.png"],
      "transition": { "LAKE": "forest_shore.png", "MOUNTAIN": "forest_rock_edge.png" }
    },
    "LAKE": {
      "base": ["water_1.png", "water_2.png"],
      "shore": ["shore_n.png", "shore_s.png", "shore_e.png", "shore_w.png"],
      "animated": { "water_1": { "frames": 4, "speed": 500 } }
    }
  },
  "characters": {
    "HUMAN": { "walkN": "human_walk_n.png", "walkS": "human_walk_s.png", "idle": "human_idle.png" },
    "ELF": { "walkN": "elf_walk_n.png", "walkS": "elf_walk_s.png", "idle": "elf_idle.png" }
  },
  "monsters": {
    "WOLF": { "idle": "wolf_idle.png", "attack": "wolf_attack.png" },
    "SKELETON": { "idle": "skeleton_idle.png", "attack": "skeleton_attack.png" }
  },
  "items": {
    "SWORD": "sword_iron.png",
    "POTION_HEALTH": "potion_red.png"
  },
  "ui": {
    "panel": "ui_panel.png",
    "button": "ui_button.png",
    "healthBar": "ui_health_bar.png"
  },
  "audio": {
    "music": { "FOREST": "forest_ambient.ogg", "COMBAT": "battle_theme.ogg" },
    "sfx": { "attack_hit": "hit.ogg", "attack_miss": "miss.ogg", "item_pickup": "pickup.ogg" }
  }
}
```

**Asset Resolution Chain (`rpgAssetLoader.js`):**

1. Look up concept key in **active theme** manifest. If asset file exists, use it.
2. If missing, look up concept key in **generated** theme. If asset file exists, use it. If not, trigger SD generation request (`POST /rest/game/asset/generate`) and cache result.
3. If both miss, fall back to **fallback** theme (guaranteed complete — uses colored rectangles and placeholder audio).

**Dynamic Asset Generation:** When a concept key has no static asset in the active or generated theme, the SD pipeline generates one on demand via `POST /rest/game/asset/generate` and caches the result in the `generated/` theme directory. A style prompt template per theme controls the visual identity (e.g., "16-bit pixel art, top-down RPG style" for the default theme).

**Theme Completeness Validator:** A build-step script (`validateTheme.js`) checks a theme manifest against the required concept key list. Reports missing keys, allowing theme authors to see gaps before deployment. Missing keys are not fatal — the resolution chain handles them at runtime.

**Runtime Theme Switching:** The active theme is a client-side config value (`rpgConfig.activeTheme`). Changing it reloads manifests, re-requests tiles from the server, and invalidates the service worker cache for the old theme. Players can switch themes at any time from the settings menu without restarting.

### Default Theme: Ninja Adventure (CC0)

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

### Asset Pipeline (Theme-Aware)

```
Theme Directory (chosen by rpgConfig.activeTheme)
    ↓
theme.json manifest maps concept keys → asset files
    ↓
Build step: spritesheet → individual tile extraction + atlas per theme
    ↓
Served via: /media/ endpoint (static) or /rest/game/asset/generate (SD pipeline)
    ↓
Client loads: rpgAssetLoader resolves concept keys → theme atlas → Canvas drawImage()
    ↓
Service worker caches per theme (invalidated on theme switch)
```

**Tile Atlas Format (per theme):**
```json
{
  "tileSize": 32,
  "atlas": "ninja-adventure-terrain.png",
  "tiles": {
    "terrain.CLEAR.base.0": {"x": 0, "y": 0},
    "terrain.CLEAR.base.1": {"x": 32, "y": 0},
    "terrain.CLEAR.base.2": {"x": 64, "y": 0},
    "terrain.FOREST.tree.oak": {"x": 0, "y": 32},
    "terrain.FOREST.tree.pine": {"x": 32, "y": 32},
    "terrain.MOUNTAIN.boulder": {"x": 64, "y": 32},
    "terrain.LAKE.base.0": {"x": 0, "y": 64},
    "structure.CAMPFIRE": {"x": 32, "y": 64},
    "structure.WALL": {"x": 64, "y": 64}
  },
  "animations": {
    "terrain.LAKE.base.0": {"frames": [{"x": 0, "y": 64}, {"x": 96, "y": 64}], "speed": 500}
  }
}
```

**Adding a New Theme:**
1. Create a new directory under `assets/rpg/themes/{themeName}/`
2. Create `theme.json` mapping all concept keys to asset files
3. Run `validateTheme.js {themeName}` — reports missing concept keys
4. Fill gaps with SD-generated assets (`POST /rest/game/asset/generate`) or let the resolution chain handle them at runtime
5. Set `rpgConfig.activeTheme = "{themeName}"` to activate

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

### Skill Universe Template

The full set of learnable skills, organized by category and gated by world maturity tier. Skills are stored as `data.trait` records with `TraitEnumType.SKILL`. Each skill belongs to a category and has a minimum world maturity tier required to exist.

#### Skill Categories (`SkillCategoryEnumType`)

| Category | Description | Primary Stats |
|----------|-------------|---------------|
| COMBAT | Melee fighting techniques | physicalStrength, physicalEndurance |
| RANGED | Projectile and thrown weapons | agility, perception |
| DEFENSE | Blocking, dodging, armor use | physicalEndurance, agility |
| SURVIVAL | Wilderness skills, foraging, tracking | perception, wisdom |
| CRAFTING | Building, smithing, alchemy | intelligence, wisdom |
| SOCIAL | Persuasion, intimidation, deception | charisma, intelligence |
| STEALTH | Sneaking, lockpicking, pickpocketing | agility, perception |
| KNOWLEDGE | Lore, medicine, languages | intelligence, wisdom |
| MOVEMENT | Climbing, swimming, riding | agility, physicalStrength |
| MAGIC | Sorcery and channeling (4x learning cost) | magic, wisdom |

#### Skills by World Maturity Tier

| Maturity Tier | Available Skill Examples |
|---------------|------------------------|
| PRIMITIVE | Brawling, Thrown Weapons, Dodge, Foraging, Tracking, Fire-making, Tanning, Intimidation, Sneaking, Herbalism, Climbing, Swimming |
| ANCIENT | Sword, Spear, Shield Use, Bow, Hunting, Pottery, Weaving, Persuasion, Lockpicking, History, Animal Handling, Basic Sorcery |
| MEDIEVAL | Longsword, Crossbow, Heavy Armor, Parry, Farming, Blacksmithing, Alchemy, Diplomacy, Disguise, Medicine, Riding, Channeling |
| RENAISSANCE | Rapier, Musket, Fencing, Navigation, Gunsmithing, Engineering, Rhetoric, Espionage, Surgery, Cartography, Sail |
| MODERN | Firearms, Martial Arts, Tactical Armor, First Aid, Mechanics, Electronics, Negotiation, Hacking, Forensics, Driving, Piloting |

#### Learning Methods (`LearnMethodEnumType`)

| Method | Proficiency Gain Rate | Requirements |
|--------|----------------------|--------------|
| TEACHER | 15-25% of `(100 - current)` per session | NPC with skill proficiency > yours + FRIENDLY reputation |
| TEXT | 10-15% of `(100 - current)` per study session | Skill book/scroll item in inventory |
| EXPERIMENTATION | 5-10% of `(100 - current)` per attempt | Relevant materials + location (e.g., forge for smithing) |
| OBSERVATION | 5% of `(100 - current)` per observation | Witness NPC or player using the skill successfully |

Magic skills (MAGIC category) use the same methods but at **4x cost** per the `olio.txt` tabletop rules — gain rates are quartered.

#### Skill Data Model (on `data.trait`)

Each learned skill is a `data.trait` record with these fields:
- `name` — skill name (e.g., "Sword", "Blacksmithing", "Basic Sorcery")
- `traitType` = `SKILL`
- `proficiency` — current skill percentage (0-100, capped at 95)
- `lastUsed` — timestamp for decay calculation
- `usageCount` — total times used
- `learnedVia` — `LearnMethodEnumType` indicating how the skill was first acquired
- `learnedFrom` — reference to the teacher NPC, book item, or null
- `maturityTier` — minimum `TechnicalMaturityEnumType` required

#### NPC Skill Distribution

NPCs are generated with skills appropriate to their profession, terrain, and world maturity:
- A blacksmith NPC in a MEDIEVAL village has Blacksmithing 70-85%, Sword 40-50%, Commerce 60-70%
- A forest bandit has Sword 50-60%, Stealth 60-70%, Tracking 40-50%
- A village healer has Herbalism 70-80%, Medicine 60-70%, Persuasion 40-50%
- Skill distribution drives NPC shop inventory, teachable skills, and combat behavior

### Magic System (Seal-Based)

All magic in the game derives from ancient machine-like artifacts called **Seals**. Seals are physical objects located at specific positions in the world. They radiate magical energy within a radius. If a Seal is damaged or goes offline, all magic of that type fails within its radius. Magic is not innate — it is a technology powered by external infrastructure.

#### Seal Types (Schools of Magic)

| Seal Type | Element | Opposition Pair | Notes |
|-----------|---------|-----------------|-------|
| FIRE | Fire, heat, combustion | WATER | Classic elemental opposition |
| WATER | Water, ice, flow | FIRE | Classic elemental opposition |
| EARTH | Stone, metal, gravity | — | Partially balances VOID |
| LIFE | Healing, growth, vitality | DEATH | Biological opposition |
| DEATH | Decay, undeath, entropy | LIFE | Biological opposition |
| POLAR | Magnetism, lightning, force | SOLAR | Energy opposition |
| SOLAR | Light, radiance, truth | POLAR | Energy opposition |
| LUNAR | Illusion, shadow, dreams | — | Neutral/self-balancing |
| VOID | Annihilation, teleportation, chaos | — | Inherently unstable; EARTH provides partial balance |

#### Seal Placement in the World

Seals are `olio.seal` model records placed at specific world locations:

```
olio.seal
├── sealType (SealTypeEnumType: FIRE, WATER, EARTH, LIFE, DEATH, POLAR, LUNAR, SOLAR, VOID)
├── location (geoLocation FK — specific cell position)
├── alignment (double, 0-1 — health/stability of the Seal)
├── radiusKm (int — effective range in kilometers)
├── discoveredBy (list of charPerson — who has found this Seal)
├── guardians (list of olio.animal / charPerson — creatures protecting the Seal)
└── narrative (olio.narrative — lore, description, visual appearance)
```

Seals are rare — perhaps 1-3 per kident. Their locations are not marked on the map. Players discover them through exploration, NPC hints (reputation-gated information), or knowledge skills. A discovered Seal's location is added to the player's known Seal list.

#### Two Casting Paths

There are two fundamentally different ways to use Seal energy. Every magic user chooses (or learns) one or both paths.

##### Path 1: Sorcerer (Reagent Casting)

Sorcerers use **physical reagents** combined with **incantations** to channel Seal energy safely. The reagents act as a buffer — they absorb the energy, transform it, and release the spell effect. The caster never directly contacts the Seal's energy.

- **Reagents consumed on cast** — each spell has a specific reagent recipe
- **Supply chain dependent** — need access to reagent materials (commerce, crafting, foraging)
- **Safe but rigid** — cannot modify spells beyond what the recipe allows
- **No orientation required** — reagents handle the Seal connection
- **Learning:** Sorcery skills in MAGIC category, learned from teachers or spell books

**Reagent Mapping by Seal Type:**

| Seal Type | Primary Reagent | Secondary Reagent | Catalyst |
|-----------|----------------|-------------------|----------|
| FIRE | Magnesium | Sulfur | Hemp |
| WATER | Salt crystal | Mercury | Distilled moss |
| EARTH | Iron filings |ite clay | Root paste |
| LIFE | Fresh herbs | Spring water | Honey |
| DEATH | Bone meal | Grave soil | Ash |
| POLAR | Lodestone dust | Zinc filings | Silver wire |
| LUNAR | Moonstone dust | Selenite crystal | Nightshade oil |
| SOLAR | Gold leaf | Quartz crystal | Amber resin |
| VOID | ??? | ??? | ??? |

Void reagents are unknown — no reliable sorcery recipe for Void magic exists. This is intentional: Void magic can only be accessed through Channeling.

##### Path 2: Channeler (Direct Channeling)

Channelers approach magic as a **science**. They measure Seal energy in amperes and coulombs, calculate vectors to Seal locations, and directly channel raw energy through their body. This is powerful but dangerous.

- **Must orient to Seal** — either be in proximity or calculate the correct directional vector (using compass + almanac or a Statlass instrument)
- **Must balance opposing channels simultaneously** — casting a FIRE spell requires holding a WATER counter-channel to prevent energy overflow
- **Failure = The Burn** — if balance fails, the caster takes direct physical damage matching the element (fire burns, ice frostbite, death necrosis, void... disintegration)
- **No reagent cost** — can cast indefinitely if skilled enough
- **Highly modifiable** — can adjust spell intensity, area, duration on the fly
- **Learning:** Channeling skills in MAGIC category, 4x learning cost

**Channeling Tools:**

| Tool | Effect | Acquisition |
|------|--------|-------------|
| Statlass | +15% balance accuracy | Crafted (requires high crafting + knowledge skill) |
| Compass + Almanac | +10% orientation accuracy | Purchased or found |
| Channeling Gloves | -50% Burn damage on failure | Crafted (rare materials) |
| Dragon Balm | -25% Burn damage, consumable | Crafted (alchemy) or purchased |

**Void Channeling:** VOID has no true opposition pair. Channelers attempting Void magic must use EARTH as a partial balance (50% effectiveness), making Void spells inherently dangerous. Even master Channelers risk catastrophic Burn when working with Void energy.

#### Spell Mechanics

Spells are `olio.spell` model records defining what each spell does and how to cast it:

```
olio.spell
├── name (string — e.g., "Fireball", "Heal", "Stone Wall")
├── sealTypes (list of SealTypeEnumType — which Seals power this spell)
├── difficulty (int, 1-20 — base difficulty for casting)
├── effect (olio.actionResult template — what the spell does)
├── sorcererReagents (list of {itemType, quantity} — recipe for Sorcerer path)
├── channelerBalancePairs (list of {primary, opposition} — balance requirements for Channeler path)
├── weaveTier (int, 0-5 — complexity tier)
├── modifiable (boolean — can Channelers modify this spell's parameters?)
├── prerequisiteSkills (map of skillName -> minProficiency)
└── description (string — narrative description)
```

##### Single-Channel Spells (Tier 0-1)

Simple spells drawing from one Seal type. Sorcerers use one reagent set; Channelers balance against the opposition element.
- **Tier 0:** Cantrips — minimal effect, low difficulty, good for practice (e.g., spark, chill touch, minor light)
- **Tier 1:** Standard spells — single target, moderate effect (e.g., fireball, heal wound, stone shield)

##### Weave Spells (Tier 2-5)

Complex spells drawing from **multiple Seal types simultaneously**. Require weaving multiple energy channels. Difficulty scales with tier:

| Weave Tier | Seal Types Required | Difficulty Multiplier | Example |
|------------|--------------------|-----------------------|---------|
| 2 | 2 Seal types | 1x base difficulty | Fire + Earth = Lava Wall |
| 3 | 3 Seal types | 3x base difficulty | Fire + Life + Solar = Phoenix Resurrection |
| 4 | 4 Seal types | 6x base difficulty | Water + Death + Lunar + Polar = Spectral Storm |
| 5 | 5+ Seal types | 12x base difficulty | Requires mastery of multiple schools; legendary spells |

Sorcerers at Tier 2+ need all reagent sets simultaneously. Channelers at Tier 2+ must balance multiple opposition pairs at once — exponentially harder.

##### Spell Modification (Channeler Only)

Channelers with `modifiable: true` spells can adjust parameters at cast time:
- **Intensity:** More power = higher difficulty, more Burn risk on failure
- **Target:** Redirect from single to area, or increase range
- **Duration:** Extend effect duration at higher energy cost
- **Area:** Expand area of effect (each doubling = +2 difficulty)

Sorcerers cannot modify spells — the reagent recipe produces a fixed effect.

#### Seal Disruption (World-Level Event)

Seals can be damaged by combat, natural disasters, or deliberate sabotage. When a Seal's alignment drops, magic dependent on that Seal degrades:

| Alignment Range | Status | Effect |
|----------------|--------|--------|
| 1.0 - 0.7 | Full | All spells function normally |
| 0.7 - 0.4 | Degraded | Spell difficulty +5, Burn damage doubled |
| 0.4 - 0.1 | Unstable | Spell difficulty +10, random misfires, Burn damage tripled |
| < 0.1 | Offline | All spells of this Seal type fail completely |

Seal disruption is a **world-level event** — it affects every magic user in the Seal's radius. Repairing a Seal requires rare materials, high magic skill, and physical access to the Seal location (which may be guarded by hostile creatures).

Seal disruption pushes `game.realm.event` with type `seal.disrupted` to all players in the affected area.

#### Integration with Existing Systems

- **Action resolution:** Spell casting routes through the `InteractionAction` pipeline. The 2d20 roll uses magic stat + spell difficulty + balance modifiers. Outcome determines spell success and Burn severity on failure.
- **Skill system:** Magic skills follow the same proficiency/decay model as all other skills, but at **4x learning cost** per the `olio.txt` tabletop rules. A character must invest heavily to become a competent magic user.
- **Economy:** Reagents are a major economic driver. Rare reagents (Moonstone dust, Gold leaf, Dragon Balm ingredients) create trade demand. Sorcerers need a reliable supply chain; Channelers need expensive tools.
- **Creature encounters:** Seal guardians appear in encounter tables for cells near Seals. These are typically BOSS-behavior creatures of the matching element.
- **Social perception:** Sorcery is generally legal and accepted — it is regulated, predictable, and safe. Channeling is controversial — it is powerful but dangerous, and communities may distrust Channelers. Void magic of any kind is universally feared and may provoke hostile reactions from NPCs regardless of reputation.

### Multiplayer Coordination

Multiple players share a realm and interact in real-time. The server is authoritative — all game state, turn resolution, and visibility calculations happen server-side. Clients receive updates exclusively via WebSocket push events.

#### Realm Sessions

A realm session is the multiplayer container. When a player starts or joins a game, they enter a realm session:

- **Create realm:** First player creates a realm → gets a realm session ID. Can share this ID with others.
- **Join realm:** Other players join by realm session ID → their character is placed in the realm's starting area
- **Leave realm:** Player disconnects or explicitly leaves → character persists in-world (can be set to "idle" or "sheltered")
- **Reconnect:** Player reconnects → resumes control of their character in its current position

**Realm Session State (`olio.realmSession`):**
```
olio.realmSession
├── realm (olio.realm FK)
├── activePlayers (list of system.user, participation: session.player)
├── maxPlayers (int, default 6)
├── status (RealmSessionStatusEnumType: LOBBY, ACTIVE, PAUSED)
├── createdBy (system.user FK)
├── turnMode (TurnModeEnumType: REAL_TIME, SIMULTANEOUS, SEQUENTIAL)
└── vatsTimeout (int, seconds, default 60)
```

#### Turn Modes

| Mode | Behavior | Best For |
|------|----------|----------|
| REAL_TIME | Overwatch runs continuously. VATS pauses only affected players. Others keep moving. | Exploration-heavy, casual co-op |
| SIMULTANEOUS | At decision points, ALL players in range pause. All submit actions. All resolve at once (initiative order). | Tactical co-op, party combat |
| SEQUENTIAL | At decision points, players take turns in initiative order (`reaction` stat). Other players see each action resolve before acting. | Strategic, turn-based purists |

Default is REAL_TIME — matching the existing single-player VATS hybrid design. The realm creator sets the mode; it can be changed between encounters.

#### Player Visibility & Interaction

Players see each other on the tile map when within perception range (same rules as NPCs):

- **Nearby players** (within perception radius): Full sprite + name label on tile grid
- **Same cell, different sub-cell**: Visible on zoomed cell view
- **Different cell**: Visible on area grid if within perception range
- **Different feature/kident**: Not visible (too far)

Player-to-player interaction uses the same `InteractionEnumType` system:

| Interaction | Mechanic | VATS Behavior |
|------------|----------|---------------|
| COMMUNICATE | Direct player-to-player text chat (WebSocket, no LLM) | No VATS pause — real-time chat |
| COOPERATE | Form cross-player party (reputation-gated, same as NPC) | No VATS pause |
| COMMERCE | Trade items between player inventories | VATS pause for both — both must confirm |
| COMBAT (PvP) | PvP combat through existing `InteractionAction` pipeline | VATS pause for both — action selection |
| BEFRIEND | Increase cross-player reputation through positive interaction | No VATS pause |

#### Cross-Player Party

Players can form parties with each other (and with NPCs). The same reputation system applies:
- Players start at NEUTRAL (+0) reputation with each other
- Cooperative actions (fighting together, trading, chatting) increase reputation
- PvP, theft, or betrayal decrease reputation
- At +30 reputation, players can formally join each other's party
- Mixed party: some members are players, some are NPC followers

When a cross-player party enters combat, ALL player members get VATS pauses. Actions resolve in initiative order.

#### PvP Rules

PvP is opt-in at the realm session level:
- **PvP disabled** (default): Players cannot initiate COMBAT interactions with each other. All other interactions work.
- **PvP enabled**: Players can attack each other. Standard VATS mechanics apply — both players get action selection. Reputation consequences apply (attacking reduces reputation with the target and any witnesses).
- **PvP dueling**: Players can challenge each other to a formal duel (no reputation penalty, no loot loss). Both must accept.

#### Shared World Events

Realm-wide events (weather, invasions, seasonal changes) affect all players simultaneously. The Overwatch engine processes these during `processEvents()` and pushes `game.realm.event` to all realm subscribers. Players in different parts of the realm may experience different local effects (a storm in the northern kident doesn't affect the southern village).

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

### Death and Permadeath

Death is permanent. When a character dies (health reaches zero from any cause — combat, starvation, environmental damage, magic), the `charPerson` record is marked dead and excluded from all group dynamics, party membership, and realm participation.

**Cascade Effects:**
- NPC relationships shift: allies mourn, enemies celebrate, neutral NPCs reassess the power balance
- Party members react based on personality: loyal members may seek vengeance, pragmatic members redistribute gear, cowardly members flee
- Social hierarchies in the area collapse and reform — the dead character's reputation with every NPC becomes frozen history
- Inventory drops at the death location as a lootable container (`olio.store`), accessible by anyone who visits the sub-cell

**Single Player:**
- Save game works normally — the player can reload a save from before death
- Death is a meaningful consequence but not permanently punishing in single-player

**Multiplayer:**
- Only the latest auto-save is available (auto-saved at each Overwatch increment)
- The player **cannot undo death** by reloading — the auto-save reflects the dead state
- The player must create a new character and enter the realm fresh
- The new character enters a world shaped by the previous character's impact: reputation with NPCs resets, but the world state (built structures, NPC dispositions toward other players, quest progress) persists
- Other players see `game.npc.died` event for the dead character

### Economy and Commerce

**Currency:** At `PRIMITIVE` world maturity, all trade is barter — items exchanged directly based on perceived value. At `MEDIEVAL` and above, coinage exists as `olio.item` records with `itemType: CURRENCY` and denomination values. Currency items are standard inventory entries managed through `olio.store`.

**NPC Pricing:**
```
finalPrice = baseValue * supplyModifier * reputationModifier * needModifier

supplyModifier:   0.5 (oversupply) to 2.0 (scarcity) based on NPC shop inventory
needModifier:     0.8 (NPC doesn't want it) to 1.5 (NPC desperately needs it)
reputationModifier per tier:
  HOSTILE:    2.0x  (double price / half sell value)
  GUARDED:    1.5x
  NEUTRAL:    1.0x
  FRIENDLY:   0.85x
  ALLIED:     0.7x
  DEVOTED:    0.5x  (half price / full sell value)
```

**NPC Shop Inventory:**
Generated from NPC profession, local terrain, world maturity tier, and NPC crafting skill:
- Blacksmith in FOREST village: iron tools, basic weapons, nails
- Herbalist in MARSH: potions, poultices, medicinal herbs
- Trader in DESERT oasis: water skins, dried food, maps, rare imports
- Inventory refreshes periodically during Overwatch increments based on NPC production and trade routes

**Crafting Integration:**
Uses the existing `olio.builder` system. Crafting requires:
- Materials (specific `olio.item` entries consumed from inventory)
- Skill (minimum crafting proficiency for the recipe)
- Location (some recipes require a forge, workbench, or other structure — ties into player construction)
- Time (crafting actions have duration, processed through Overwatch)

**Build/Craft Action (`Build.java`):**
The `build` action provider implements the full crafting lifecycle as a 3-phase action (begin → execute → conclude):
1. **beginAction** — Resolves the builder (by name via `itemName` param, or pre-set by NeedsUtil for AI-driven needs). Validates terrain compatibility (`builder.terrain[]` vs actor's current cell), required skills (`builder.skills[]` vs actor's `trades`), and material availability. Calculates build time adjusted by actor's skill level.
2. **executeAction** — Waits for dependent actions (e.g., movement). Consumes materials from actor inventory via `ItemUtil.withdrawItemFromInventory()`. Produces output based on builder type (ITEM/APPAREL/WEARABLE/LOCATION/FIXTURE/BUILDER) and deposits into actor's store. Sets `retailValue` on produced items via the pricing formula.
3. **concludeAction** — Applies action effects (energy, fatigue).

Builder types and their outputs:
| Type | Output | Destination |
|------|--------|-------------|
| ITEM | Clone from `builder.item` template | Actor's `store.inventory` |
| APPAREL | Clone from `builder.apparel` template | Actor's `store.apparel` |
| WEARABLE | Single wearable item | Actor's `store.items` |
| LOCATION | Structure (shelter, cistern) | Actor's cell / `store.locations` |
| FIXTURE | Interactive cell object (well, road) | Current cell as POI with attached store |
| BUILDER | Meta-craft (tools, skills) | New builder/skill capability |

Quality scaling: The builder's `qualities[0].skill` (0.0–1.0) determines how much crafter skill affects output quality. Low-skill items (rudimentary shelter, skill=0.1) barely vary; high-skill items (atlatl, skill=0.3) reward better crafters with better stats.

**Formulaic Pricing System (`PriceUtil.java`):**
A baseline transactional pricing model designed to be eventually replaced by organically evolved commerce from simulation. All prices derive from a minimum wage constant, modified by skill, materials, artistry, and rarity:

```
retailValue = (laborCost + materialCost) * qualityModifier * artistryModifier * rarityModifier
```

| Component | Formula | Description |
|-----------|---------|-------------|
| laborCost | `MINIMUM_WAGE_PER_MINUTE × builder.time` | Base cost of labor |
| materialCost | `Σ(material.retailValue)` | Sum of material values (raw materials use `RAW_MATERIAL_BASE_VALUE`) |
| qualityModifier | `QUALITY_FLOOR + (1 - QUALITY_FLOOR) × skillMatch` | Actor's avg build stats / max stat. Floor=0.3 prevents zero-value items |
| artistryModifier | `1.0 + (favorableRatio - 0.5) × ARTISTRY_WEIGHT` | Ratio of favorable interaction outcomes. Clamped [0.5, 2.0] |
| rarityModifier | `1.0 + (1.0 - skillPrevalence) × RARITY_WEIGHT` | Inverse of how many characters have required skills. Clamped [1.0, 3.0] |

Constants (in `Rules.java`): `MINIMUM_WAGE_PER_MINUTE=0.25`, `RAW_MATERIAL_BASE_VALUE=1.0`, `QUALITY_FLOOR=0.3`, `ARTISTRY_WEIGHT=1.0`, `RARITY_WEIGHT=1.0`, `RESALE_DEFAULT=0.5`.

**Design Note — Evolved Commerce:**
The formulaic pricing above is a placeholder. The long-term vision is that commerce emerges organically from simulated evolution: take 1–3 locations with small populations (50–100), run them through the Olio Evolve system over simulated centuries, and let trade systems develop from needs-driven interactions, resource scarcity, skill specialization, and social dynamics. The current system provides a working transactional baseline while the evolution simulation matures.

**Player-to-Player Trade:**
Direct barter between players. Both players see the offered items, both must confirm. Reputation between the trading players adjusts positively on successful trades. No currency required — items are exchanged directly from `olio.store` to `olio.store`.

### Creature Encounters and Behavior

**Encounter Tables (per terrain):**

Each `TerrainEnumType` has an encounter table with weighted creature entries:

| Rarity | Weight | Typical Encounter |
|--------|--------|-------------------|
| COMMON | 60% | Wolves, rats, snakes, bandits |
| UNCOMMON | 25% | Bears, giant spiders, orc scouts |
| RARE | 12% | Trolls, wyverns, necromancers |
| LEGENDARY | 3% | Dragons, liches, ancient golems |

**Example Encounter Tables:**

| Terrain | Common | Uncommon | Rare | Legendary |
|---------|--------|----------|------|-----------|
| FOREST | Wolf, Boar, Bandit | Bear, Giant Spider, Dryad | Troll, Werewolf | Ancient Treant |
| MOUNTAIN | Mountain Goat, Eagle, Orc | Yeti, Stone Golem, Wyvern | Rock Dragon, Giant | Mountain Titan |
| DESERT | Scorpion, Sand Viper, Jackal | Dust Devil, Mummy, Sandworm | Sphinx, Djinn | Desert Wyrm |
| LAKE | Fish, Frog, Water Snake | Nixie, Crocodile, Siren | Sea Serpent, Water Elemental | Kraken |
| CAVE | Bat, Rat, Goblin | Cave Troll, Basilisk, Myconid | Umber Hulk, Beholder | Ancient Dragon |
| MARSH | Leech, Bog Rat, Will-o-Wisp | Hydra, Swamp Hag, Lizardfolk | Black Dragon, Lich | Swamp Colossus |

**Difficulty Scaling:**
Difficulty is **geographic, not level-based**. The starting kident contains only COMMON encounters. Adjacent kidents introduce UNCOMMON. Distant kidents have the full table including RARE and LEGENDARY. This creates natural difficulty zones without artificial level scaling.

**Creature Behavior Patterns (`BehaviorPatternEnumType`):**

| Pattern | Behavior | Example |
|---------|----------|---------|
| TERRITORIAL | Attacks if player enters their sub-cell range, does not pursue far | Bears, Trolls |
| PREDATORY | Actively hunts player, pursues across cells | Wolves, Wyverns |
| PACK | Calls nearby allies when engaged, coordinates attacks | Wolves, Orcs, Goblins |
| DEFENSIVE | Only attacks if attacked first or cornered | Deer, Treants, Golems |
| FLEE | Runs from player if player's stats exceed a threshold | Rats, Rabbits, low-HP creatures |
| BOSS | Does not flee, has special attack patterns, guards a location | Dragons, Titans, Liches |

**Integration:** Creature behavior is driven by the existing `olio.instinct` system. A wolf with high `instinct.hunt` and `instinct.pack` exhibits PREDATORY + PACK behavior. Behavior patterns are the default; instinct values modify the specifics. Monster stats use the same `olio.statistics` model as characters — there is no separate monster stat system.

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

### Responsive & App-Ready Design

The RPG client adapts to three form factors from a single codebase. All layouts use the same components and REST API calls — only sizing, positioning, and input handling change.

#### Layout Breakpoints

| Breakpoint | Target | Layout | Tile Size | UI Mode |
|-----------|--------|--------|-----------|---------|
| ≥ 1200px | Desktop | Three-panel (map + sidebar + bottom bar) | 48px or 64px | Full — all panels visible |
| 768–1199px | Tablet | Two-panel (map + collapsible sidebar) | 32px or 48px | Compact — sidebar toggles, panels stack |
| < 768px | Mobile | Single-panel (map fullscreen, overlays) | 32px | Minimal — slide-out panels, bottom nav |

#### Input Modes

| Platform | Movement | Action Selection | Menu/Inventory |
|----------|----------|-----------------|----------------|
| Desktop | WASD / arrow keys / click tile | Click action button / hotkeys | Click sidebar panels |
| Tablet | Touch d-pad overlay / tap tile | Tap action button in VATS overlay | Tap sidebar toggle, swipe panels |
| Mobile | Touch d-pad (thumb zone) / swipe | Tap action in fullscreen VATS overlay | Bottom nav tabs, slide-up sheets |

**Touch d-pad:** Virtual directional pad rendered in the bottom-left corner on touch devices. 8-way input matching the existing compass system. Sized for thumb reach (~120px diameter on mobile, ~160px on tablet). Only visible on touch-capable devices (detected via `'ontouchstart' in window`).

**Pinch-zoom on tile map:** Canvas supports pinch-to-zoom between 1x and 3x magnification on the tile grid. Zoom level adjusts which sub-cell tiles are visible (fewer tiles at higher zoom = more detail). Mouse wheel zoom on desktop.

#### Responsive Component Patterns

Each UI panel (character stats, inventory, needs journal, party, skills) follows the same responsive pattern:

- **Desktop:** Rendered inline in the sidebar. Always visible. Full detail.
- **Tablet:** Rendered in a collapsible sidebar. Tap to expand/collapse. Medium detail.
- **Mobile:** Rendered as a slide-up bottom sheet or fullscreen overlay. Accessed via bottom navigation tab. Compact detail with expandable sections.

The VATS overlay is fullscreen on all form factors — it is the primary decision point and demands full attention regardless of screen size.

#### PWA Shell

```
manifest.json:
  name: "AM7 RPG"
  short_name: "AM7 RPG"
  display: "standalone"
  orientation: "any"
  theme_color: "#1a1a2e"
  background_color: "#0f0f23"
  icons: [192x192, 512x512]
  start_url: "/rpg"

service-worker.js:
  Cache strategy:
    - Static assets (tile atlases, spritesheets, audio): Cache-first, long TTL
    - REST API calls: Network-first, fallback to "server unavailable" UI
    - Game state: Not cached (server is source of truth)
  Install prompt: Triggered after first game session
```

#### CSS Architecture

A single `rpgLayout.css` file using CSS Grid + CSS custom properties for responsive theming:

```css
.rpg-app {
  --tile-size: 48px;          /* overridden per breakpoint */
  --sidebar-width: 320px;     /* collapses on mobile */
  --panel-padding: 12px;
  display: grid;
  grid-template: "map sidebar" 1fr / 1fr var(--sidebar-width);
}
@media (max-width: 1199px) { /* tablet */
  .rpg-app { --tile-size: 32px; --sidebar-width: 280px; }
}
@media (max-width: 767px) {  /* mobile */
  .rpg-app {
    --tile-size: 32px;
    grid-template: "map" 1fr / 1fr;  /* sidebar becomes overlay */
  }
}
```

### WebSocket Real-Time Event System

All intra-game status updates flow through the WebSocket connection at `/wss`. The existing `GameStreamHandler` implements a session-subscription model; the RPG extends this with new event types for multiplayer, VATS, reputation, and skill progression. REST is for commands (player initiates an action); WebSocket is for notifications (server tells clients what happened).

#### Existing Events (GameStreamHandler — already implemented)

| Event | Direction | Trigger | Payload |
|-------|-----------|---------|---------|
| `game.action.start` | Server → Client | Action begins executing | `{actionType, charId}` |
| `game.action.progress` | Server → Client | Action progress update | `{progress: 0-100, currentState}` |
| `game.action.complete` | Server → Client | Action resolved | `{situation}` (full situation snapshot) |
| `game.action.error` | Server → Client | Action failed | `{message}` |
| `game.action.interrupt` | Server → Client | Action interrupted by threat/event | `{interruptData}` |
| `game.threat.detected` | Server → Client | Hostile enters proximity | `{threatId, threatType, distance}` |
| `game.threat.removed` | Server → Client | Threat no longer in proximity | `{threatId}` |
| `game.threat.changed` | Server → Client | Threat state changed | `{threatId, newState}` |
| `game.npc.moved` | Server → Client | NPC position changed | `{npcId, position}` |
| `game.npc.action` | Server → Client | NPC performed action | `{npcId, actionType, outcome}` |
| `game.npc.died` | Server → Client | NPC died | `{npcId}` |
| `game.interaction.start` | Server → Client | Interaction initiated | `{interactionType, participants}` |
| `game.interaction.phase` | Server → Client | Interaction phase change | `{phase, data}` |
| `game.interaction.end` | Server → Client | Interaction resolved | `{outcome}` |
| `game.time.advanced` | Server → Client | Game clock advanced | `{currentTime}` |
| `game.increment.end` | Server → Client | Overwatch increment completed | `{summary}` |
| `game.event.occurred` | Server → Client | World event triggered | `{eventType, data}` |
| `game.state.changed` | Server → Client | Character state updated | `{stateData}` |
| `game.situation.update` | Server → Client | Full situation refresh | `{situation}` |

#### New Events — VATS & Combat (Phase 2)

| Event | Direction | Trigger | Payload |
|-------|-----------|---------|---------|
| `game.vats.pause` | Server → Client | Decision point detected | `{threats[], npcs[], context, availableActions[]}` |
| `game.vats.resume` | Server → Client | VATS resolved, world resumes | `{outcomes[]}` |
| `game.vats.timeout` | Server → Client | Player did not act within timeout | `{defaultAction}` |

#### New Events — Reputation (Phase 2)

| Event | Direction | Trigger | Payload |
|-------|-----------|---------|---------|
| `game.reputation.changed` | Server → Client | Reputation score updated | `{actorId, targetId, oldScore, newScore, tier}` |
| `game.reputation.tier.changed` | Server → Client | Reputation crossed tier boundary | `{actorId, targetId, oldTier, newTier}` |

#### New Events — Party (Phase 3)

| Event | Direction | Trigger | Payload |
|-------|-----------|---------|---------|
| `game.party.join.request` | Server → Client | NPC wants to join party | `{npcId, reputation, personality}` |
| `game.party.member.joined` | Server → Client | Member added to party | `{memberId, partyId}` |
| `game.party.member.left` | Server → Client | Member left/deserted | `{memberId, reason}` |
| `game.party.member.betrayed` | Server → Client | Member betrayed party | `{memberId, action}` |

#### New Events — Skills (Phase 4)

| Event | Direction | Trigger | Payload |
|-------|-----------|---------|---------|
| `game.skill.gained` | Server → Client | Skill proficiency increased | `{skillName, oldProf, newProf}` |
| `game.skill.decayed` | Server → Client | Skill proficiency decreased from disuse | `{skillName, oldProf, newProf}` |
| `game.milestone.reached` | Server → Client | Milestone achieved, attribute points available | `{milestone, pointsGranted}` |

#### New Events — Multiplayer (Phase 5)

| Event | Direction | Trigger | Payload |
|-------|-----------|---------|---------|
| `game.player.joined` | Server → All in realm | Player enters realm | `{playerId, playerName, charId, position}` |
| `game.player.left` | Server → All in realm | Player leaves/disconnects | `{playerId, reason}` |
| `game.player.moved` | Server → Nearby players | Player position changed | `{playerId, charId, position, cellId}` |
| `game.player.action` | Server → Nearby players | Player performed visible action | `{playerId, actionType, outcome}` |
| `game.vats.playerReady` | Server → Affected players | A player committed their VATS action | `{playerId, actionCommitted: true}` |
| `game.vats.allReady` | Server → Affected players | All players committed, resolving | `{playerActions[]}` |
| `game.trade.request` | Server → Target player | Player initiated trade | `{fromPlayerId, offeredItems[]}` |
| `game.trade.accepted` | Server → Both players | Trade completed | `{items exchanged}` |
| `game.trade.rejected` | Server → Initiator | Trade declined | `{reason}` |
| `game.pvp.challenge` | Server → Target player | PvP initiated | `{challengerId, context}` |
| `game.realm.event` | Server → All in realm | Realm-wide event | `{eventType, affectedArea, data}` |

#### Subscription Model

The existing `GameStreamHandler` subscription model extends naturally for multiplayer:

```
Single-player (existing):
  Client subscribes to charId → receives all events for that character

Multiplayer (extended):
  Client subscribes to charId → receives character-specific events (same as today)
  Client subscribes to realmId → receives realm-wide events (player join/leave, world events)
  Server auto-subscribes to nearby players → receives movement/action events for players in range
  Nearby-player subscriptions update as the player moves (server manages automatically)
```

**Message Format (unchanged from existing):**
```json
// Client → Server (action request)
{"actionId": "uuid", "actionType": "move", "params": {"charId": "...", "direction": "NORTH"}}

// Client → Server (subscription)
{"subscribe": true, "charId": "..."}
{"subscribe": true, "realmId": "..."}

// Server → Client (push event)
["game.player.moved", "charId", "{\"playerId\":\"...\",\"position\":{...}}"]

// Server → Client (action response)
["game.action.complete", "actionId", "{\"situation\":{...}}"]
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

olio.realmSession (NEW — multiplayer session container)
├── realm (olio.realm FK)
├── activePlayers (list of system.user, participation: session.player)
├── maxPlayers (int, default 6)
├── status (RealmSessionStatusEnumType: LOBBY, ACTIVE, PAUSED)
├── createdBy (system.user FK)
├── turnMode (TurnModeEnumType: REAL_TIME, SIMULTANEOUS, SEQUENTIAL)
├── vatsTimeout (int, seconds, default 60)
├── pvpEnabled (boolean, default false)
└── sessionCode (string, 6-char shareable join code)

olio.playerPresence (NEW — tracks active player in a realm session)
├── user (system.user FK)
├── character (charPerson FK)
├── realmSession (olio.realmSession FK)
├── status (PlayerStatusEnumType: ACTIVE, IDLE, DISCONNECTED, SHELTERED)
├── lastHeartbeat (zonetime)
├── joinedAt (zonetime)
└── position (geoLocation FK — cached for fast nearby-player queries)

olio.seal (NEW — physical magic source in the world)
├── sealType (SealTypeEnumType)
├── location (geoLocation FK)
├── alignment (double, 0-1)
├── radiusKm (int)
├── discoveredBy (list of charPerson)
├── guardians (list of olio.animal / charPerson)
└── narrative (olio.narrative)

olio.spell (NEW — spell definition)
├── name (string)
├── sealTypes (list of SealTypeEnumType)
├── difficulty (int, 1-20)
├── effect (olio.actionResult template)
├── sorcererReagents (list of {itemType, quantity})
├── channelerBalancePairs (list of {primary, opposition})
├── weaveTier (int, 0-5)
├── modifiable (boolean)
├── prerequisiteSkills (map of skillName -> minProficiency)
└── description (string)

olio.skillUniverse (NEW — skill catalog per world maturity)
├── name (string)
├── category (SkillCategoryEnumType)
├── maturityFloor (TechnicalMaturityEnumType)
├── primaryStats (list of string)
├── prerequisites (list of string)
├── learnableMethods (list of LearnMethodEnumType)
├── baseDifficulty (int, 1-20)
└── description (string)

olio.encounterTable (NEW — per-terrain creature spawning)
├── terrain (TerrainEnumType)
├── entries (list with creature, rarity, weight, groupSize, behaviorPattern)
└── baseEncounterRate (double)

Extensions to existing models:
  data.trait (add fields):
  ├── proficiency (double, 0-100)  — current skill proficiency %
  ├── lastUsed (zonetime)          — for decay calculation
  ├── usageCount (int)             — total times used
  ├── learnedVia (LearnMethodEnumType) — how the skill was acquired
  ├── learnedFrom (charPerson FK or item FK) — teacher or book reference
  └── maturityTier (TechnicalMaturityEnumType) — minimum world maturity

  olio.state (add fields):
  ├── exploredCells (string)       — packed bitfield of explored cell IDs
  └── milestones (list of string)  — milestone keys for attribute point grants

  data.geoLocation (add field):
  └── tileData (string, JSON)      — packed 10x10 sub-cell grid with concept keys

  olio.party (extend fields):
  └── playerMembers (list of system.user, participation: party.player)
      — supports mixed parties (players + NPCs)
```

### New Enums

```
FormationEnumType: LINE, WEDGE, CIRCLE, SCATTER, COLUMN
TargetTypeEnumType: SELF, SINGLE_ALLY, SINGLE_ENEMY, ALL_ALLIES, ALL_ENEMIES, AREA, LINE
ReputationTierEnumType: HOSTILE, GUARDED, NEUTRAL, FRIENDLY, ALLIED, DEVOTED
RealmSessionStatusEnumType: LOBBY, ACTIVE, PAUSED
TurnModeEnumType: REAL_TIME, SIMULTANEOUS, SEQUENTIAL
PlayerStatusEnumType: ACTIVE, IDLE, DISCONNECTED, SHELTERED
SkillCategoryEnumType: COMBAT, RANGED, DEFENSE, SURVIVAL, CRAFTING, SOCIAL, STEALTH, KNOWLEDGE, MOVEMENT, MAGIC
LearnMethodEnumType: TEACHER, TEXT, EXPERIMENTATION, OBSERVATION
RarityEnumType: COMMON, UNCOMMON, RARE, LEGENDARY
BehaviorPatternEnumType: TERRITORIAL, PREDATORY, PACK, DEFENSIVE, FLEE, BOSS
StructureTypeEnumType: CAMPFIRE, STORAGE_CHEST, WALL, WORKBENCH, FORGE, SHELTER, BRIDGE, DOOR, FENCE
SealTypeEnumType: FIRE, WATER, EARTH, LIFE, DEATH, POLAR, LUNAR, SOLAR, VOID
CastingPathEnumType: SORCERER, CHANNELER
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

Multiplayer — Realm Sessions:
  POST /rest/game/realm/create          — Create new realm session (returns sessionCode)
  POST /rest/game/realm/join/{code}     — Join realm session by code
  POST /rest/game/realm/leave           — Leave current realm session
  GET  /rest/game/realm/{sessionId}     — Get realm session state (players, status, settings)
  POST /rest/game/realm/settings        — Update realm settings (turn mode, pvp, timeout)
  GET  /rest/game/realm/{sessionId}/players — List active players with positions

Multiplayer — Player Interaction:
  POST /rest/game/trade/offer           — Initiate trade with another player
  POST /rest/game/trade/accept/{tradeId}— Accept pending trade
  POST /rest/game/trade/reject/{tradeId}— Reject pending trade
  POST /rest/game/pvp/challenge/{charId}— Challenge player to duel
  POST /rest/game/pvp/accept/{challengeId} — Accept PvP challenge
  POST /rest/game/pvp/decline/{challengeId}— Decline PvP challenge

Construction:
  POST /rest/game/build                   — Build structure at player's current sub-cell
  POST /rest/game/demolish                — Demolish structure

Commerce:
  GET  /rest/game/commerce/{npcId}        — NPC shop inventory with prices
  POST /rest/game/commerce/buy            — Buy from NPC
  POST /rest/game/commerce/sell           — Sell to NPC
  POST /rest/game/craft                   — Initiate crafting action
  GET  /rest/game/recipes/{charId}        — Known recipes

Skill Learning:
  POST /rest/game/skills/learn            — Learn skill from teacher/text/experimentation
  GET  /rest/game/skills/available/{charId} — Learnable skills nearby

Magic:
  GET  /rest/game/seals/known/{charId}    — Known Seal locations and alignments
  POST /rest/game/cast                    — Cast a spell (route through Sorcerer or Channeler path)
  GET  /rest/game/spells/{charId}         — Known spells with castability status

Asset Generation:
  POST /rest/game/asset/generate          — Generate game asset via SD for concept key

Multiplayer — WebSocket Subscriptions (via GameStreamHandler):
  {"subscribe": true, "realmId": "..."}  — Subscribe to realm-wide events
  {"unsubscribe": true, "realmId": "..."} — Unsubscribe from realm events
  {"heartbeat": true}                     — Player presence heartbeat (30s interval)
```

---

## Implementation Phases

### Phase 1: Foundation — Tile Rendering, Sub-Grid & App Shell

**Goal:** Visual tile-based map with intra-cell sub-grid navigation, responsive layout, and installable PWA shell. All client-server communication via REST API from day one.

**Backend:**
- Add `tileData` JSON field to `data.geoLocation` model (or dedicated sub-model)
- Create `CellGridUtil.java` — sub-grid generation from terrain type + POI data
- Add `/rest/game/cellGrid/*` endpoints to `GameService` — client fetches sub-grid data exclusively through these endpoints
- Wire sub-grid generation into `prepareCells()` pipeline

**Frontend — App Shell & Responsive Layout:**
- Create `rpgLayout.css` — CSS Grid responsive layout with desktop/tablet/mobile breakpoints
- Create `manifest.json` — PWA manifest (name, icons, display: standalone, orientation: any)
- Create `service-worker.js` — cache tile atlases and spritesheets; network-first for REST calls
- Create `rpgInputManager.js` — unified input handling:
  - Desktop: keyboard (WASD/arrows) + mouse click on tiles
  - Tablet/Mobile: touch d-pad overlay + tap-to-move on tiles
  - Pinch-to-zoom on tile canvas (1x–3x)
  - Input mode auto-detected via `'ontouchstart' in window` + `matchMedia`
- Add viewport meta tag (`width=device-width, initial-scale=1, viewport-fit=cover`)
- Ensure all game state comes from REST calls (`/rest/game/situation`, `/rest/game/cellGrid`) — no hardcoded or computed game state on the client

**Frontend — Tile Renderer:**
- Create `rpgTileRenderer.js` — Canvas-based tile renderer
  - Load tile atlas (Ninja Adventure spritesheet)
  - Render 10x10 sub-grid with proper tile sprites
  - Support tile animations (water, fire)
  - Layer: ground → props → entities → fog
  - Tile size adapts to breakpoint: 64px desktop, 48px tablet, 32px mobile
- Create `rpgAssetLoader.js` — spritesheet/atlas management
  - Parse atlas JSON manifest
  - Cache loaded atlases (service worker pre-caches for offline resilience)
  - Provide `drawTile(ctx, tileKey, x, y, size)` API
- Modify zoomed cell view rendering to use tile sprites instead of flat colors
- Add passability check to movement (cannot move into impassable sub-cells) — passability data comes from REST `/rest/game/cellGrid` response, not computed client-side

**Assets:**
- Download Ninja Adventure asset pack
- Extract terrain tilesets → build atlas PNG + JSON manifest
- Map AM7 terrain enum values to tile keys
- Create tile variation sets per terrain type (min 3 variants each)
- Create PWA icons (192px, 512px) from game art

**Tests:**
- `TestCellGridUtil.java` — all 12 tests (Tier 1 + Tier 2): grid generation, terrain mapping, passability guarantees, path existence, coordinate mapping, edge cells, transition tiles, serialization round-trip, persistence, lazy generation
- `rpgTileRenderer.test.js` — all 5 tests: coordinate conversion edge cases (0, 9, 50, 99), atlas key lookup, variation selection, layer ordering, viewport clipping
- Manual verification: PWA installable on Chrome desktop, Safari iOS, Chrome Android; responsive layout correct at all three breakpoints; touch d-pad functional on tablet/mobile
- **Gate:** Phase 1 is not complete until all 17 automated tests pass AND the app installs and renders correctly on desktop, tablet, and mobile

**Deliverable:** Player can install the RPG as an app on desktop, tablet, or mobile. Navigate a visually rich tile map with trees, rocks, water, and paths rendered at the sub-cell level. All game data fetched via REST API. Touch and keyboard input both functional.

### Phase 2: Reputation & VATS Combat

**Goal:** Persistent reputation system and VATS-style action selection. All reputation data and VATS triggers flow through REST/WebSocket — no client-side game logic.

**Backend:**
- Create `ReputationUtil.java` — pair reputation calculation, persistence, tier evaluation
- Add `olio.reputation` model + schema
- Wire `InteractionUtil.calculateSocialInfluence()` output into reputation records after every interaction
- Create `VATSUtil.java` — decision point detection, action option generation based on context
- Extend `GameService` with reputation endpoints (`GET /rest/game/reputation/{charId}/{targetId}`, `GET /rest/game/reputation/{charId}/nearby`)
- Extend `GameStreamHandler` with VATS pause/resume push events (client listens, never computes triggers)

**Frontend:**
- Create `rpgVATSOverlay.js` — action selection overlay on tile map
  - Fullscreen on all form factors (desktop, tablet, mobile) — the decision moment demands full attention
  - Freeze time display on WebSocket trigger from server
  - Context-sensitive action list populated from REST response (combat/social/survival)
  - Action preview (success probability returned by server, not computed client-side)
  - Submit action via REST → stream resolution via WebSocket → show outcome → dismiss overlay
  - Touch-friendly: action buttons sized for thumb tap on mobile (min 44px touch targets)
- Create `rpgReputationPanel.js` — shows reputation with nearby NPCs
  - Reputation bars with tier labels (Hostile → Devoted)
  - Recent interaction history summary
  - Responsive: inline sidebar on desktop, slide-up sheet on mobile
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

**Goal:** Party system gated by reputation + player-facing needs objectives. Party state and needs data served via REST endpoints.

**Backend:**
- Create `PartyUtil.java` — reputation-gated party formation, member loyalty check during Overwatch increments
- Add `olio.party` model + schema
- Extend `NeedsUtil` to generate player-facing objective descriptions
- Create `NeedsJournalUtil.java` — needs-to-objective mapping with progress calculation
- Add party endpoints (`GET /rest/game/party`, `POST /rest/game/party/formation`, `POST /rest/game/party/dismiss`) and needs endpoints (`GET /rest/game/needs`, `GET /rest/game/needs/objectives`) to `GameService`
- Wire reputation check into Overwatch: members desert if reputation drops below threshold; client notified via WebSocket push

**Frontend:**
- Create `rpgPartyPanel.js` — party management
  - Member portraits with reputation bar overlay (data from `GET /rest/game/party`)
  - Formation editor (drag on desktop, tap-to-assign on touch; sends `POST /rest/game/party/formation`)
  - Loyalty warnings when reputation is borderline
  - Responsive: sidebar panel on desktop, bottom sheet on mobile
- Create `rpgNeedsJournal.js` — needs-as-objectives display
  - Maslow hierarchy visualization (survive at bottom, thrive at top)
  - Per-need progress bars (data from `GET /rest/game/needs/objectives`)
  - LLM-generated narrative framing for each unmet need
  - Responsive: scrollable sidebar on desktop, tabbed view on mobile
- Reputation-based NPC indicators on tile map (color-coded by disposition tier)

**Tests:**
- `TestPartyUtil.java` — all 13 tests (Tier 1 + Tier 2): reputation ≥+30 join gate, reputation <+30 refusal, desertion below +10, boundary value +10, max party size, formation assignment, leader movement, shared store access, dismiss reputation drop, dark triad betrayal trigger, high-reputation no-betrayal, party save/load persistence, NPC-initiated join request
- `TestNeedsJournalUtil.java` — all 8 tests (Tier 2): physiological needs → objectives, Maslow level gating, progress calculation, reputation-based objectives, milestone tracking, needs reassessment on depletion, multiple simultaneous needs, narrative framing non-null
- `TestGameServiceRPGEndpoints.java` — party + needs endpoints (5 of 15): GET party, POST formation, POST dismiss, GET needs, GET needs/objectives
- `rpgNeedsJournal.test.js` — all 4 tests: Maslow ordering, progress bars, gated level display, objective counts
- **Gate:** Phase 3 is not complete until all 30 tests pass

**Deliverable:** NPCs organically join party when reputation is high enough. Player has visible needs-based objectives that drive exploration and interaction.

### Phase 4: Skill Progression, Fog of War & Audio

**Goal:** Skill leveling/decay, visibility system, and audio. Skill and fog data served via REST; all game logic server-side.

**Backend:**
- Create `SkillProgressionUtil.java` — skill gain on use, decay on disuse per `olio.txt` rules
- Add `proficiency`, `lastUsed`, `usageCount` fields to `data.trait`
- Wire skill updates into `InteractionAction.executeAction()` conclusion
- Wire decay evaluation into `Overwatch.syncClocks()`
- Add fog of war tracking: `exploredCells` packed field on `olio.state`
- Milestone-based attribute point grants (new region, new Maslow level, reputation threshold)
- Add skill endpoints (`GET /rest/game/skills`, `POST /rest/game/allocatePoints`, `GET /rest/game/abilities`) to `GameService`
- Extend save/load to include reputation, explored map, skill proficiencies
- Fog of war visibility included in `/rest/game/situation` response — client renders what the server says is visible

**Frontend:**
- Create `rpgSkillPanel.js` — skill list with proficiency bars and decay indicators
  - Decay warning (amber) when skill is approaching decay threshold
  - Active decay (red) when skill has decayed since last use
  - Proficiency gain animation on successful skill use
  - Data from `GET /rest/game/skills` — decay calculations run on server, not client
  - Responsive: full list on desktop, collapsible categories on mobile
- Create `rpgFogOfWar.js` — visibility overlay
  - Unexplored cells: opaque black
  - Previously explored but out of range: 50% darkened
  - Currently visible (perception radius): full brightness
  - Visibility data comes from server situation response — client never computes perception radius
- Create `rpgAudioManager.js` — background music + SFX
  - Terrain-keyed music (Ninja Adventure tracks)
  - Combat music trigger on VATS activation (WebSocket push event)
  - SFX on action resolution, item pickup, skill gain
  - Respects mobile audio policies (user gesture required to start AudioContext)
- Attribute allocation UI when milestone grants points (`POST /rest/game/allocatePoints`)
- Character sheet with full stat + skill + reputation display — all data from REST
- Mini-map overlay showing explored area (from server-side `exploredCells`)

**Tests:**
- `TestSkillProgressionUtil.java` — all 17 tests (Tier 1 + Tier 2): gain on favorable/very-favorable/equilibrium, no gain on unfavorable, diminishing returns curve, cap at 95%, initial cap at 50%, Fae 4x cost, decay formula verification (70%→30d, 90%→10d, 95%→5d), floor at 60%, decay at 61%→60% then stop, decay accumulation over time, decay reset on use, intelligence gain rate modifier, decay during Overwatch syncClocks, skill gain after combat interaction, independent multi-skill decay
- `TestOverwatchRPGExtensions.java` — skill decay tests (3 of 5): skill decay during syncClocks, member desertion during processGroup, full cycle with gain-then-decay
- `TestGameServiceRPGEndpoints.java` — remaining endpoints (5 of 15): GET skills, POST allocatePoints, allocatePoints exceeds potential 400, GET abilities, unauthorized access 403
- `rpgSkillPanel.test.js` — all 4 tests: proficiency bar width, decay warning threshold, active decay indicator, skill sort order
- **Gate:** Phase 4 is not complete until all 29 tests pass

**Cumulative:** All 110 tests across Phases 1-4 must pass before starting Phase 5.

**Deliverable:** Complete single-player RPG experience with organic skill progression, atmospheric audio, and exploration reward through fog of war. Fully functional as an installed App on desktop, tablet, and mobile. All game logic on the server, all data via REST. Foundation for multiplayer (REST API + WebSocket event system) already in place.

### Phase 5: Multiplayer

**Goal:** Multiple players share a realm, see each other in real-time, form cross-player parties, trade, and optionally PvP. All coordination via WebSocket push. Single-player remains fully functional (multiplayer with one participant).

**Backend:**
- Create `RealmSessionManager.java` — realm session lifecycle (create, join, leave, reconnect)
  - Session code generation (6-char alphanumeric, unique)
  - Player presence tracking with heartbeat (30s interval, 90s timeout → DISCONNECTED)
  - Realm-scoped event broadcasting (push events to all players in a realm)
- Create `RealmSessionUtil.java` — realm session CRUD, player count enforcement, status transitions
- Add `olio.realmSession` + `olio.playerPresence` models + schema
- Extend `GameStreamHandler`:
  - Realm subscription (`subscribe`/`unsubscribe` for `realmId`)
  - Heartbeat handling → update `lastHeartbeat` on `playerPresence`
  - Player movement broadcasting: on character move, push `game.player.moved` to all realm subscribers within perception range
  - Nearby-player subscription management: server auto-subscribes/unsubscribes as players move in and out of each other's perception radius
- Extend `Overwatch` for coordinated turn resolution:
  - When decision point affects multiple players, enter VATS collection window
  - Track which players have submitted actions (per decision point)
  - Timeout handling: default idle players to "defend" action
  - Resolve all queued player actions in initiative order (`reaction` stat)
  - Support three turn modes: REAL_TIME, SIMULTANEOUS, SEQUENTIAL
- Add realm session REST endpoints: `POST /rest/game/realm/create`, `POST /rest/game/realm/join/{code}`, `POST /rest/game/realm/leave`, `GET /rest/game/realm/{sessionId}`, `POST /rest/game/realm/settings`, `GET /rest/game/realm/{sessionId}/players`
- Add player interaction REST endpoints: `POST /rest/game/trade/offer`, `POST /rest/game/trade/accept|reject`, `POST /rest/game/pvp/challenge|accept|decline`
- Wire cross-player reputation: player-to-player reputation uses the same `olio.reputation` model and `ReputationUtil` as NPC reputation. Cooperative actions increase rep, PvP/theft decreases it. Party join gated at +30 (same threshold as NPCs).

**Frontend:**
- Create `rpgMultiplayerLobby.js` — realm session UI
  - Create realm: generates session code, displays for sharing
  - Join realm: enter session code
  - Lobby view: shows connected players, realm settings, "Start" button for creator
  - Settings panel: turn mode selector, PvP toggle, VATS timeout slider
  - Responsive: fullscreen overlay on all form factors
- Create `rpgPlayerRenderer.js` — render other players on tile map
  - Player sprites with name labels (different visual treatment from NPCs)
  - Player status indicators (active, idle, disconnected)
  - Proximity-based: only render players within perception range (data from server)
- Create `rpgTradeUI.js` — player-to-player trade interface
  - Split-screen inventory view (your items / their items)
  - Drag-to-offer, confirm/cancel
  - Both players must confirm (server enforces via VATS-like commit)
  - Responsive: fullscreen overlay on mobile, panel on desktop
- Create `rpgPlayerChat.js` — direct player-to-player text chat
  - WebSocket-routed (not LLM) — low latency
  - Chat overlay on tile map, expandable
  - Player name + message display
- Extend VATS overlay for multiplayer:
  - Show other player action status ("Player 2 is choosing..." / "Player 2 ready")
  - Initiative order display
  - Timeout countdown
- Extend `rpgReputationPanel.js` — add player-to-player reputation entries
- Extend `rpgPartyPanel.js` — show player members alongside NPC members

**Tests:**
- `TestRealmSessionManager.java` — all 15 tests (Tier 2 + Tier 3):
  - Session creation with unique code
  - Player join by code (valid/invalid/full)
  - Player leave and cleanup
  - Reconnect with existing character
  - Heartbeat updates lastHeartbeat
  - Heartbeat timeout → DISCONNECTED status
  - Max players enforcement
  - Session status transitions (LOBBY → ACTIVE → PAUSED)
  - Realm-scoped event broadcast reaches all players
  - Player movement broadcast to nearby players only
  - Perception-radius subscription management
  - Cross-player reputation (create, update, query)
  - Settings update (turn mode, PvP, timeout)
  - Concurrent session access (thread safety)
  - Session code uniqueness under parallel creation
- `TestMultiplayerTurnCoordination.java` — all 10 tests (Tier 2):
  - REAL_TIME mode: non-affected players continue during VATS
  - SIMULTANEOUS mode: all nearby players pause at decision point
  - SEQUENTIAL mode: players take turns in initiative order
  - Timeout defaults idle player to "defend"
  - All players submit → resolution in initiative order
  - PvP challenge → both players get VATS pause
  - Cross-player party combat: all party members get VATS
  - Trade offer → accept flow via VATS-like commit
  - Trade rejection flow
  - Mixed PvP: PvP disabled blocks COMBAT interaction
- `TestMultiplayerWebSocket.java` — all 8 tests (Tier 3):
  - `game.player.joined` event reaches all realm subscribers
  - `game.player.moved` event reaches only nearby players
  - `game.player.left` event on disconnect
  - `game.vats.playerReady` event during multiplayer VATS
  - `game.trade.request/accepted/rejected` event flow
  - Realm subscription/unsubscription
  - Heartbeat over WebSocket
  - Stale session cleanup on heartbeat timeout
- `rpgMultiplayerLobby.test.js` — all 4 tests: session code display, player list rendering, settings controls, join/leave state transitions
- **Gate:** Phase 5 is not complete until all 37 tests pass

**Cumulative:** All 147 tests across Phases 1-5 must pass for the multiplayer RPG to be considered feature-complete.

**Deliverable:** Multiple players can create or join a shared realm session, see each other move on the tile map in real-time, form cross-player parties, trade items, and engage in coordinated combat. All coordination via WebSocket push events. Single-player works unchanged.

### Phase 6 (Future): Content Expansion & Native Wrappers

**Goal:** Expanded content, advanced multiplayer features, and optional native app store distribution. (Deferred)

- LLM-generated contextual objectives beyond basic needs
- Dungeon instances (instanced sub-realms with unique POI configurations)
- Reputation-based faction system (communities with collective disposition)
- World events affecting reputation across regions
- Shared party reputation (group reputation with NPCs across all party members)
- Spectator mode (observe a realm without a character)
- **Native app wrappers** (if App Store distribution needed):
  - Android: Trusted Web Activity (TWA) wrapping the PWA — zero code changes
  - iOS: Capacitor or WKWebView shell wrapping the PWA — zero code changes
  - Desktop: Electron shell (if PWA install isn't sufficient) — zero code changes
  - All wrappers consume the same REST API; the PWA *is* the app

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

#### TestRealmSessionManager.java (Phase 5)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testCreateSession` | 2 | Create realm session returns valid session with unique 6-char code |
| `testJoinByCode` | 2 | Player joins session by valid code, appears in activePlayers |
| `testJoinInvalidCode` | 2 | Invalid session code returns appropriate error |
| `testJoinFullSession` | 2 | Joining at maxPlayers returns capacity error |
| `testLeaveSession` | 2 | Player leave removes from activePlayers, pushes `game.player.left` |
| `testReconnect` | 2 | Reconnecting player resumes existing character and position |
| `testHeartbeatUpdate` | 2 | Heartbeat message updates `lastHeartbeat` timestamp on `playerPresence` |
| `testHeartbeatTimeout` | 2 | No heartbeat for 90s → status transitions to DISCONNECTED |
| `testMaxPlayersEnforcement` | 2 | Session rejects join when `activePlayers.size >= maxPlayers` |
| `testStatusTransitions` | 2 | Session status follows LOBBY → ACTIVE → PAUSED lifecycle |
| `testRealmScopedBroadcast` | 3 | Push event reaches all players in the realm, not players in other realms |
| `testPlayerMovementBroadcast` | 3 | Player move pushes `game.player.moved` only to nearby players (perception range) |
| `testPerceptionSubscriptionManagement` | 3 | Moving in/out of another player's range auto-subscribes/unsubscribes |
| `testSettingsUpdate` | 2 | Realm creator can update turn mode, PvP, timeout |
| `testSessionCodeUniqueness` | 2 | Parallel session creation produces unique codes |

#### TestMultiplayerTurnCoordination.java (Phase 5)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testRealTimeMode` | 2 | Non-affected players continue moving during another player's VATS pause |
| `testSimultaneousMode` | 2 | All nearby players pause at decision point, all must submit |
| `testSequentialMode` | 2 | Players take turns in initiative order, each sees prior resolution |
| `testVATSTimeout` | 2 | Idle player defaults to "defend" after timeout expires |
| `testInitiativeOrderResolution` | 2 | Queued player actions resolve in `reaction` stat order |
| `testPvPChallenge` | 2 | PvP challenge → both players get VATS pause |
| `testCrossPlayerPartyCombat` | 2 | Party with multiple players → all get VATS in shared combat |
| `testTradeOfferAccept` | 2 | Trade offer → accept → items exchanged, both inventories updated |
| `testTradeOfferReject` | 2 | Trade offer → reject → no items exchanged, initiator notified |
| `testPvPDisabledBlocksCombat` | 2 | PvP disabled realm rejects COMBAT interaction between players |

#### TestMultiplayerWebSocket.java (Phase 5)

| Test Method | Tier | What It Validates |
|------------|------|-------------------|
| `testPlayerJoinedEvent` | 3 | `game.player.joined` reaches all realm subscribers on player join |
| `testPlayerMovedEvent` | 3 | `game.player.moved` reaches only nearby players within perception range |
| `testPlayerLeftEvent` | 3 | `game.player.left` pushed on disconnect with reason |
| `testVATSPlayerReadyEvent` | 3 | `game.vats.playerReady` pushed when player commits VATS action in multiplayer |
| `testTradeEvents` | 3 | `game.trade.request/accepted/rejected` flow between two player sessions |
| `testRealmSubscription` | 3 | Subscribe/unsubscribe to realmId controls realm event delivery |
| `testHeartbeatOverWebSocket` | 3 | Heartbeat message processed and acknowledged |
| `testStaleSessionCleanup` | 3 | Timed-out player presence cleaned up, `game.player.left` pushed |

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

#### rpgMultiplayerLobby.test.js (Phase 5)

| Test | What It Validates |
|------|-------------------|
| `session code display` | Created session shows shareable code prominently |
| `player list rendering` | Connected players listed with status indicators |
| `settings controls` | Turn mode, PvP toggle, timeout slider render and update |
| `join/leave state` | UI transitions correctly between lobby/active/disconnected states |

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
| Realm session lifecycle | 100% branch | Session join/leave/reconnect must be bulletproof for multiplayer |
| Turn coordination modes | 100% branch | Wrong mode behavior = broken multiplayer combat |
| Player visibility broadcast | 90%+ line | Wrong broadcast scope leaks position data or misses nearby players |
| WebSocket event delivery | 100% branch | Missed events = desynced clients; leaked events = information exposure |

### Test Execution

```bash
# Tier 1 — Pure logic (no external dependencies, runs in CI)
mvn test -pl AccountManagerObjects7

# Tier 2 — Integration (requires DB)
mvn test -pl AccountManagerObjects7 -Dtest="TestReputationUtil,TestSkillProgressionUtil,TestCellGridUtil,TestPartyUtil,TestVATSUtil,TestNeedsJournalUtil,TestOverwatchRPGExtensions,TestRealmSessionManager,TestMultiplayerTurnCoordination"

# Tier 3 — Service layer (requires H2)
mvn test -pl AccountManagerService7 -Dtest="TestGameServiceRPGEndpoints,TestMultiplayerWebSocket"

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
- **Phase 5:** TestRealmSessionManager (all), TestMultiplayerTurnCoordination (all), TestMultiplayerWebSocket (all), rpgMultiplayerLobby.test.js (all)

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
| `src/main/resources/models/olio/realmSessionModel.json` | Multiplayer realm session schema |
| `src/main/resources/models/olio/playerPresenceModel.json` | Player presence tracking schema |
| `src/main/java/org/cote/accountmanager/olio/RealmSessionUtil.java` | Realm session CRUD + player count + status |
| `src/main/java/org/cote/accountmanager/olio/schema/RealmSessionStatusEnumType.java` | Session status enum |
| `src/main/java/org/cote/accountmanager/olio/schema/TurnModeEnumType.java` | Turn coordination mode enum |
| `src/main/java/org/cote/accountmanager/olio/schema/PlayerStatusEnumType.java` | Player presence status enum |
| `src/main/resources/models/olio/sealModel.json` | Seal (magic source) schema |
| `src/main/resources/models/olio/spellModel.json` | Spell definition schema |
| `src/main/resources/models/olio/skillUniverseModel.json` | Skill catalog per world maturity schema |
| `src/main/resources/models/olio/encounterTableModel.json` | Per-terrain creature encounter table schema |
| `src/main/java/org/cote/accountmanager/olio/CommerceUtil.java` | NPC pricing, shop inventory, buy/sell logic |
| `src/main/java/org/cote/accountmanager/olio/EncounterUtil.java` | Encounter table lookup, difficulty scaling, creature behavior |
| `src/main/java/org/cote/accountmanager/olio/SkillUniverseUtil.java` | Skill catalog management, learning methods, NPC skill distribution |
| `src/main/java/org/cote/accountmanager/olio/ConstructionUtil.java` | Player structure building/demolition in sub-cell grid |
| `src/main/java/org/cote/accountmanager/olio/MagicUtil.java` | Seal-based magic system — casting, balance, burn, seal health |
| `src/main/java/org/cote/accountmanager/olio/schema/SkillCategoryEnumType.java` | Skill category enum |
| `src/main/java/org/cote/accountmanager/olio/schema/LearnMethodEnumType.java` | Skill learning method enum |
| `src/main/java/org/cote/accountmanager/olio/schema/RarityEnumType.java` | Creature encounter rarity enum |
| `src/main/java/org/cote/accountmanager/olio/schema/BehaviorPatternEnumType.java` | Creature behavior pattern enum |
| `src/main/java/org/cote/accountmanager/olio/schema/StructureTypeEnumType.java` | Player-buildable structure types |
| `src/main/java/org/cote/accountmanager/olio/schema/SealTypeEnumType.java` | Magic seal type (school) enum |
| `src/main/java/org/cote/accountmanager/olio/schema/CastingPathEnumType.java` | Magic casting path enum (Sorcerer/Channeler) |
| `src/main/resources/data/skillUniverse.json` | Skill catalog data — all skills by category and maturity tier |
| `src/main/resources/data/encounterTables.json` | Encounter table data — creatures by terrain and rarity |

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
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestRealmSessionManager.java` | Session lifecycle, join/leave, heartbeat, broadcast, code uniqueness (15 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestMultiplayerTurnCoordination.java` | Turn modes, VATS timeout, initiative order, PvP, trade flows (10 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestCommerceUtil.java` | NPC pricing, reputation modifiers, shop inventory generation, buy/sell |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestEncounterUtil.java` | Encounter table lookup, difficulty scaling, behavior patterns, geographic gating |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestSkillUniverseUtil.java` | Skill catalog, maturity gating, learning methods, NPC skill distribution |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestConstructionUtil.java` | Structure building/demolition, material validation, passability updates |
| `src/test/java/org/cote/accountmanager/objects/tests/olio/TestMagicUtil.java` | Seal casting, balance check, burn damage, seal disruption, weave spells |

**Backend Tests (AccountManagerService7):**
| File | Purpose |
|------|---------|
| `src/test/java/org/cote/accountmanager/objects/tests/TestGameServiceRPGEndpoints.java` | REST endpoint contracts for all new game endpoints (15 tests) |
| `src/test/java/org/cote/accountmanager/objects/tests/TestMultiplayerWebSocket.java` | WebSocket event delivery for multiplayer: join/move/leave, VATS, trade, realm sub (8 tests) |

**Backend (AccountManagerService7):**
| File | Purpose |
|------|---------|
| Extensions to `GameService.java` | New endpoints for reputation, party, cellGrid, skills, needs journal, realm sessions, trade, PvP |
| `src/main/java/org/cote/sockets/RealmSessionManager.java` | Multiplayer session lifecycle, presence tracking, heartbeat, realm-scoped broadcast |

**Frontend (AccountManagerUx7):**
| File | Purpose |
|------|---------|
| `client/view/rpgGame.js` | Main RPG game view (extends cardGame patterns, REST-only data access) |
| `client/view/rpg/rpgTileRenderer.js` | Canvas tile rendering engine (adaptive tile size per breakpoint) |
| `client/view/rpg/rpgAssetLoader.js` | Spritesheet/atlas management (service worker cached) |
| `client/view/rpg/rpgInputManager.js` | Unified input — keyboard (desktop), touch d-pad + tap (tablet/mobile), pinch-zoom |
| `client/view/rpg/rpgVATSOverlay.js` | VATS-style action selection overlay (fullscreen, touch-friendly) |
| `client/view/rpg/rpgReputationPanel.js` | NPC reputation display (responsive: sidebar / bottom sheet) |
| `client/view/rpg/rpgPartyPanel.js` | Reputation-gated party management (responsive: sidebar / bottom sheet) |
| `client/view/rpg/rpgNeedsJournal.js` | Needs-as-objectives display (responsive: sidebar / tabbed mobile view) |
| `client/view/rpg/rpgSkillPanel.js` | Skill proficiency + decay display (responsive) |
| `client/view/rpg/rpgAudioManager.js` | Music and sound effects (respects mobile audio policies) |
| `client/view/rpg/rpgFogOfWar.js` | Visibility overlay (server-driven visibility data) |
| `client/view/rpg/rpgInventoryUI.js` | Enhanced inventory with equipment slots (touch drag on mobile) |
| `client/view/rpg/rpgCharacterSheet.js` | Full character stats + skills + reputation display |
| `client/view/rpg/rpgMiniMap.js` | Explored area mini-map |
| `client/view/rpg/rpgMultiplayerLobby.js` | Realm session create/join/lobby UI (responsive) |
| `client/view/rpg/rpgPlayerRenderer.js` | Render other players on tile map (sprites, labels, status) |
| `client/view/rpg/rpgTradeUI.js` | Player-to-player trade interface (split inventory, confirm) |
| `client/view/rpg/rpgPlayerChat.js` | Direct player-to-player text chat (WebSocket, not LLM) |
| `client/view/rpg/rpgConstructionUI.js` | Structure building interface — material selection, placement preview |
| `client/view/rpg/rpgCommerceUI.js` | NPC shop interface — buy/sell with reputation-adjusted prices |
| `client/view/rpg/rpgCraftingUI.js` | Crafting interface — recipe selection, material check, progress |
| `client/view/rpg/rpgDeathScreen.js` | Death screen — permadeath notification, world impact summary, new character option |
| `client/view/rpg/rpgMagicUI.js` | Magic casting interface — Seal orientation, reagent selection, balance display |

**PWA & App Shell (AccountManagerUx7):**
| File | Purpose |
|------|---------|
| `manifest.json` | PWA manifest — app name, icons, display: standalone, orientation: any, theme color |
| `service-worker.js` | Asset caching (tile atlases, spritesheets, audio), network-first for REST API |
| `client/view/rpg/rpgLayout.css` | Responsive CSS Grid layout with desktop/tablet/mobile breakpoints |
| `assets/rpg/icons/icon-192.png` | PWA icon 192x192 |
| `assets/rpg/icons/icon-512.png` | PWA icon 512x512 |

**Frontend Tests (AccountManagerUx7):**
| File | Purpose |
|------|---------|
| `client/view/rpg/__tests__/rpgTileRenderer.test.js` | Coordinate conversion, atlas lookup, viewport clipping (5 tests) |
| `client/view/rpg/__tests__/rpgReputationPanel.test.js` | Tier labels, color mapping, bar percentage (4 tests) |
| `client/view/rpg/__tests__/rpgSkillPanel.test.js` | Proficiency bars, decay indicators, sort order (4 tests) |
| `client/view/rpg/__tests__/rpgNeedsJournal.test.js` | Maslow ordering, progress bars, level gating (4 tests) |
| `client/view/rpg/__tests__/rpgMultiplayerLobby.test.js` | Session code display, player list, settings, state transitions (4 tests) |
| `vitest.config.js` | Vitest configuration for RPG module unit tests |

**Assets (Theme Directory Structure):**
| Directory | Contents |
|-----------|----------|
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/` | Default theme (CC0) — complete asset pack |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/theme.json` | Theme manifest mapping concept keys to asset files |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/terrain/` | Terrain tile atlases |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/characters/` | Character spritesheets |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/monsters/` | Monster spritesheets |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/items/` | Item/equipment icons |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/ui/` | UI elements (panels, buttons, bars) |
| `AccountManagerUx7/assets/rpg/themes/ninja-adventure/audio/` | Music tracks + sound effects |
| `AccountManagerUx7/assets/rpg/themes/generated/` | AI-generated theme (SD pipeline output) |
| `AccountManagerUx7/assets/rpg/themes/generated/theme.json` | Generated theme manifest |
| `AccountManagerUx7/assets/rpg/themes/fallback/` | Minimal placeholder assets (colored rectangles, beep SFX) |
| `AccountManagerUx7/assets/rpg/themes/fallback/theme.json` | Fallback theme manifest |
| `AccountManagerUx7/assets/rpg/icons/` | PWA icons (192px, 512px) |
| `AccountManagerUx7/validateTheme.js` | Build-step script to check theme completeness against required concept keys |

### Existing Files to Modify

| File | Changes |
|------|---------|
| `AccountManagerObjects7` `data.traitModel.json` | Add `proficiency`, `lastUsed`, `usageCount`, `learnedVia`, `learnedFrom`, `maturityTier` fields |
| `AccountManagerObjects7` `olio.stateModel.json` | Add `exploredCells`, `milestones` fields |
| `AccountManagerObjects7` `data.geoLocationModel.json` | Add `tileData` JSON field for sub-cell grid (concept keys, structures, POIs) |
| `AccountManagerObjects7` `Overwatch.java` | Wire skill decay into `syncClocks()`, VATS pause support, multiplayer turn coordination (VATS collection window, initiative-order resolution) |
| `AccountManagerObjects7` `Overwatch.java` `processProximity()` | Wire encounter table lookup for creature spawning based on terrain and geographic difficulty |
| `AccountManagerObjects7` `InteractionAction.java` | Wire skill proficiency gain after action resolution |
| `AccountManagerObjects7` `InteractionUtil.java` | Wire reputation persistence into `calculateSocialInfluence()` |
| `AccountManagerObjects7` `GameUtil.java` | Party-aware movement, reputation-aware NPC behavior |
| `AccountManagerService7` `GameService.java` | New endpoints: reputation, party, cellGrid, skills, needs, realm sessions, trade, PvP, construction, commerce, skill learning, magic, asset generation |
| `AccountManagerService7` `GameStreamHandler.java` | VATS pause push events, reputation update events, realm subscriptions, player presence broadcasting, heartbeat handling, multiplayer event routing |
| `AccountManagerUx7` `applicationRouter.js` | Add `/rpg/:id` route |
| `AccountManagerUx7` `modelDef.js` | Add new model schemas (party, reputation, ability, realmSession, playerPresence, seal, spell, skillUniverse, encounterTable) |
| `AccountManagerUx7` `gameStream.js` | Add realm subscription, heartbeat, player event routing, trade/PvP message handling |
| `AccountManagerUx7` `build.js` / esbuild config | Include new RPG modules, asset directories, and service worker |
| `AccountManagerUx7` `index.html` (or entry point) | Add viewport meta tag, manifest link, service worker registration |

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
