# Card Game Gap Analysis: Ux7 → Ux752

## CRITICAL: Missing CSS Stylesheet

| Item | Ux7 | Ux752 |
|------|-----|-------|
| `cardGame-v2.css` | 218,897 bytes — 7,653 lines, ALL `cg2-*` class definitions | **COMPLETELY MISSING** |

The Ux752 card game JS references **987 `cg2-*` CSS classes** across all modules. Zero of these classes are defined anywhere in Ux752's stylesheets. The entire card game UI is unstyled.

### Fix Required
1. Copy `AccountManagerUx7/styles/cardGame-v2.css` → `AccountManagerUx752/src/styles/cardGame-v2.css`
2. Import in `src/main.js`: `import './styles/cardGame-v2.css';`
3. Verify Vite processes it through the build pipeline

---

## File-by-File Size Comparison

### Significantly Reduced Files

| File | Ux7 (bytes) | Ux752 (bytes) | Delta | Impact |
|------|-------------|---------------|-------|--------|
| `state/gameState.js` | 147,379 | 88,716 | -40% | Debug logging + comments stripped, 3 minor logic removals |
| `test/testMode.js` | 192,808 | 703 | -99.6% | **Complete test framework gutted to stub** |
| `ui/deckView.js` | 79,922 | 70,454 | -12% | IIFE→ESM cleanup |
| `ui/gameView.js` | 81,954 | 77,079 | -6% | IIFE→ESM cleanup |
| `ui/phaseUI.js` | 66,513 | 62,920 | -5% | IIFE→ESM cleanup |
| `services/artPipeline.js` | 70,701 | 66,462 | -6% | IIFE→ESM cleanup |
| `ui/deckList.js` | 29,456 | 24,838 | -16% | IIFE→ESM cleanup |
| `ui/builder.js` | 35,281 | 32,007 | -9% | IIFE→ESM cleanup |
| `services/characters.js` | 38,415 | 35,990 | -6% | IIFE→ESM cleanup |
| `ai/director.js` | 24,054 | 21,157 | -12% | Global namespace→ESM imports |
| `engine/encounters.js` | 24,831 | 22,670 | -9% | IIFE→ESM cleanup |
| `engine/actions.js` | 23,485 | 21,161 | -10% | IIFE→ESM cleanup |
| `constants/gameConstants.js` | 27,895 | 26,021 | -7% | Minor cleanup |
| `CardGameApp.js` | 15,418 | 13,911 | -10% | Test refs + public API reduction |

### Missing File

| File | Ux7 | Ux752 |
|------|-----|-------|
| `index.js` (module loader) | 3,322 bytes | Missing (expected — ESM replaces it) |

---

## Detailed Gap Analysis

### 1. `state/gameState.js` — 40% Size Reduction

**What was removed:**
- 111 `console.log` debug statements (~400 lines) — ALL diagnostic logging stripped
- 318 comment lines (from 336 → 18)
- 194 blank/whitespace lines

**Logic removed (minor):**
- Craft action `flavor` property: crafted items no longer get `" [Crafted by {name}]"` appended
- Trade failure `reason` property: no feedback stored when trade fails due to no eligible cards
- Variable naming: `gameState` → `_gameState` (private convention)

**Functions removed:** 0 — all 49 functions preserved
**Functional impact:** Low — game logic intact, but zero debug capability

### 2. `test/testMode.js` — 99.6% Removed (Gutted to Stub)

**Ux7 had 3,211 lines covering 15 test categories:**

| Category | What It Tested |
|----------|----------------|
| `modules` | Module presence, function exports for all subsystems |
| `stats` | Character stat mapping and validation |
| `gameflow` | Game phase transitions, state management lifecycle |
| `storage` | Deck/game/campaign CRUD via data.data records |
| `narration` | LLM-based narration system (4 profiles) |
| `combat` | Full combat mechanics (attack, defense, damage, outcomes) |
| `cards` | Card evaluation, pooling, type validation |
| `campaign` | Campaign persistence, resume, W/L tracking |
| `llm` | LLM integration, prompt building, connectivity |
| `voice` | Audio generation, voice profiles, TTS playback |
| `playthrough` | Full game playthrough scenario automation |
| `ux` | UI/UX interaction scenarios |
| `layouts` | Card layout configuration and rendering zones |
| `designer` | Card designer canvas functionality |
| `export` | Deck export and packaging (html2canvas + JSZip) |

**Ux752 stub exports:**
- Empty `TEST_CARDS` array
- No-op `runTestSuite()` function
- Placeholder `TestModeUI` returning null

### 3. `CardGameApp.js` — 10% Reduced

**Removed:**
- Test mode integration (`NS.TestMode` reference, TestModeUI mount)
- Public API surface: 30+ getter functions via `page.cardGameV2` reduced to 11
  - Removed from API: rendering components, storage accessors, combat functions, effect parsing, test functions
- Some CSS changed from `cg2-*` to inline Tailwind classes (inconsistent mix)

### 4. `ai/director.js` — 12% Reduced

**Removed:**
- Global namespace lazy-loading patterns (`window.CardGame.Engine?.placeCard`)
- Defensive checks for undefined modules (now hard-wired ESM imports)
- Core AI logic fully preserved

### 5. `ui/deckList.js` — 16% Reduced

**Removed:**
- IIFE namespace accessor patterns
- Redundant state management comments
- Core deck operations fully preserved

---

## CSS Class Categories Used But Undefined in Ux752

All 170+ `cg2-*` classes are referenced in JS but have no CSS definitions:

### Layout & Container
- `cg2-container`, `cg2-game-container`, `cg2-game-mode`
- `cg2-toolbar`, `cg2-game-header`, `cg2-game-title`
- `cg2-sidebar`, `cg2-sidebar-card-wrap`, `cg2-sidebar-status-bars`

### Card Rendering
- `cg2-card`, `cg2-card-front`, `cg2-card-back`, `cg2-card-full`, `cg2-card-flipper`
- `cg2-card-image-area`, `cg2-card-img`, `cg2-card-details`, `cg2-card-body`, `cg2-card-footer`
- `cg2-card-name`, `cg2-card-stats-line`, `cg2-corner-icon`
- `cg2-char-back`, `cg2-char-back-title`, `cg2-char-back-body-info`
- `cg2-stack-top`, `cg2-stack-right`, `cg2-stack-count`

### Stats & Bars
- `cg2-need-row`, `cg2-need-label`, `cg2-need-track`, `cg2-need-fill`, `cg2-need-value`
- `cg2-stat-grid`, `cg2-stat-row`, `cg2-stat-cell`, `cg2-stat-abbrev`, `cg2-stat-val`
- `cg2-rarity-star`, `cg2-rarity-star-active`

### Game Phases
- `cg2-phase-panel`, `cg2-phase-label`, `cg2-step-progress`, `cg2-step-dot`
- `cg2-init-cards`, `cg2-d20-wrap`, `cg2-d20-rolling`, `cg2-d20-final`, `cg2-d20-winner`

### Action Bar & Hand
- `cg2-action-bar`, `cg2-action-bar-track`, `cg2-action-panel`, `cg2-action-position`
- `cg2-hand-tray`, `cg2-hand-cards`, `cg2-hand-empty`

### Combat & Threats
- `cg2-threat-combat-result`, `cg2-threat-stats`, `cg2-threat-name`
- `cg2-threat-rolls`, `cg2-threat-damage-line`, `cg2-threat-actions`

### Game Over & Level Up
- `cg2-game-over-overlay`, `cg2-game-over-panel`, `cg2-victory`, `cg2-defeat`
- `cg2-levelup-overlay`, `cg2-levelup-panel`, `cg2-stat-pick`

### Buttons
- `cg2-btn`, `cg2-btn-primary`, `cg2-btn-secondary`, `cg2-btn-danger`
- `cg2-btn-warning`, `cg2-btn-accent`, `cg2-btn-sm`

### Chat & Overlays
- `cg2-chat-overlay`, `cg2-chat-panel`, `cg2-chat-messages`, `cg2-chat-input`
- `cg2-card-preview-overlay`, `cg2-modal-overlay`
- `cg2-image-preview-overlay`

### Deck Management
- `cg2-deck-grid`, `cg2-deck-item`, `cg2-deck-item-name`
- `cg2-card-grid`, `cg2-card-count-badge`

### Builder & Designer
- `cg2-builder-nav`, `cg2-theme-grid`, `cg2-theme-card`
- `cg2-theme-editor`, `cg2-theme-list`

### Art Pipeline
- `cg2-bg-panel`, `cg2-art-progress`, `cg2-art-progress-bar`, `cg2-art-progress-fill`

### Animations
- `cg2-spin` (keyframe animation)
- Card flip transitions
- D20 dice rolling animation
- Status effect ticker

---

## Correction Priority

### P0 — Blocking (game completely unusable)
1. **Copy `cardGame-v2.css`** from Ux7 and import it

### P1 — Important (features broken)
2. **Restore `testMode.js`** — port Ux7's test infrastructure to ESM
3. **Restore debug logging** in `gameState.js` — at minimum, key phase transitions and combat results

### P2 — Minor (polish)
4. **Restore craft flavor text** in `gameState.js`
5. **Restore trade failure reason** in `gameState.js`
6. **Verify all CardGameApp public API** consumers don't rely on removed accessors

---

## Backend Services Required for Testing

All of these are deployed and running at localhost:8443:

| Service | Used For |
|---------|----------|
| OlioService | Character profiles, world data, charPerson CRUD |
| REST `/rest/chat/*` | LLM director, narrator, chat manager |
| REST `/rest/olio/sdModels` | Stable Diffusion model listing for art pipeline |
| REST `/rest/resource/media/*` | Card art, portraits, backgrounds |
| WebSocket | Game streaming, push updates |
| data.data CRUD | Deck storage, game saves, campaign records |
| auth.group | Group/container management for game data |

---

## Card Game Correction & Test Prompt

Use the following prompt to fix all gaps and verify 100% feature coverage against the live backend.

---

### PROMPT

```
# Card Game Correction — AccountManagerUx752

## Context

You are fixing the card game port from AccountManagerUx7 to AccountManagerUx752.
Read `aiDocs/cardGameCorrection.md` for the full gap analysis.

All backend services are live at localhost:8443 (OlioService, chat/LLM, Stable Diffusion, WebSocket, data CRUD, auth). There is NO excuse to skip live testing.

## Mandatory Rules

1. NEVER use admin user for testing. Use `ensureSharedTestUser()` from `e2e/helpers/api.js`. Admin may ONLY be used to create/cleanup test users.
2. ALWAYS read Ux7 source BEFORE writing any code. If Ux7 had it, port it — don't rewrite.
3. NEVER claim something is fixed without a passing Playwright test that exercises the actual functionality against the live backend.
4. If you cannot test something, say "I cannot test this." Do NOT write a fake test.
5. Test EVERY change against the live backend before moving to the next task.

## Phase 1: CSS Restoration (P0 — Blocking)

1. Copy `../AccountManagerUx7/styles/cardGame-v2.css` → `src/styles/cardGame-v2.css`
2. Add `import './styles/cardGame-v2.css';` to `src/main.js` (or lazy-import in the cardGame feature route)
3. `npx vite build` — verify no errors
4. Write Playwright test: navigate to card game route, verify `.cg2-container` is visible and styled (has computed background/dimensions, not 0x0)

## Phase 2: Test Mode Restoration (P1)

1. Read Ux7 `client/view/cardGame/test/testMode.js` (3,211 lines)
2. Port to ESM with minimal changes — same test categories, same assertions
3. Verify the Test button in CardGameApp renders TestModeUI
4. Write Playwright test: navigate to card game, click Test button, verify test UI renders

## Phase 3: gameState.js Restoration (P1-P2)

1. Restore craft flavor text: `item.flavor = (item.flavor || "") + " [Crafted by " + owner.name + "]";`
2. Restore trade failure reason property
3. Restore key diagnostic `console.log` statements (phase transitions, combat results)
4. Do NOT restore all 111 console.logs — only the ones needed for debugging

## Phase 4: Playwright E2E Tests — 100% Feature Coverage

All tests use `ensureSharedTestUser()` or `setupTestUser()`. Admin ONLY for user creation/cleanup.

### Test Suite: Card Game Core

#### 4.1 Navigation & Loading
- [ ] Card game route (`/cardGame`) loads and renders `.cg2-container`
- [ ] Toolbar is visible with title and buttons
- [ ] Card game CSS is applied (`.cg2-btn` has correct padding/background)

#### 4.2 Deck Management
- [ ] Create new deck via builder flow
- [ ] Theme selection step renders theme grid with 8+ built-in themes
- [ ] Character selection step renders character cards
- [ ] Outfit review step shows character with applied apparel
- [ ] Deck appears in deck list after creation
- [ ] Load existing deck — card grid renders
- [ ] Delete deck — removed from list
- [ ] Deck list shows deck name, card count, character info

#### 4.3 Deck Editing
- [ ] Open deck view — card grid visible with type filters
- [ ] Add card to deck (each type: apparel, item, action, skill, magic)
- [ ] Remove card from deck
- [ ] Edit card properties (name, stats, effects)
- [ ] Card count badge updates
- [ ] Card art thumbnail displays (or placeholder)

#### 4.4 Game Initialization
- [ ] Click "Play" on a deck — character selection screen appears
- [ ] Select player character — game view loads
- [ ] Game header shows deck name, round counter, phase indicator
- [ ] Both player and opponent sidebars render with character cards
- [ ] Status bars (HP, Energy, Morale) render with correct initial values

#### 4.5 Initiative Phase
- [ ] D20 dice render for both players
- [ ] Rolling animation plays (`.cg2-d20-rolling` class toggles)
- [ ] Winner determined and highlighted (`.cg2-d20-winner`)
- [ ] Phase advances to Equip after initiative resolves

#### 4.6 Equip Phase
- [ ] Equipment slots render (head, chest, legs, feet, hands, weapon)
- [ ] Player can equip apparel from deck
- [ ] Equipped items show in slots with name, ATK/DEF/DUR stats
- [ ] Phase advances to Draw/Placement

#### 4.7 Draw/Placement Phase
- [ ] Hand tray renders with modifier cards (skills, magic, items)
- [ ] Action bar renders with 3-4 position slots
- [ ] Icon picker shows available actions for character class
- [ ] Select action — card placed in action bar position
- [ ] Place modifier card on action position (one per type)
- [ ] Remove card from position — returns to hand
- [ ] AP/Energy costs deducted on placement, refunded on removal
- [ ] Auto-end turn when all positions filled or AP exhausted

#### 4.8 Resolution Phase
- [ ] Each action position resolves sequentially
- [ ] Attack actions: D20 roll + STR + ATK vs D20 + END + DEF
- [ ] Combat outcome displayed (Critical Hit, Devastating, Strong Hit, etc.)
- [ ] Damage numbers shown, HP bars update
- [ ] Status effects applied from card effects
- [ ] Guard action: defense bonus applied
- [ ] Rest action: HP/Energy recovery
- [ ] Flee action: escape check (D20 + AGI)
- [ ] Talk action: CHA-based roll with LLM dialogue
- [ ] Investigate action: reveal opponent cards
- [ ] Craft action: create item with flavor text
- [ ] Trade action: exchange cards between players

#### 4.9 Cleanup Phase
- [ ] Round winner claims pot
- [ ] Pot cards transferred to winner's collection
- [ ] Scenario card drawn (peaceful or ambush weighted)
- [ ] Scenario effects applied (heal, loot, threat)
- [ ] Round counter increments

#### 4.10 Threat System
- [ ] Nat 1 triggers beginning threat
- [ ] Threat creature appears with stats and behavior
- [ ] Threat response phase: player places defense cards
- [ ] Threat combat resolves with D20 rolls
- [ ] Threat loot awarded on defeat
- [ ] Flee from threat: AGI check
- [ ] End-of-round threat check from scenario cards

#### 4.11 Game Over
- [ ] Game ends when a player reaches 0 HP
- [ ] Victory overlay (`.cg2-victory`) or defeat (`.cg2-defeat`) renders
- [ ] Final stats displayed (rounds, damage dealt, cards played)
- [ ] Campaign record updated (W/L)
- [ ] "Play Again" and "Return to Decks" buttons work

#### 4.12 Campaign System
- [ ] Campaign progress saves after game
- [ ] Campaign record shows W/L history
- [ ] Resume campaign from deck list
- [ ] Level-up screen appears on XP threshold
- [ ] Stat selection on level-up (pick stat to increase)
- [ ] Level-up stat gains persist to next game

#### 4.13 Card Rendering
- [ ] Character card front: portrait, stats grid, need bars
- [ ] Character card back: personality, description, abilities
- [ ] Card flip animation (`.cg2-card-flipper` transition)
- [ ] Rarity stars render (`.cg2-rarity-star-active`)
- [ ] Corner icon shows card type
- [ ] NeedBar component: HP/Energy/Morale with fill animation
- [ ] StatBlock component: 6-stat grid (STR/AGI/END/INT/MAG/CHA)

#### 4.14 Status Effects
- [ ] Stunned: skip next turn
- [ ] Poisoned: -2 HP/turn
- [ ] Shielded: +3 DEF
- [ ] Weakened: -2 all rolls
- [ ] Enraged: +3 ATK, -2 DEF
- [ ] Burning: -3 HP/turn
- [ ] Bleeding: -1 HP/turn
- [ ] Regenerating: +2 HP/turn
- [ ] Fortified: +2 DEF, +1 ATK
- [ ] Inspired: +2 all rolls
- [ ] Status effect ticker visible in sidebar
- [ ] Effects expire after duration

#### 4.15 AI System
- [ ] LLM connectivity check on game start
- [ ] Director makes placement decisions (LLM or FIFO fallback)
- [ ] Narrator commentary fires on game events (start, round, resolution, end)
- [ ] Narrator profiles: arena-announcer, dungeon-master, war-correspondent, bard
- [ ] Chat UI renders for narrative moments
- [ ] LLM status indicator (`.cg2-llm-online` / `.cg2-llm-offline`)

#### 4.16 Voice System
- [ ] Voice generation endpoint responds
- [ ] Audio playback on narration events (if voice enabled)
- [ ] Voice profile selection in settings

#### 4.17 Art Pipeline
- [ ] SD model list loads from `/rest/olio/sdModels`
- [ ] Art generation queue processes cards
- [ ] Progress bar renders during generation
- [ ] Generated art displays on card face
- [ ] Background/tabletop art generation
- [ ] Art failure handling (retry, skip)

#### 4.18 Designer
- [ ] Designer canvas loads
- [ ] Zone-based layout editor renders
- [ ] Layout template selection
- [ ] Icon picker for card types
- [ ] Export pipeline: card image export
- [ ] Export dialog with format options

#### 4.19 Storage & Persistence
- [ ] Save deck — data.data record created with encoded content
- [ ] Load deck — data.data record retrieved and decoded
- [ ] Save game state — mid-game save/resume
- [ ] Load saved game — resume from saved state
- [ ] Campaign record persistence across sessions
- [ ] Delete saved game

#### 4.20 Theme System
- [ ] 8 built-in themes load (High Fantasy, Dark Medieval, Sci-Fi, Post-Apocalypse, Steampunk, Cyberpunk, Space Opera, Horror)
- [ ] Theme card pool contains type-specific cards
- [ ] Theme art style config applies to generated art
- [ ] Theme encounter overrides (custom threats, scenarios)
- [ ] Custom theme creation/editing
- [ ] Theme editor UI renders

#### 4.21 Keyboard & Interactions
- [ ] Card click opens preview overlay
- [ ] Card preview flip (front/back toggle)
- [ ] Drag-and-drop card placement in hand tray
- [ ] Tab filters in hand (all/skill/magic/item)
- [ ] Fullscreen toggle
- [ ] Escape closes overlays

#### 4.22 Animations
- [ ] D20 dice rolling animation
- [ ] Card flip animation
- [ ] Status effect ticker animation
- [ ] Loading spinner (`.cg2-spin`)
- [ ] Game over fade-in

#### 4.23 Responsive Layout
- [ ] Card game renders correctly at 1920x1080
- [ ] Card game renders correctly at 1366x768
- [ ] Sidebar collapses or scrolls on smaller viewports
- [ ] Card grid adjusts column count
```

---
