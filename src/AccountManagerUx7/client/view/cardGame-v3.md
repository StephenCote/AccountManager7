# Card Game v3 — Card-Based RPG

A web-based card RPG playable online (1v1 vs AI) and printable for real-life tabletop play (multiplayer IRL only). Built on the AM7 backend with LLM-driven outcomes online; IRL play is fully standalone — just printed cards, dice, and players. **v3 is a print-first redesign** — every card looks identical on screen and on paper; the web app adds interactive command buttons but never changes the card's visual layout.

**Predecessor:** [cardGame-v2.md](cardGame-v2.md) — v3 inherits all unchanged mechanics from v2. This document specifies only what **changes** or **is new**; sections marked *"Unchanged from v2"* carry forward verbatim.

---

## Table of Contents

1. [v3 Design Goals](#v3-design-goals) — the 12 requirements driving this refactor
2. [Design Philosophy](#design-philosophy) — updated for print-first
3. [Card Types](#card-types) — unchanged from v2 (8 types)
4. [Card Anatomy — Print-First](#card-anatomy--print-first) — stacking borders, simplified stats, front/back
5. [Equipment System](#equipment-system) — character stack as card stack, equip via action
6. [Round Structure](#round-structure) — 6 phases (EQUIP phase removed), 5 fixed action spots
7. [Draw & Hand Mechanics](#draw--hand-mechanics) — draw = play count, max 7, discard
8. [Loot Box](#loot-box) — dropped/lost/stolen cards
9. [Game Play Layout](#game-play-layout) — 5 spots, 3/2 initiative split, jumbled card appearance
10. [Combat Resolution](#combat-resolution) — unchanged core, updated UI for per-step display
11. [Character Stack & Action Stacks](#character-stack--action-stacks) — simplified
12. [Mid-Round Disruption](#mid-round-disruption) — unchanged from v2
13. [Talk / Chat Action Card](#talk--chat-action-card) — chat system refactored
14. [Magic System](#magic-system) — unchanged from v2
15. [Needs & Survival](#needs--survival) — unchanged from v2
16. [AI Modes](#ai-modes) — unchanged from v2
17. [Card Arrangement UX](#card-arrangement-ux) — tablet-first redesign
18. [Poker Face](#poker-face-online-only) — unchanged from v2
19. [Live Narration System](#live-narration-system-online-only) — unchanged from v2
20. [Image Generation Pipeline](#image-generation-pipeline) — unchanged from v2
21. [Print Specifications](#print-specifications) — finalized print design
22. [Deck Theme Configuration](#deck-theme-configuration) — unchanged from v2
23. [Deck Builder & Snapshot Architecture](#deck-builder--snapshot-architecture) — unchanged from v2
24. [Online Implementation](#online-implementation) — updated endpoints for v3 mechanics
25. [Code Refactor Plan](#code-refactor-plan) — Tailwind migration, chat refactor, simplification
26. [Test Plan](#test-plan) — comprehensive UX test requirements
27. [Tablet UX Plan](#tablet-ux-plan) — responsive layout fixes
28. [Proposed Additional Refactors](#proposed-additional-refactors)
29. [Rules Quick Reference](#rules-quick-reference) — updated for v3
30. [Phased Implementation Plan](#phased-implementation-plan) — v3 build phases
31. [Known Issues & Technical Debt](#known-issues--technical-debt) — all open issues (54 total across 8 categories)

**Companion files:**
- [cardGame-v2.md](cardGame-v2.md) — Full v2 specification (all unchanged mechanics referenced here)
- [cardGame-v2-themes.md](cardGame-v2-themes.md) — Complete card pool definitions for all themes

---

## v3 Design Goals

These 12 requirements drive every change from v2 → v3:

| # | Requirement | Summary |
|---|------------|---------|
| 1 | **Print-ready cards** | Cards ready-to-print; apart from command button augmentation in config, cards consistent between web and print display |
| 2 | **Consistent card display** | Cards always look like print cards with common border on top and right for pseudo-stacking. Stats simplified to gameplay-relevant only. Character description/extra stats on back. |
| 3 | **Finish print design** | Complete all open design work for printing cards to nail down card consistency |
| 4 | **Simplify equipping** | Character stack = character card + equipment cards in a stack. Card type on horizontal/vertical border for click-through in web. No EQUIP phase — use "Use Item" action to switch equipment. |
| 5 | **More special cards + draw mechanics** | More special cards mixed in. Draw as many cards as played, up to max hand size 7. Hand can grow very large. Players can discard. |
| 6 | **Loot box** | Non-consumable/non-use-it-or-lose-it cards go to a "loot" box when a player loses a step |
| 7 | **Game play arrangement** | 5 max action spots. Highest agility with initiative gets 3, other gets 2. Jumbled card appearance. Die roll and result kept on board per step. |
| 8 | **Equipment rules** | Start with initial weapons/armor. Player must equip other weapon/armor via action, returning current to hand. |
| 9 | **Code refactor** | Simplify code. Move CSS to Tailwind or game CSS. Refactor chat for new/recent chat system. Use chat config policies. |
| 10 | **Test updates** | All cardGame UX tests must be updated and able to test ALL aspects of game play |
| 11 | **Tablet UX** | Must work on tablets. Current layout too padded with inconsistently sized and non-dynamic areas. |
| 12 | **Propose other refactors** | Identify additional areas to refactor or simplify |

---

## Design Philosophy

### Core Principles (Updated for v3)

1. **Print-first, screen-identical.** Every card renders identically on paper and on screen. The web app overlays interactive command buttons (config-driven) but never alters the card's printed layout — borders, stats, art, and text are pixel-identical.
2. **Cards are the interface.** Every game element is a card. No map, no board.
3. **Stacks are sentences.** Character + Weapon + Action = "I attack." Stacking is the primary mechanic.
4. **Use it or lose it.** Consumable cards played in a round are spent whether their effect triggered or not.
5. **Do it or don't.** Each round you build stacks and commit them. No take-backs.
6. **Same rules, two surfaces.** Online and IRL follow identical rules. Online adds LLM narration and AI; IRL uses dice + printed cards only.
7. **Equipping is an action, not a phase.** Switching weapons/armor costs an action spot. Your gear is part of your character stack — visible, tangible, always there.
8. **5 spots, 3-and-2.** The action bar is always exactly 5 spots. Initiative winner gets 3, loser gets 2. Simple, predictable, strategic.
9. **Draw what you play.** Each round you draw as many cards as you played (up to max 7 hand). Your hand can grow beyond 7 — but draw is capped at 7.
10. **Loot drops on loss.** When you lose a step, non-consumable cards from that stack go to the loot box. Both players can see what's available. The loot box is contested ground.

### Simplifications from v2

| v2 Feature | v3 Change |
|-----------|-----------|
| EQUIP phase between rounds | Eliminated — equipping is a "Use Item" action that costs an action spot |
| Variable AP (1-5 based on END) | Fixed 5 action spots total: initiative winner gets 3, loser gets 2 |
| Odd/even position interleaving | Initiative winner picks first 3 spots, loser picks remaining 2 (or vice versa, player can arrange) |
| Card stats show everything | Simplified to gameplay-relevant stats only on front; description and extra stats on character card back |
| Draw 1 per turn, choose draw/place/skip | Draw at round start = number of cards played last round, up to max 7 |
| No discard mechanic | Players can discard cards from hand at any time |
| Cards have full UX-specific styling | Cards always look like print cards; web app adds overlay buttons only |
| Equipment change is free between rounds | Equipment change costs an action spot (Use Item action) |
| AP from END stat | No AP concept — fixed 5 spots with 3/2 split |
| Round pot with mandatory ante | Round pot simplified — no mandatory ante, pot fills from loot drops and combat effects |

---

## Card Types

*Unchanged from v2.* There are 8 card types with distinct card back colors:

| Type | Back Color | Purpose | Persistence |
|------|-----------|---------|-------------|
| **Character** | Gold | Your persona — stats, portrait, needs | Persistent (never discarded) |
| **Apparel** | Silver | Armor, clothing — defensive modifiers | Equipped until destroyed or replaced |
| **Item** | Green | Weapons, tools, consumables | Weapons persist; consumables are use-or-lose |
| **Action** | Red | What you do this round | Played and returned to hand after round |
| **Talk** | Blue | Initiate conversation / negotiate | Played and returned after round |
| **Encounter** | Purple | Threats, events, discoveries | Resolved and discarded |
| **Skill** | Orange | Learned abilities that modify actions | Persist until decay |
| **Magic Effect** | Teal | Spell effects requiring skill type + stat threshold | Consumable or reusable (per card) |

---

## Card Anatomy — Print-First

### Print-First Principle

Every card is designed as a **physical print card first**. The on-screen rendering must be pixel-identical to the printed version. The web app adds interactive overlays (command buttons, hover states, drag handles) via a config-driven overlay layer — these overlays are **never part of the card image itself**.

### Stacking Borders

All cards have a **common border on the top and right edges** for pseudo-stacking. When cards are fanned or stacked in a character stack, the top and right borders remain visible, showing:
- **Top border:** Card name (abbreviated if needed) + card type icon
- **Right border (vertical):** Card type color bar + type icon (rotated 90°)

This allows instant identification of any card in a stack by its visible border edges.

```
SINGLE CARD:
┌─── Card Name ─── [TypeIcon] ────┐ ← top border (always visible in stack)
│                                  ║ ← right border (type color + icon)
│  [Card Image Area]               ║
│                                  ║
│  ─────────────────────────       ║
│  [Gameplay Stats - simplified]   ║
│                                  ║
│  [Type Icon]          [Type Icon]║
└──────────────────────────────────┘

STACKED (character + equipment):
┌─── Plate Armor ─── [🛡] ────────┐
┌─── Iron Sword ─── [⚔] ─────────╢ ← right borders visible
┌─── Elf Ranger ─── [🛡] ────────╢    showing card types
│                                  ║
│  [Character Card Face visible]   ║
│  STR 14 | AGI 16 | END 12       ║
│  INT 10 | MAG 8  | CHA 12       ║
│                                  ║
│  HP [████████░░] 16/20           ║
│  NRG [██████░░░░] 8/13           ║
│                                  ║
│  [🛡]                      [🛡] ║
└──────────────────────────────────┘
```

### Card Type on Border

The right border color and icon uniquely identify the card type:

| Card Type | Border Color | Border Icon |
|-----------|-------------|-------------|
| Character | Gold (#C5A55A) | Shield crest |
| Apparel | Silver (#A0A0A0) | Helmet |
| Item (Weapon) | Red (#8B2500) | Crossed swords |
| Item (Consumable) | Green (#2E5A2E) | Potion flask |
| Action | Red-orange (#D4380D) | Lightning bolt |
| Talk | Blue (#2F4F6F) | Speech bubble |
| Encounter | Purple (#4A2060) | Portal |
| Skill | Orange (#8B5A00) | Star |
| Magic Effect | Teal (#2F6A6A) | Arcane circle |

### Click-Through in Web App

In the web app, clicking/tapping the **visible border** of a stacked card pulls it to the front for full view. This is the primary navigation for character stacks:

1. Character stack shows the character card face with equipment borders visible
2. Click an equipment border → that equipment card slides to front
3. Click again or click the character border → returns to character view
4. Long-press/right-click any border → context menu: Unequip, Reimage, Open in AM7

### Simplified Stats (Front Face)

**v3 rule: Only gameplay-relevant stats appear on the card front.** No flavor text, lore, or descriptive stats clutter the front face. The card front must be readable at a glance during play.

#### Character Card Front (Simplified)

```
┌─── CHARACTER NAME ─── [🛡] ─────┐
│                                  ║
│  [Portrait Image]                ║
│  ──────────────────────────      ║
│  Race / Class          Level N   ║
│  ────────────────────────────    ║
│  STR [14] | AGI [16] | END [12] ║
│  INT [10] | MAG [ 8] | CHA [12] ║
│  ────────────────────────────    ║
│  HP  [████████░░] 16/20         ║
│  NRG [██████░░░░]  8/13         ║
│  MRL [██████████] 20/20         ║
│                                  ║
│  [🛡]                      [🛡] ║
└──────────────────────────────────┘
```

**Front shows only:**
- Name, race, class, level
- 6 core stats (STR, AGI, END, INT, MAG, CHA)
- 3 need tracks (HP, NRG, MRL) with bars
- Portrait image
- Corner type icons

**Removed from front (moved to back):**
- Equip slot diagram
- Skill slot diagram
- Alignment text
- Physical description
- Personality traits

#### Character Card Back (Extended Info)

The character card is the **only type** where the back contains useful information (all other card backs are decorative type-colored designs).

```
┌─── CHARACTER NAME ─── [🛡] ─────┐
│                                  ║
│  ALIGNMENT: Neutral Good         ║
│  ────────────────────────────    ║
│  DESCRIPTION:                    ║
│  Sharp features, green eyes,     ║
│  silver hair. Athletic build.    ║
│  ────────────────────────────    ║
│  PERSONALITY:                    ║
│  Cautious, observant, loyal      ║
│  ────────────────────────────    ║
│  EQUIPPED:                       ║
│  Head: [empty]  Body: Plate      ║
│  HandL: Shield  HandR: Sword     ║
│  Feet: Boots    Ring: [empty]    ║
│  Back: Cloak                     ║
│  ────────────────────────────    ║
│  SKILLS: [1] [2] [3] [4]        ║
│  ────────────────────────────    ║
│  XP: 250/300  Wins: 3  Losses: 1║
│                                  ║
│  [🛡]                      [🛡] ║
└──────────────────────────────────┘
```

**Web app:** Double-click or flip button to see the back. Flip animation.
**Print:** Both sides printed — front on page A, back on page B (duplex).

#### Other Card Types (Simplified Fronts)

All non-character cards show only gameplay-relevant stats on the front:

**Apparel Card Front:**
```
┌─── PLATE ARMOR ─── [🛡] ────────┐
│                                  ║
│  [Apparel Image]                 ║
│  ──────────────────────────      ║
│  Slot: Body       Rarity: ★★★   ║
│  DEF +5  |  HP +0                ║
│  Special: Resist Fire (halve)    ║
│  Durability: ████████░░ 8/10    ║
│                                  ║
│  [🛡]                      [🛡] ║
└──────────────────────────────────┘
```

**Removed:** Flavor text, material description, crafting origin.

**Item Card (Weapon) Front:**
```
┌─── IRON SWORD ─── [⚔] ─────────┐
│                                  ║
│  [Weapon Image]                  ║
│  ──────────────────────────      ║
│  Slot: Hand (1H)   Rarity: ★★   ║
│  ATK +4  |  Range: Melee        ║
│  Requires: STR 8                 ║
│  Parry: +2                       ║
│  Durability: ████████░░ 8/10    ║
│                                  ║
│  [⚔]                      [⚔]  ║
└──────────────────────────────────┘
```

**Removed:** Damage type description, special ability flavor text (special kept if gameplay-relevant).

**Item Card (Consumable) Front:**
```
┌─── HEALTH POTION ─── [🧪] ──────┐
│                                  ║
│  [Potion Image]                  ║
│  ──────────────────────────      ║
│  Consumable          Rarity: ★   ║
│  Effect: Restore 5 HP           ║
│  ──────────────────────────      ║
│  USE IT OR LOSE IT               ║
│                                  ║
│  [🧪]                      [🧪]║
└──────────────────────────────────┘
```

**Action Card Front:**
```
┌─── ATTACK ─── [⚡] ─────────────┐
│                                  ║
│  [Action Illustration]           ║
│  ──────────────────────────      ║
│  Type: Offensive                 ║
│  Stack: Char + Weapon (req)      ║
│         + Skill (opt)            ║
│  Roll: 1d20 + STR + ATK         ║
│    vs 1d20 + END + DEF           ║
│  Cost: 0 Energy                  ║
│                                  ║
│  [⚡]                      [⚡] ║
└──────────────────────────────────┘
```

### Web App Command Button Overlay

The web app adds interactive buttons **on top of** the print card. These are positioned via a config-driven overlay system and are **never part of the card image**:

```json
{
  "cardOverlays": {
    "character": {
      "buttons": [
        { "id": "flip", "icon": "flip", "position": "top-right", "action": "flipCard" },
        { "id": "reimage", "icon": "refresh", "position": "bottom-right", "action": "reimageCard" }
      ]
    },
    "apparel": {
      "buttons": [
        { "id": "equip", "icon": "checkroom", "position": "bottom-center", "action": "equipCard", "label": "Equip" },
        { "id": "unequip", "icon": "remove_circle", "position": "bottom-center", "action": "unequipCard", "label": "Unequip", "showWhen": "equipped" }
      ]
    },
    "item": {
      "buttons": [
        { "id": "use", "icon": "play_arrow", "position": "bottom-center", "action": "useItem" }
      ]
    }
  }
}
```

**Overlay rules:**
- Buttons are semi-transparent circles/pills over the card surface
- They appear on hover (desktop) or on tap (touch — first tap selects card, buttons appear, second tap on button activates)
- They are never rendered in print export or PDF generation
- Config is per-card-type but can be overridden per-card
- Overlay config stored in `media/cardGame/card-overlays.json`

---

## Equipment System

### v3 Equipment Changes

**v2:** Equipment changes happen freely during a dedicated EQUIP phase between rounds. No cost.

**v3:** Equipment changes happen **during play** as a "Use Item" action that consumes one of your 5 action spots. This creates meaningful trade-offs — equipping better gear costs you an action.

### Character Stack as Card Stack

The character stack is a physical stack of cards:

```
Character Stack (always visible, left sidebar):

┌─── Cloak of Shadows ─── [🛡] ──┐  ← top card border visible
┌─── Steel Shield ─── [🛡] ──────╢  ← right border visible
┌─── Iron Sword ─── [⚔] ────────╢  ← right border visible (weapon = red)
┌─── Plate Armor ─── [🛡] ───────╢  ← right border visible (apparel = silver)
┌─── Elf Ranger ─── [🛡] ────────╢  ← character card visible at bottom
│                                  ║
│  [Full character card face]      ║
│  Stats, needs, portrait          ║
│                                  ║
└──────────────────────────────────┘
```

**Stack ordering (bottom to top):**
1. Character card (always at bottom, face always visible)
2. Body apparel
3. Weapon(s)
4. Shield/off-hand
5. Other equipment (head, feet, ring, back)

**Web app interaction:**
- Click any visible border → that card slides to front
- Equipment modifiers from the character stack apply to ALL action stacks automatically (same as v2)
- The stack is purely visual — mechanically, all equipped cards' stats are summed into the character's base modifiers

### Equipping as an Action

To change equipment mid-game, a player uses one of their 5 action spots:

**Equip Action:**
```
Action Stack: [Use Item] + [New Weapon/Armor from hand]
Effect: Auto-success (no roll required)
  1. Currently equipped item in that slot returns to hand
  2. New item from hand is placed in the slot
  3. Character portrait does NOT regenerate mid-game (too slow)
  4. Character stack visual updates immediately
```

**Rules:**
- **Both weapon and armor can be equipped in the same action** — placing a "Use Item" action with a weapon AND an armor card equips both. This counts as a single action spot.
- A player can equip at any of their action spots (doesn't have to be the first)
- Equipping returns the **current** item in that slot to the player's hand
- If a player has no weapon equipped (dropped in combat), they fight unarmed until they spend an action to equip one
- **Start of game:** Characters start with their initial weapons and armor already equipped from deck build. No equip action needed for starting gear.

### Starting Equipment

At game start, the character's starter deck equipment is pre-equipped:
- **Body armor:** Best DEF apparel from starter deck
- **Weapon(s):** Primary weapon from starter deck
- **Other slots:** Filled from starter deck apparel matching those slots

This mirrors v2's `dealInitialStack()` behavior — equipment is equipped before round 1 begins. The player does not need to "waste" action spots equipping their starting gear.

---

## Round Structure

### v3 Round Flow (6 Phases — EQUIP Phase Removed)

```
1. INITIATIVE PHASE
   └─ All players roll 1d20 + AGI
   └─ Highest total wins initiative (ties: re-roll)
   └─ Initiative winner gets 3 action spots
   └─ Initiative loser gets 2 action spots
   └─ Total: always exactly 5 action spots per round
   └─ CRITICAL INITIATIVE: Nat 1 triggers Per-Round Threat
      at the BEGINNING of the action bar

2. DRAW PHASE (simultaneous)
   └─ Each player draws cards equal to the number of cards
      they PLAYED last round, up to a maximum of 7
   └─ Round 1: draw up to max hand size (7)
   └─ Draw from encounter deck (random, top card)
   └─ If hand exceeds 7 after draw, NO forced discard
      (hand can grow beyond 7)
   └─ Players may voluntarily discard any number of cards
      at any time during the round
   └─ If Threat drawn: must address this round
   └─ If Event drawn: effect applies immediately
   └─ If Discovery (★★★): grants 1 Treasure Vault draw

3. PLACEMENT PHASE (initiative winner places first)
   └─ Initiative winner places action stacks on their
      3 spots (spots 1, 2, 3 — or rearranged)
   └─ Initiative loser places on their 2 spots (spots 4, 5)
   └─ Stacks are OPEN (face-up)
   └─ Consumables committed here are LOCKED IN
   └─ Must place at least 1 action stack
   └─ EQUIP actions placed here like any other action
   └─ [Pause] button pauses the round timer

4. RESOLUTION PHASE (left to right, interleaved)
   └─ Resolve spots 1 → 2 → 3 → 4 → 5
   └─ Each spot resolves fully before the next
   └─ Die roll and result KEPT ON BOARD per step:
      left side = your stats, middle = rolls, right = opponent stats
   └─ DAMAGE IS REAL-TIME: HP changes immediately
   └─ If HP hits 0, game ends IMMEDIATELY
   └─ LOOT DROP: When a player loses a step, non-consumable
      non-use-it-or-lose-it cards from that stack go to the
      LOOT BOX (right side of board)
   └─ Mid-round disruptions may INSERT/REMOVE/MODIFY stacks

5. CLEANUP PHASE
   └─ Discard consumed items
   └─ Return non-consumed Action cards to hand
   └─ Reduce durability on used equipment
   └─ LETHARGY CHECK: hoarding prevention (same as v2)
   └─ Round recovery: LOSER gets +2 HP, WINNER gets +5 HP
   └─ Round winner claims LOOT BOX contents (if any)
   └─ Unresolved threats carry to next round

6. END_THREAT PHASE (if applicable)
   └─ Same as v2 — end threats resolved after cleanup
   └─ Round winner faces threat with 1 bonus stack
```

### Key Differences from v2

| Aspect | v2 | v3 |
|--------|----|----|
| Phases | 7 (INITIATIVE, EQUIP, THREAT_RESPONSE, DRAW_PLACEMENT, RESOLUTION, CLEANUP, END_THREAT) | 6 (INITIATIVE, DRAW, PLACEMENT, RESOLUTION, CLEANUP, END_THREAT) |
| EQUIP phase | Dedicated free phase between rounds | Eliminated — equip via "Use Item" action |
| Action spots | Variable: AP = floor(END/5) + 1 (1-5) | Fixed: always 5 total (3 winner + 2 loser) |
| Position assignment | Odd/even interleaving | Winner gets 3 spots, loser gets 2 |
| Draw timing | Mixed with placement (turn-based) | Separate phase before placement (simultaneous) |
| Draw count | 1 mandatory + optional second draw | Draw = number played last round, max 7 |

### The 5-Spot Action Bar

The action bar is **always exactly 5 spots**. No more, no less (excluding beginning/end threats).

```
Initiative winner gets 3 spots, loser gets 2:

┌─────┬─────┬─────┬─────┬─────┐
│  1  │  2  │  3  │  4  │  5  │
│WIN  │WIN  │WIN  │LOSE │LOSE │
└─────┴─────┴─────┴─────┴─────┘

The winner's 3 spots resolve first (positions 1-3),
then the loser's 2 spots (positions 4-5).
```

**With beginning/end threats:**
```
┌──────┬─────┬─────┬─────┬─────┬─────┬──────┐
│  T1  │  1  │  2  │  3  │  4  │  5  │  T2  │
│Threat│WIN  │WIN  │WIN  │LOSE │LOSE │Threat│
│(Beg) │     │     │     │     │     │(End) │
└──────┴─────┴─────┴─────┴─────┴─────┴──────┘
```

**Why fixed 5 spots:**
- Simpler to understand and balance
- No need for AP calculation from END stat
- END stat still matters for defense rolls (unchanged)
- Initiative becomes the primary tactical advantage (3 vs 2 spots)
- Print-friendly: the board layout is always the same size

### Initiative & Spot Assignment

**Initiative roll:** Each round, every player rolls `1d20 + AGI`. Highest total wins.

**Spot assignment:**
- Initiative winner: spots 1, 2, 3 (resolve first — positional advantage)
- Initiative loser: spots 4, 5 (resolve after the winner)
- The winner acts 3 times before the loser acts — this is a significant advantage

**Why winner goes first (not interleaved):**
- Simpler resolution flow
- Clear strategic value to winning initiative
- AGI becomes more important (governs initiative)
- No confusion about who acts when — winner's 3 spots, then loser's 2

### Encounter Threats on the Bar

Encounter threats drawn during the Draw Phase get their own spots ADDED to the bar:

```
Player: 3 spots (won initiative)
Dire Wolf: 2 spots (difficulty 8 → 2 threat AP)

Bar (7 spots total):
┌─────┬─────┬─────┬──────┬──────┬─────┬─────┐
│  1  │  2  │  3  │  T1  │  T2  │  4  │  5  │
│ Plr │ Plr │ Plr │ Wolf │ Wolf │ Opp │ Opp │
└─────┴─────┴─────┴──────┴──────┴─────┴─────┘

Threat spots insert between winner and loser spots.
Encounter AP from v2 difficulty tiers still applies.
```

---

## Draw & Hand Mechanics

### v3 Draw System

**v2:** Draw 1 card per turn during the combined Draw & Placement phase. Choose to draw again instead of placing.

**v3:** Draw is a separate phase. Draw count = number of cards played last round, capped at 7.

### Draw Rules

1. **Round 1:** Each player draws up to max hand size (7 cards) from the encounter deck
2. **Rounds 2+:** Each player draws cards equal to the number of action stacks they played in the previous round
   - Played 3 stacks last round → draw 3 cards
   - Played 5 stacks last round → draw 5 cards
   - Played 1 stack last round → draw 1 card
   - Never draw more than 7 cards in a single draw phase
3. **Draw source:** All draws from the top of the shuffled encounter deck (random)
4. **No choice:** You must draw the full count — no partial draws or refusals

### Hand Size

- **Maximum draw cap:** 7 (you never draw more than 7 in one phase)
- **No hand size limit:** Your hand can grow beyond 7 cards. You might have 15 cards in hand.
- **Voluntary discard:** Players may discard any number of cards from their hand at any time during their turn (placement phase or between rounds). Discarded cards go to the encounter deck discard pile.
- **Draw cap is the limit, not hand size:** If you have 10 cards in hand and drew 3, you now have 13. That's fine.

### Special Cards

More special cards are mixed into the encounter deck. These are cards with unique one-time effects that add variety:

**Special card types (mixed into encounter deck):**

| Card | Type | Effect | Persistence |
|------|------|--------|-------------|
| **Lucky Charm** | Item (Special) | Next roll gains +3 bonus | Consumable |
| **Mirror Shield** | Item (Special) | Reflect next incoming attack back at attacker | Consumable |
| **Time Skip** | Action (Special) | Skip opponent's next action spot (it resolves as empty) | Returned to hand |
| **Double Down** | Action (Special) | Your next action resolves twice | Consumable |
| **Steal** | Action (Special) | Take 1 random card from opponent's hand | Returned to hand |
| **Ambush** | Action (Special) | Insert 1 extra action spot after current | Consumable |
| **Shield Wall** | Item (Special) | +5 DEF for this round only | Consumable |
| **War Cry** | Action (Special) | All your remaining spots this round get +2 ATK | Consumable |
| **Siphon** | Magic (Special) | Deal 3 damage and heal 3 HP | Consumable |
| **Swap** | Action (Special) | Swap the positions of 2 unresolved spots on the bar | Returned to hand |

**Mix ratio:** Special cards make up ~15% of the encounter deck (up from ~5% in v2).

---

## Loot Box

### Concept

The **Loot Box** is a visible area on the right side of the game board where dropped, lost, and stolen cards accumulate during a round. When a player loses a step in combat, their non-consumable, non-use-it-or-lose-it cards from that action stack go to the loot box instead of being discarded.

### Loot Drop Rules

**When a player LOSES a resolution step** (Deflected, Countered, or Critical Counter outcome):

1. **Consumable cards** in the losing stack are consumed normally (gone)
2. **Use-it-or-lose-it cards** are consumed normally (gone)
3. **All other cards** (weapons, skills, modifier cards, reusable action cards) from the losing action stack go to the **Loot Box**
4. Cards go to the loot box **immediately** when the step resolves

**What goes to the loot box:**
| Card Type | Goes to Loot Box on Loss? |
|-----------|--------------------------|
| Action card (reusable) | Yes — but returned to hand at cleanup if not claimed |
| Skill card | Yes |
| Weapon/item (non-consumable) | Yes |
| Consumable | No — consumed as normal |
| Magic Effect (reusable) | Yes |
| Apparel (equipped) | No — apparel stays equipped unless specifically disarmed |

### Loot Box UI

```
LOOT BOX (right side of board):
┌──────────────────────┐
│     LOOT BOX         │
│  ┌───┐ ┌───┐ ┌───┐  │
│  │🗡️ │ │⭐ │ │🔮 │  │  ← cards visible face-up
│  │Swd│ │Skl│ │Mag│  │
│  └───┘ └───┘ └───┘  │
│                      │
│  3 cards available   │
│  Winner claims all   │
└──────────────────────┘
```

### Claiming the Loot Box

- **Round winner** claims all cards in the loot box at the end of the Cleanup Phase
- Cards transfer to the winner's hand
- If the loot box is empty, nothing to claim
- The loot box is visible to both players throughout the round — you can see what's at stake

### Strategic Implications

- **Risk/reward:** Playing powerful modifier cards on aggressive actions is risky — if you lose that step, those cards go to the loot box and your opponent might claim them
- **Targeted play:** Players may intentionally target an opponent's stack that has valuable modifier cards to try to send them to the loot box
- **Conservative play:** Keeping powerful cards in hand (not played) protects them from loot drops, but reduces your effectiveness
- **Equipment equip actions** are safe — Use Item (equip) is an auto-success, so no risk of loot drop

### Loot Box vs v2 Round Pot

**v2 had a Round Pot** with mandatory ante. **v3 replaces the pot with the Loot Box:**

| v2 Pot | v3 Loot Box |
|--------|------------|
| Mandatory ante each round | No ante — loot box starts empty |
| Cards added from drops, steals, destroyed items | Cards added from LOST action steps |
| Winner claims at cleanup | Winner claims at cleanup |
| Pot jackpot triggers vault draw | Loot box with 5+ cards triggers vault draw |

The loot box is simpler (no ante mechanic) and creates a more natural flow where cards accumulate from actual combat losses.

---

## Game Play Layout

### v3 Layout — The 5-Spot Board

The user-specified layout for v3:

```
┌─────────────────────────────────────────────────────────────────────┐
│  HEADER: Round # | Phase | Initiative | Timer | [⏸] | ⚙            │
├───────────┬─────────────────────────────────────────┬───────────────┤
│           │                                         │               │
│  YOUR     │           ACTION BAR (5 spots)          │   LOOT BOX    │
│  CHAR     │                                         │               │
│  STACK    │  ┌───────┐ ┌───────┐ ┌───────┐         │  ┌───┐ ┌───┐  │
│           │  │ Spot 1│ │ Spot 2│ │ Spot 3│         │  │🗡️ │ │⭐ │  │
│  ┌────┐   │  │ (WIN) │ │ (WIN) │ │ (WIN) │         │  └───┘ └───┘  │
│  │Char│   │  │       │ │       │ │       │         │               │
│  │Armr│   │  └───────┘ └───────┘ └───────┘         │  Dropped/     │
│  │Weap│   │                                         │  Lost/Stolen  │
│  └────┘   │  ┌───────┐ ┌───────┐                   │  cards here   │
│           │  │ Spot 4│ │ Spot 5│                   │               │
│  HP ████  │  │(LOSE) │ │(LOSE) │                   │               │
│  NRG ███  │  │       │ │       │                   │               │
│  MRL ████ │  └───────┘ └───────┘                   │               │
│           │                                         │               │
│           │  ┌─────────────────────────────────┐    │               │
│           │  │ STEP RESULT (per spot)           │    │               │
│           │  │ [Your Stats] [Dice] [Opp Stats]  │    │               │
│           │  └─────────────────────────────────┘    │               │
│           │                                         │               │
│           │  OPPONENT CHARACTER (right side)         │               │
│           │  ┌────┐  HP ████  NRG ███  MRL ████     │               │
│           │  │Char│                                 │               │
│           │  │Armr│                                 │               │
│           │  │Weap│                                 │               │
│           │  └────┘                                 │               │
├───────────┴─────────────────────────────────────────┴───────────────┤
│  YOUR HAND — cards displayed as print cards, horizontally scrollable │
│  [Card] [Card] [Card] [Card] [Card] [Card] [Card]                  │
│                                                    [🗑 Discard]     │
└─────────────────────────────────────────────────────────────────────┘
```

### Jumbled Card Appearance

Cards placed on the action bar should have a **jumbled, natural appearance** like a real card game — not perfectly aligned in a grid:

```
Action spots with jumbled card stacks:

    ┌─────┐
   ┌┤ ATK ├──┐
   │└─┬───┘  │         ┌─────┐
   │ ┌┤Sword├─┤        ┌┤FLEE ├┐
   │ │└─────┘ │       ┌┤└─────┘│        ┌─────┐
   └─┤+2 Skl ├┘       │ (no   ├┘       ┌┤ REST├─┐
     └───────┘         │  mods)│        │└─────┘ │
      Spot 1           └──────┘         └────────┘
                        Spot 2           Spot 3
```

**Implementation:**
- Each card in a stack is offset by a random ±3px horizontal, ±2px vertical, and ±2° rotation
- Core action card is most visible (on top)
- Modifier cards peek out from underneath
- Offset values are set when cards are placed and stay consistent for the round
- Offsets are CSS transforms, not layout changes — no reflow

### Per-Step Roll & Result Display

During resolution, each step's dice roll and result are kept visible on the board (not dismissed):

```
STEP 1 RESULT (kept visible while other steps resolve):
┌───────────────────────────────────────────────────────┐
│  YOUR STATS          ROLL           OPPONENT DEFENSE  │
│  ─────────          ──────          ────────────────  │
│  STR: 14            [d20: 17]       END: 12           │
│  Sword: +4          + 21            Armor DEF: +5     │
│  Skill: +2          ──────          Parry: +2         │
│  ──────             Total: 38       ──────            │
│  Total: +21         vs 31           Total: +19        │
│                     ──────                            │
│                   SOLID HIT                           │
│                   7 damage                            │
└───────────────────────────────────────────────────────┘
```

**Display rules:**
- Each spot's result panel stays visible after resolution
- Active spot has a highlighted/glowing border
- Resolved spots show the complete breakdown: your stats (left), dice rolls (center), opponent stats (right)
- The result (SOLID HIT, DEFLECTED, etc.) and damage are prominent in the center
- Spots not yet resolved show "—" or an empty state

---

## Combat Resolution

### Core Mechanics — Unchanged from v2

The opposed roll system, outcome table (9 tiers from Critical Hit to Critical Counter), damage calculation, critical effects (drop & disable), natural 20/1 rules, and critical range (skilled vs unskilled) are **all unchanged from v2**. See v2 sections:
- [Opposed Roll System](cardGame-v2.md#opposed-roll-system-1d20)
- [Outcome Table](cardGame-v2.md#outcome-table)
- [Damage Calculation](cardGame-v2.md#damage-calculation)
- [Critical Range](cardGame-v2.md#critical-range--skilled-vs-unskilled-actions)

### v3 Combat Changes

1. **Loot drops on loss:** When a player loses a step (Deflected or worse), non-consumable cards from that stack go to the loot box (see [Loot Box](#loot-box))
2. **No AP concept:** Action spots are fixed (3/2 split), not derived from END
3. **Step results stay visible:** Each step's roll breakdown persists on the board (see [Per-Step Roll & Result Display](#per-step-roll--result-display))
4. **Equipment loss in combat:** If a Critical Hit/Counter forces an item drop, the dropped item goes to the **Loot Box** (not the pot, which no longer exists)

---

## Character Stack & Action Stacks

### Character Stack — Updated

The character stack is now a visible **physical card stack** with stacking borders (see [Equipment System](#equipment-system)):

```
CHARACTER STACK = CharPerson card (bottom) + [Equipped Apparel cards] + [Equipped Weapon/Shield cards]
```

- The character card's face is always visible (bottom of stack, face exposed)
- Equipment cards are stacked on top with their **top and right borders visible**
- Click any visible border to bring that card to front (web app only)
- All equipment modifiers apply automatically to action stacks (same as v2)

### Action Stacks — Same as v2

Action stacks placed on the 5-spot bar follow the same rules as v2:
```
ACTION STACK = CoreCard(s) + [ModifierCards...]
```

The only difference is that stacks are placed on fixed spots (3 for winner, 2 for loser) rather than interleaved odd/even positions.

---

## Mid-Round Disruption

*Unchanged from v2.* INSERT, REMOVE, and MODIFY mechanics work the same way. See v2 section: [Mid-Round Disruption](cardGame-v2.md#mid-round-disruption).

The only layout difference: disruptions INSERT into the 5-spot bar, potentially extending it beyond 5 spots temporarily.

---

## Talk / Chat Action Card

### Core Mechanics — Unchanged from v2

The Talk card rules (Silence Rule, online LLM chat, RL talk, outcomes) are unchanged. See v2: [Talk / Chat Action Card](cardGame-v2.md#talk--chat-action-card).

### Chat System Refactor (v3)

The cardGame chat system is refactored to use the **new/recent AM7 chat system refactor** with chat config policies:

**v2 chat:** Direct WebSocket calls via `page.wss.send("chat", ...)` with custom handlers in `gameChatManager.js`.

**v3 chat:** Uses the standard AM7 chat config policy system:

```json
{
  "chatPolicies": {
    "talkCard": {
      "configId": "cardGame-talk",
      "baseTemplate": "Open Chat",
      "systemPrompt": "You are {npc.name}, a {npc.race} {npc.trade}. Personality: {npc.personality}. You are in a card game encounter. Respond in character.",
      "contextFields": ["npc.charPerson", "player.charPerson", "gameState.round", "gameState.needs"],
      "maxTurns": 10,
      "evaluateOnClose": true,
      "evaluationPrompt": "Evaluate the conversation outcome: agreement, info exchange, or deception. Return JSON: { outcome, bonusModifier }",
      "streaming": true
    },
    "narrator": {
      "configId": "cardGame-narrator",
      "baseTemplate": "Open Chat",
      "systemPrompt": "{narratorProfile.personality}",
      "contextFields": ["gameState", "roundHistory"],
      "singleResponse": true,
      "streaming": true
    },
    "aiDirector": {
      "configId": "cardGame-director",
      "baseTemplate": "Open Chat",
      "systemPrompt": "You are {opponent.name}...",
      "contextFields": ["gameState", "opponent.hand", "player.charStack"],
      "singleResponse": true,
      "responseFormat": "json",
      "streaming": false
    }
  }
}
```

**Benefits of using chat config policies:**
- Centralized configuration (not hardcoded in JS)
- Reuses the AM7 chat infrastructure (session management, history, streaming)
- Chat configs can be overridden per-theme
- Easier to debug and modify chat behavior without code changes
- Chat history automatically managed by the AM7 chat system

---

## Magic System

*Unchanged from v2.* See v2: [Magic System](cardGame-v2.md#magic-system).

---

## Needs & Survival

*Unchanged from v2.* See v2: [Needs & Survival](cardGame-v2.md#needs--survival).

---

## AI Modes

*Unchanged from v2.* See v2: [AI Modes](cardGame-v2.md#ai-modes).

The only change: AI opponent now fills 2 spots (when it loses initiative) or 3 spots (when it wins initiative) instead of variable AP-based spot count.

---

## Card Arrangement UX

### v3 UX — Tablet-First Redesign

The v3 UX is designed **tablet-first** with desktop and phone as secondary targets. All interactive elements must be usable with touch on a 10" tablet.

### Hand Tray

Cards in hand are displayed as **print-sized cards** (not compact thumbnails) in a horizontally scrollable tray:

```
┌──────────────────────────────────────────────────────────────────┐
│  YOUR HAND (12 cards)                              [🗑 Discard]  │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐  → scroll    │
│  │ ATK │ │FLEE │ │REST │ │Heal │ │Sword│ │Skill│              │
│  │     │ │     │ │     │ │Pot. │ │     │ │     │              │
│  │     │ │     │ │     │ │     │ │     │ │     │              │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘              │
└──────────────────────────────────────────────────────────────────┘
```

- Cards are full print-card appearance (same rendering as printed cards)
- Horizontal scroll with momentum/inertia on touch
- Drag cards from hand to action spots
- **Discard button:** Tap to enter discard mode, then tap cards to discard them. Tap discard button again to exit.

### Drag-and-Drop (Touch-First)

| Action | Touch Gesture | Desktop Equivalent |
|--------|--------------|-------------------|
| Select card | Tap | Click |
| View card detail | Tap (selected card) | Click (selected card) |
| Pick up card for drag | Long-press (200ms) | Click and drag |
| Drop on action spot | Release on spot | Release on spot |
| Discard | Drag off-board or tap Discard | Drag to discard area |
| View stack card | Tap visible border | Click visible border |

### Action Spot Interaction

During placement phase, empty action spots show a "+" drop target:

```
┌───────────┐  ┌───────────┐  ┌───────────┐
│           │  │  ┌─────┐  │  │           │
│    (+)    │  │  │ ATK │  │  │    (+)    │
│  Drop     │  │  │+Swd │  │  │  Drop     │
│  card     │  │  └─────┘  │  │  card     │
│  here     │  │  Spot 2   │  │  here     │
└───────────┘  └───────────┘  └───────────┘
   Spot 1       (filled)         Spot 3
```

- Tap "+" to open action picker (same as v2 virtual action cards)
- Or drag a card from hand directly onto the spot
- Filled spots can be tapped to view/modify the stack

---

## Poker Face (Online Only)

*Unchanged from v2.* See v2: [Poker Face](cardGame-v2.md#poker-face-online-only).

---

## Live Narration System (Online Only)

*Unchanged from v2.* See v2: [Live Narration System](cardGame-v2.md#live-narration-system-online-only).

---

## Image Generation Pipeline

*Unchanged from v2.* See v2: [Image Generation Pipeline](cardGame-v2.md#image-generation-pipeline).

---

## Print Specifications

### Card Dimensions — Same as v2

Standard poker card: **2.5" × 3.5"** (63.5mm × 88.9mm). At 300 DPI: **750 × 1050 pixels**.

### v3 Print Additions

#### Stacking Borders on Printed Cards

Every printed card includes the stacking borders:
- **Top border:** 5mm tall, card name + type icon, readable when stacked
- **Right border:** 4mm wide, type-colored bar + small type icon (rotated 90°), visible when stacked

These borders are part of the card design itself — they exist in the print template, not as an overlay.

#### Character Card Double-Sided

Character cards are printed double-sided:
- **Front:** Simplified stats (6 core stats, 3 need tracks, portrait)
- **Back:** Extended info (alignment, description, personality, equip slots, skill slots, XP)

All other card types have decorative type-colored backs (same as v2).

#### Print Card Template (v3)

```
┌─── [Card Name (truncated to fit)] ─── [T] ─┐
│                                              │▒ ← right border
│  ┌──────────────────────────────────────┐   │▒    (type color
│  │                                      │   │▒     4mm wide)
│  │         [Card Image Area]            │   │▒
│  │                                      │   │▒
│  └──────────────────────────────────────┘   │▒
│  ────────────────────────────────────────   │▒
│  [Stat Line 1 — most important]             │▒
│  [Stat Line 2 — secondary stats]            │▒
│  [Stat Line 3 — special/durability]         │▒
│  ────────────────────────────────────────   │▒
│  [T]                                   [T]  │▒
└─────────────────────────────────────────────┘
```

**[T] = type icon** (24×24 print, 32×32 at 300 DPI)

#### Reference Card (Updated for v3)

The printable reference card is updated for v3 rules:
- 5-spot action bar (3/2 split)
- No EQUIP phase — equip via Use Item
- Draw = cards played last round (max 7)
- Loot box rules
- Same outcome table, critical ranges, etc.

### Print Layout — Same as v2

3×3 per US Letter page, duplex-ready. See v2: [Print Layout](cardGame-v2.md#print-layout).

### Print Production Specifications

#### Bleed & Trim

```
┌─── BLEED AREA (3mm / 0.125" beyond cut line) ───┐
│ ┌─── CUT LINE ───────────────────────────────┐  │
│ │ ┌─── SAFE AREA (5mm / 0.2" inside cut) ──┐ │  │
│ │ │                                          │ │  │
│ │ │  [Top stacking border - card name]       │ │  │
│ │ │  [Card content]              [Right brdr]│ │  │
│ │ │                                          │ │  │
│ │ └──────────────────────────────────────────┘ │  │
│ └──────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────┘

Final card size: 2.5" × 3.5" (63.5mm × 88.9mm)
With bleed: 2.75" × 3.75" (69.85mm × 95.25mm)
Safe area: 2.1" × 3.1" (53.34mm × 78.74mm)
```

- **Bleed:** 3mm (0.125") on all four sides — extends card art/color past the cut line
- **Safe area:** 5mm (0.2") inside the cut line — all text and critical elements must stay within safe area
- **Stacking borders** extend into bleed (top and right borders bleed to edge for full coverage when cut)

#### Color Profile

- **Generation:** All card art generated in sRGB (SD output default)
- **Screen rendering:** sRGB (standard web)
- **Print export:** sRGB for home printing; optional CMYK conversion toggle for professional print shops
- **Card type border colors** specified in both sRGB hex and approximate CMYK:

| Card Type | sRGB Hex | Approx CMYK |
|-----------|----------|-------------|
| Character | #C5A55A | C:0 M:20 Y:60 K:20 |
| Apparel | #A0A0A0 | C:0 M:0 Y:0 K:40 |
| Item | #2E5A2E | C:60 M:0 Y:60 K:50 |
| Action | #D4380D | C:0 M:80 Y:95 K:10 |
| Talk | #2F4F6F | C:70 M:30 Y:0 K:40 |
| Encounter | #4A2060 | C:50 M:80 Y:0 K:40 |
| Skill | #8B5A00 | C:0 M:40 Y:100 K:40 |
| Magic | #2F6A6A | C:70 M:0 Y:0 K:40 |

#### Paper Stock

- **Recommended:** 300gsm (110lb) card stock, smooth matte or satin finish
- **Minimum:** 250gsm (90lb) for home printing
- **Professional:** 310gsm blue-core card stock (standard for TCG printing)
- **Finish:** Matte recommended (reduces glare under game lighting). Gloss acceptable.
- **Duplex alignment:** Registration marks printed on each sheet for front/back alignment

#### Font Requirements

- **Primary font:** Theme-configured (`theme.artStyle.fontFamily`), default: system sans-serif
- **Fallback chain:** Theme font → "Inter" → "Segoe UI" → "Helvetica Neue" → sans-serif
- **Print rendering:** All text rendered as vector paths in PDF export (not rasterized) for crisp output at any print resolution
- **Minimum font sizes:** Card name: 10pt, stat labels: 8pt, stat values: 9pt, flavor/special text: 7pt
- **Stacking border text:** Card name on top border at 7pt bold, must be legible when printed at 300 DPI

#### Crop Marks & Registration

- **Crop marks:** Thin (0.25pt) black lines at each card corner extending 3mm outside the bleed area
- **Registration marks:** Small crosshairs at page corners for duplex alignment
- **Color bars:** Optional CMYK color calibration strip at page bottom (professional export only)

### Print Export — Same as v2

PDF, PNG, ZIP export. See v2: [Print-Ready Card Export](cardGame-v2.md#print-ready-card-export).

---

## Deck Theme Configuration

*Unchanged from v2.* See v2: [Deck Theme Configuration](cardGame-v2.md#deck-theme-configuration).

The only addition: themes may include `specialCards` arrays in their card pools to define theme-specific special cards.

---

## Deck Builder & Snapshot Architecture

*Unchanged from v2.* See v2: [Deck Builder & Snapshot Architecture](cardGame-v2.md#deck-builder--snapshot-architecture).

---

## Online Implementation

### Updated Endpoints for v3

**Changed endpoints:**

| Endpoint | Change |
|----------|--------|
| `POST /rest/game/v2/equip` | Now costs an action spot. Request must include the spot number being used for equip. |
| `POST /rest/game/v2/placeStacks` | Now expects exactly 3 stacks (winner) or 2 stacks (loser), not variable AP count. |
| `POST /rest/game/v2/draw` | New draw count logic: `min(cardsPlayedLastRound, 7)`. No longer draws 1 per turn. |

**New endpoints:**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v3/discard` | POST | Discard cards from hand (voluntary) |
| `GET /rest/game/v3/lootBox` | GET | Get current loot box contents |
| `POST /rest/game/v3/lootBox/claim` | POST | Round winner claims loot box |

**Removed endpoints:**

| Endpoint | Reason |
|----------|--------|
| `POST /rest/game/v2/ante` | No more mandatory ante (loot box replaces pot) |

### WebSocket Events — Updated

| Event | Change |
|-------|--------|
| `game.v3.lootBox.updated` | NEW — card added to loot box |
| `game.v3.lootBox.claimed` | NEW — round winner claimed loot box |
| `game.v3.hand.discarded` | NEW — player discarded cards |
| `game.v2.ante.placed` | REMOVED — no ante mechanic |

All other WebSocket events from v2 remain unchanged.

### Client Architecture — Same Structure, Refactored Code

The 29-module IIFE architecture remains. Key changes:
- CSS migrated to Tailwind + game CSS (see [Code Refactor Plan](#code-refactor-plan))
- Chat modules refactored to use chat config policies
- `phaseUI.js` updated to remove EquipPhaseUI (equip is now an action)
- `actions.js` updated for 5-spot fixed bar and draw=play mechanics
- `gameView.js` updated for loot box UI

---

## Code Refactor Plan

### 1. CSS Migration to Tailwind

**Current state:** ~2500 lines of custom CSS in `cardGame-v2.css` with `cg2-` prefixed classes.

**Target:** Migrate to Tailwind utility classes where possible, keeping a minimal `cardGame.css` for game-specific styles that Tailwind can't handle.

**Migration strategy:**

| CSS Category | Migration Target |
|-------------|-----------------|
| Layout (flex, grid, positioning) | Tailwind utilities (`flex`, `grid`, `absolute`, etc.) |
| Spacing (margin, padding) | Tailwind spacing (`m-2`, `p-4`, etc.) |
| Typography (font, size, color) | Tailwind typography (`text-sm`, `font-bold`, etc.) |
| Colors (backgrounds, borders) | Tailwind colors + CSS custom properties for card type colors |
| Card-specific styles (borders, stacking, type colors) | Keep in `cardGame.css` |
| Animations (card flip, dice roll, transitions) | Keep in `cardGame.css` |
| Print styles (@media print) | Keep in `cardGame.css` |

**Custom properties to keep:**
```css
/* cardGame.css — game-specific properties */
:root {
  --card-width: 180px;
  --card-aspect: 2.5 / 3.5;
  --card-character-color: #C5A55A;
  --card-apparel-color: #A0A0A0;
  --card-item-color: #2E5A2E;
  --card-action-color: #D4380D;
  --card-talk-color: #2F4F6F;
  --card-encounter-color: #4A2060;
  --card-skill-color: #8B5A00;
  --card-magic-color: #2F6A6A;
  --stacking-border-width: 4px;
  --stacking-border-top-height: 24px;
}
```

**File rename:** `cardGame-v2.css` → `cardGame.css` (minimal game-specific styles only)

### 2. Chat System Refactor

**Current state:** `gameChatManager.js` makes direct WebSocket calls with custom prompt construction.

**Target:** Use AM7 chat config policies for all LLM interactions.

**Refactor steps:**

1. Create chat config policy files:
   - `media/cardGame/prompts/talk-card.chatConfig.json`
   - `media/cardGame/prompts/narrator.chatConfig.json`
   - `media/cardGame/prompts/director.chatConfig.json`
   - `media/cardGame/prompts/combat-eval.chatConfig.json`

2. Refactor `gameChatManager.js`:
   - Remove direct WebSocket `page.wss.send("chat", ...)` calls
   - Use `am7chat.startChat(configId, contextData)` instead
   - Chat sessions managed by AM7 chat system (not custom state)

3. Refactor `narrator.js`:
   - Use narrator chat config policy
   - Single-response mode (not streaming for narration)

4. Refactor `director.js`:
   - Use director chat config policy
   - JSON response format for action placement

5. Benefits:
   - Chat configs editable without code changes
   - Chat history managed by AM7 (not custom)
   - Theme-specific chat configs via `aiConfigs.chatConfigs` in theme config
   - Consistent error handling and retry from AM7 chat system

### 3. Code Simplification

**Targets for simplification:**

| Area | Current | Target |
|------|---------|--------|
| Phase management | 7 phases with complex transitions | 6 phases with simpler flow |
| AP calculation | `floor(END/5) + 1` with min/max, encounter AP | Fixed 5 spots, 3/2 split |
| Position interleaving | Odd/even assignment with wrap-around | Winner spots 1-3, loser spots 4-5 |
| Equip phase UI | Dedicated `EquipPhaseUI` component | Remove entirely — equip is an action |
| Draw logic | Turn-based draw-or-place with desperation draw | Simple: draw = cards played, max 7 |
| Pot system | Mandatory ante, mid-round additions, winner claims | Loot box: only filled from combat losses |
| Card sizing | Inconsistent across UI areas | Unified: always print-card proportions |

### 4. File Consolidation

Consider merging small modules:
- `d20Dice.js` → merge into `cardComponents.js` (it's one component)
- `cardPreview.js` → merge into `overlays.js`
- `gameOverUI.js` → merge into `phaseUI.js`
- `threatUI.js` → merge into `phaseUI.js`

Target: reduce from 29 modules to ~22-24 without losing modularity.

---

## Test Plan

### v3 Test Requirements

All cardGame UX tests must be updated to test **every aspect** of v3 gameplay. The existing `testMode.js` in-browser test suite must be expanded.

### Test Categories

#### 1. Card Rendering Tests
- [ ] All 8 card types render correctly with print-first layout
- [ ] Stacking borders visible on all cards (top + right)
- [ ] Character card front shows simplified stats only
- [ ] Character card back shows extended info
- [ ] Card flip animation works
- [ ] Cards render identically at all sizes (hand, spot, preview)
- [ ] Print export produces identical cards to screen

#### 2. Equipment System Tests
- [ ] Character stack shows as stacked cards with visible borders
- [ ] Click border → card slides to front
- [ ] Starting equipment pre-equipped at game start
- [ ] Use Item (equip) action works: places weapon in slot, returns old to hand
- [ ] Use Item (equip) action costs 1 action spot
- [ ] Both weapon + armor can be equipped in same action
- [ ] Unarmed combat works when no weapon equipped
- [ ] Equipment dropped in combat → goes to loot box

#### 3. Round Structure Tests
- [ ] Initiative roll determines 3/2 spot split
- [ ] Winner gets spots 1-3, loser gets spots 4-5
- [ ] Draw phase: correct number drawn (= cards played last round)
- [ ] Draw phase: max 7 draw cap enforced
- [ ] Round 1: draw up to 7
- [ ] No EQUIP phase exists
- [ ] Placement enforces correct spot count (3 for winner, 2 for loser)
- [ ] Resolution resolves left to right (1→2→3→4→5)
- [ ] Cleanup: recovery, durability, lethargy
- [ ] End threats work as in v2

#### 4. Draw & Hand Tests
- [ ] Hand can grow beyond 7 cards
- [ ] Voluntary discard works during placement phase
- [ ] Discarded cards go to encounter deck discard pile
- [ ] Draw correctly counts "cards played last round"
- [ ] Special cards mixed into encounter deck

#### 5. Loot Box Tests
- [ ] Loot box starts empty each round
- [ ] Non-consumable cards go to loot box on step loss
- [ ] Consumable cards consumed normally (not to loot box)
- [ ] Loot box visible to both players
- [ ] Round winner claims loot box at cleanup
- [ ] Loot box with 5+ cards triggers vault draw

#### 6. Combat Resolution Tests
- [ ] All 9 outcome tiers work correctly
- [ ] Per-step result display shows your stats / dice / opponent stats
- [ ] Results persist on board after resolution
- [ ] Critical effects (drop, disable) work
- [ ] Natural 20/1 rules work
- [ ] Loot drops on loss (Deflected or worse)

#### 7. Action Type Tests
- [ ] Attack, Flee, Investigate, Trade, Rest, Use Item, Craft all work
- [ ] Use Item (equip) action properly equips/unequips
- [ ] Use Item (consumable) consumes the item
- [ ] Talk card opens LLM chat (online)
- [ ] Magic spells cost energy and resolve correctly

#### 8. AI Opponent Tests
- [ ] AI fills 2 or 3 spots (based on initiative)
- [ ] AI makes reasonable action selections
- [ ] LLM director called for placement
- [ ] AI handles equipment equipping
- [ ] AI responds to mid-round disruptions

#### 9. UI / Tablet Tests
- [ ] Layout works at 1024×768 (tablet landscape)
- [ ] All touch targets ≥ 44×44px
- [ ] Drag-and-drop works with touch
- [ ] Horizontal scroll works in hand tray
- [ ] Cards readable at tablet card sizes
- [ ] No overlapping elements on tablet
- [ ] Action spots touch-accessible
- [ ] Discard button works on touch

#### 10. Print Tests
- [ ] Card export produces correct stacking borders
- [ ] Character card front/back both print correctly
- [ ] Card stats match between screen and print
- [ ] PDF layout: 3×3 per page, duplex-ready
- [ ] Reference card updated for v3 rules

#### 11. Chat System Tests
- [ ] Talk card chat uses chat config policy
- [ ] Narrator uses chat config policy
- [ ] AI director uses chat config policy
- [ ] Chat sessions properly managed
- [ ] Chat history persists within game session

#### 12. Save/Load Tests
- [ ] Auto-save includes loot box state
- [ ] Auto-save includes draw count from last round
- [ ] Resume correctly restores 5-spot layout
- [ ] Resume correctly restores equipment stack

---

## Tablet UX Plan

### Current Problems (v2)

1. **Cards too padded:** Excessive margins/padding waste screen real estate on tablets
2. **Inconsistent sizing:** Cards render at different sizes in hand, action bar, initiative, and sidebars
3. **Non-dynamic areas:** Fixed-size panels don't adapt to screen size
4. **Game unplayable on tablets:** Touch targets too small, layout overflows

### v3 Tablet Fixes

#### Responsive Card Sizing

```css
/* cardGame.css */
:root {
  --card-width: 180px;  /* desktop default */
}

@media (max-width: 1199px) and (min-width: 768px) {
  /* Tablet landscape */
  :root {
    --card-width: 120px;
  }
}

@media (max-width: 767px) {
  /* Phone / tablet portrait */
  :root {
    --card-width: 90px;
  }
}
```

All card displays use `var(--card-width)` with `aspect-ratio: 2.5 / 3.5` for consistent proportions everywhere.

#### Tablet Layout (768-1199px)

```
┌─────────────────────────────────────────┐
│ HEADER: Round | Phase | Timer | ⏸ | ⚙  │
├─────┬───────────────────────────┬───────┤
│CHAR │     ACTION BAR (5 spots)  │ LOOT  │
│STACK│  [1] [2] [3]              │ BOX   │
│(min)│  [4] [5]                  │       │
│     │  [Step Result Display]    │       │
│     │  OPP CHAR (compact)       │       │
├─────┴───────────────────────────┴───────┤
│ HAND: [card] [card] [card] → scroll    │
└─────────────────────────────────────────┘
```

**Tablet-specific adjustments:**
- Character stack sidebar: 80px wide (collapsed, borders only)
- Loot box: 80px wide (card count + icons only, tap to expand)
- Action spots: fill remaining width
- Hand tray: full-width horizontal scroll with 120px cards
- All tap targets ≥ 44px

#### Padding/Margin Reduction

**v2 problem:** `cg2-` classes use generous padding (16px, 24px) designed for desktop.

**v3 fix:** Use Tailwind's responsive padding:
```html
<!-- Example: action spot -->
<div class="p-4 md:p-2 sm:p-1">
```

Or CSS custom properties:
```css
:root {
  --game-padding: 16px;
}
@media (max-width: 1199px) {
  :root { --game-padding: 8px; }
}
@media (max-width: 767px) {
  :root { --game-padding: 4px; }
}
```

#### Touch Target Compliance

Every interactive element must have a **minimum 44×44px touch target** (Apple HIG / Google Material guidelines):

| Element | Min Touch Target | Notes |
|---------|-----------------|-------|
| Cards in hand | 90×126px (at smallest) | Scrollable tray |
| Action spots | 80×80px (min) | Drop targets |
| Buttons | 44×44px | All buttons |
| Border click zones | 44px wide/tall | Stack navigation |
| Discard button | 44×44px | Always visible |

---

## Proposed Additional Refactors

### Requirement 12: Other Areas to Simplify

#### 1. Consolidate Card Rendering Pipeline

**Current:** `cardFace.js` has `renderCardBody()` with per-type routing and `CARD_RENDER_CONFIG` data-driven rendering.

**Proposal:** Unify all card rendering into a single `CardRenderer` class that takes a card data object and a render size, producing identical output for screen, print, and export. The renderer should use a template system driven entirely by `CARD_RENDER_CONFIG` — no per-type `render*Body()` functions.

#### 2. Remove Health/Energy Denomination Cards

**Current (v2):** Health and energy tracking uses physical denomination cards (+1, +2, +5, +10) for IRL play. This adds complexity to the deck (extra cards to print, change-making logic, card bank management).

**Proposal:** Simplify to **numeric tracking only**:
- Online: numeric bars (already implemented)
- IRL: use a d20 or health dial to track HP/NRG. Print a tracking sheet instead of denomination cards.
- Remove denomination card rendering, card bank logic, and change-making rules
- Reduces deck size by ~20 cards per player

#### 3. Simplify Encounter Deck Composition

**Current:** Complex card count tables with per-player-count scaling formulas.

**Proposal:** Standardize at a flat 60-card encounter deck for all player counts. For 3-4 players, shuffle in a second copy of the encounter deck rather than scaling individual categories.

#### 4. Remove Wearable Icon Pipeline

**Current:** Separate SD pipeline for individual `olio.wearable` icons (512×512).

**Proposal:** Use apparel mannequin images for all equipment display. The wearable icon pipeline adds complexity and queue time for minimal visual value — the mannequin images already show what equipment looks like.

#### 5. Simplify Card Style Composition

**Current:** LLM-composed `cardStyleDef` JSON that defines borders, text regions, icon styles, and colors. Complex multi-step process (LLM → SD for frame elements → client renderer).

**Proposal:** Use a fixed set of 3-5 pre-built card style templates (clean, parchment, metallic, dark, neon). Theme config selects one. Remove the LLM card style composition pipeline entirely. This eliminates a flaky LLM call and several SD generations per deck build.

#### 6. Event System for State Changes

**Current:** Direct state mutation in various modules. Game state changes happen inline in combat resolution, action processing, etc.

**Proposal:** Introduce a simple event bus for state changes:
```javascript
CardGame.events.emit('damage', { target: 'player', amount: 5, source: 'combat' });
CardGame.events.emit('cardGained', { player: 'player', card: lootCard });
CardGame.events.emit('lootDrop', { card: weaponCard });
```

This decouples UI updates from game logic and makes testing easier — assert on events emitted rather than state mutations.

#### 7. Externalize Action Definitions

**Current:** `ACTION_DEFINITIONS` in `gameConstants.js` with inline formulas.

**Proposal:** Move all action definitions to `action-definitions.json` (already partially done in v2). Make the action system fully data-driven — adding a new action type requires only adding a JSON entry, not code changes.

---

## Rules Quick Reference

### Printable Reference Card (v3)

```
╔══════════════════════════════════════════════╗
║  CARD GAME v3 — QUICK REFERENCE             ║
╠══════════════════════════════════════════════╣
║                                              ║
║  ROUND FLOW:                                 ║
║  1. Roll initiative (1d20 + AGI)             ║
║  2. Draw cards (= cards played last round,   ║
║     max 7; Round 1 = draw 7)                 ║
║  3. Place action stacks on 5 spots           ║
║     Winner: 3 spots | Loser: 2 spots         ║
║  4. Resolve spots 1→2→3→4→5                  ║
║  5. Cleanup — round winner claims loot box   ║
║                                              ║
║  5 SPOTS, 3-AND-2:                           ║
║  Initiative winner → spots 1, 2, 3           ║
║  Initiative loser  → spots 4, 5              ║
║  Winner's spots resolve FIRST                ║
║                                              ║
║  EQUIPPING: Use Item action (costs 1 spot)   ║
║  Can equip weapon + armor in same action     ║
║  Current equipment returns to hand           ║
║                                              ║
║  CHARACTER STACK (sidebar, always active):    ║
║  Person + Apparel + Weapon (stacked cards)   ║
║  → base modifiers apply to ALL actions       ║
║                                              ║
║  ACTION STACK (on the bar, 1 per spot):      ║
║  Core card(s) + Modifier card(s)             ║
║  Consumable cores = use-or-lose              ║
║                                              ║
║  DEFENSE: Passive armor (always) + Parry     ║
║  Armor CAN fully block non-critical damage.  ║
║  Criticals always deal min 2 dmg.            ║
║                                              ║
║  ROLL: 1d20 + char base + action modifiers   ║
║    vs 1d20 + END + Armor DEF (+ Parry)       ║
║                                              ║
║  OUTCOMES (attacker - defender):              ║
║  +10 CRIT HIT: 2× dmg, drop → loot box      ║
║   +5 Solid Hit: full damage                  ║
║   +1 Glancing: half damage                   ║
║    0 Stalemate: nothing                      ║
║   -1 Deflected: weapon -1 dur               ║
║   -5 Countered: half dmg to attacker         ║
║  -10 CRIT COUNTER: full dmg to attacker,     ║
║      drop + lose next action                 ║
║  Nat 20 = ALWAYS SUCCEEDS + upgrade 1 tier  ║
║  Nat 1  = ALWAYS FAILS + downgrade + drop   ║
║                                              ║
║  LOOT BOX:                                   ║
║  When you LOSE a step, non-consumable cards  ║
║  from that stack go to the loot box.         ║
║  Round winner claims all loot box cards.     ║
║  Loot box with 5+ cards → vault draw.        ║
║                                              ║
║  DRAW: Each round, draw cards = number you   ║
║  played last round (max 7). Hand can grow    ║
║  beyond 7. Discard freely at any time.       ║
║                                              ║
║  SPECIAL CARDS: Mixed into encounter deck.   ║
║  One-time effects: Lucky Charm, Mirror       ║
║  Shield, Time Skip, Ambush, etc.             ║
║                                              ║
║  PER-ROUND THREATS (0–3 per round):          ║
║  Nat 1 initiative → threat at BEGINNING      ║
║  Scenario card → threat at END               ║
║                                              ║
║  ANTI-HOARDING (action/talk cards only):      ║
║  LETHARGY: Hold 2+ same type, played 0       ║
║    → keep 1, return extras                   ║
║  EXHAUSTED: Same action 2+ times, last       ║
║    failed → keep 1, return extras            ║
║                                              ║
║  MAGIC: Skill Type + min stat + Energy cost  ║
║  TALK CARD: Required to communicate          ║
║                                              ║
╚══════════════════════════════════════════════╝
```

### Victory Conditions — Same as v2

| Mode | Win Condition |
|------|-------------|
| vs AI (Mode 1) | Reduce opponent to 0 HP |
| Story Mode (Mode 2) | Survive 20 rounds OR complete scenario objective |
| Free-for-all (multiplayer) | Last player standing |
| Campaign | Survive across sessions, level up |

---

## Phased Implementation Plan

Build-test-build. Each phase produces a testable artifact.

### Phase v3.1 — Print-First Card Rendering

**Goal:** All cards render with print-first layout, stacking borders, and simplified stats.

**Build:**
- Update `CardFace` component with stacking border rendering (top + right borders)
- Simplify stat display on all card fronts (gameplay-relevant only)
- Character card back component (extended info)
- Card flip animation for character card
- Update `CARD_RENDER_CONFIG` for simplified layouts
- Print template includes stacking borders

**Test gate:**
- [ ] All 8 card types show stacking borders (top name bar + right type bar)
- [ ] Character card front shows only 6 stats + 3 needs
- [ ] Character card back shows alignment, description, equip slots, skills
- [ ] Stacking borders visible when cards overlapped
- [ ] Click border in stack → card to front (web app)

---

### Phase v3.2 — Equipment System Refactor

**Goal:** Character stack is a card stack. EQUIP phase removed. Equipping is a "Use Item" action.

**Build:**
- Refactor character sidebar as stacked card component with visible borders
- Remove `EquipPhaseUI` component
- Update `actions.js`: "Use Item (equip)" action type
- Update `gameState.js`: remove EQUIP phase from phase transitions
- Starting equipment auto-equipped at game start (existing `dealInitialStack()`)
- Equipment returns to hand when replaced

**Test gate:**
- [ ] No EQUIP phase in game flow
- [ ] Character sidebar shows stacked cards with borders
- [ ] Use Item action equips weapon/armor and returns old to hand
- [ ] Both weapon + armor equippable in single action
- [ ] Starting equipment works without equip phase

---

### Phase v3.3 — 5-Spot Action Bar & Initiative

**Goal:** Fixed 5 spots with 3/2 initiative split.

**Build:**
- Update action bar to fixed 5 spots
- Update initiative: winner gets 3, loser gets 2
- Remove AP calculation from END
- Update AI opponent to fill 2 or 3 spots
- Jumbled card appearance (CSS random offsets)
- Per-step result display (stats/dice/result persisted on board)

**Test gate:**
- [ ] Action bar always has exactly 5 spots
- [ ] Initiative winner fills spots 1-3
- [ ] Initiative loser fills spots 4-5
- [ ] Encounter threats add extra spots between winner/loser
- [ ] Jumbled card appearance on placed stacks
- [ ] Step results persist on board

---

### Phase v3.4 — Draw & Hand Mechanics

**Goal:** Draw = cards played, max 7. Voluntary discard.

**Build:**
- Separate Draw Phase (before Placement)
- Draw count = cards played last round, max 7
- Round 1 draw = 7
- Hand size unlimited (can grow beyond 7)
- Discard button/mode in hand tray
- Special cards mixed into encounter deck

**Test gate:**
- [ ] Draw phase is separate from placement
- [ ] Correct draw count (= played last round, max 7)
- [ ] Hand can exceed 7 cards
- [ ] Discard works (cards to encounter discard pile)
- [ ] Special cards appear in draws

---

### Phase v3.5 — Loot Box

**Goal:** Loot box replaces round pot. Cards drop on step loss.

**Build:**
- Loot box UI component (right side of board)
- Loot drop logic: non-consumable cards to loot box on step loss
- Remove mandatory ante system
- Round winner claims loot box at cleanup
- Loot box jackpot (5+ cards) triggers vault draw

**Test gate:**
- [ ] No mandatory ante
- [ ] Non-consumable cards go to loot box when step lost
- [ ] Consumables consumed normally (not to loot box)
- [ ] Round winner claims loot box
- [ ] Loot box visible to both players
- [ ] 5+ cards in loot box triggers vault draw

---

### Phase v3.6 — CSS Migration & Tablet UX

**Goal:** Tailwind migration, responsive tablet layout.

**Build:**
- Migrate layout CSS to Tailwind utility classes
- Keep `cardGame.css` for game-specific styles only
- Responsive card sizing with CSS custom properties
- Tablet layout (768-1199px) optimization
- All touch targets ≥ 44×44px
- Padding/margin reduction for tablet

**Test gate:**
- [ ] Tailwind classes used for layout/spacing/typography
- [ ] `cardGame.css` reduced to game-specific styles
- [ ] Layout works at 1024×768 (iPad landscape)
- [ ] All interactive elements have ≥ 44px touch targets
- [ ] Cards render consistently at all viewport sizes
- [ ] Game playable on tablet (end-to-end play test)

---

### Phase v3.7 — Chat System Refactor

**Goal:** All LLM interactions use AM7 chat config policies.

**Build:**
- Create chat config policy JSON files for Talk, Narrator, Director
- Refactor `gameChatManager.js` to use `am7chat` system
- Refactor `narrator.js` to use chat config policy
- Refactor `director.js` to use chat config policy
- Theme-specific chat configs via `aiConfigs.chatConfigs`

**Test gate:**
- [ ] Talk card chat uses chat config policy (not direct WebSocket)
- [ ] Narrator uses chat config policy
- [ ] AI director uses chat config policy
- [ ] Chat sessions managed by AM7 chat system
- [ ] Theme-specific chat configs work

---

### Phase v3.8 — Comprehensive Test Suite

**Goal:** All cardGame UX tests updated for v3. 100% gameplay coverage.

**Build:**
- Update `testMode.js` for all 12 test categories from [Test Plan](#test-plan)
- Automated test sequences for full game rounds
- Equipment test suite (equip via action, stack navigation)
- Loot box test suite
- Draw mechanics test suite
- Tablet-specific test suite (viewport emulation)
- Print export test suite

**Test gate:**
- [ ] All 12 test categories pass
- [ ] Automated full-round test sequence completes
- [ ] Tests cover all v3-specific mechanics (5-spot, 3/2 split, loot box, draw=play, equip action)
- [ ] Tests runnable on tablet viewport

---

### Phase v3.9 — Code Simplification & Cleanup

**Goal:** Apply proposed refactors from requirement 12.

**Build:**
- Consolidate small modules (d20Dice → cardComponents, etc.)
- Remove health/energy denomination card system (optional — confirm with user)
- Externalize action definitions to JSON
- Clean up unused code from v2 mechanics (AP, EQUIP phase, pot ante)
- Final code audit

**Test gate:**
- [ ] Module count reduced (target: 22-24 from 29)
- [ ] No dead code from removed v2 features
- [ ] All tests still pass after cleanup
- [ ] Code review: no duplicate functions >10 lines

---

## Known Issues & Technical Debt

All known open issues across v2, v3 design, and the current codebase. Organized by category with severity and status.

### Legend

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | Blocks gameplay or causes data loss |
| **HIGH** | Significant feature gap or incorrect behavior |
| **MEDIUM** | Quality/polish issue, workaround exists |
| **LOW** | Minor, cosmetic, or nice-to-have |

---

### A. Open Design Questions (v3)

| # | Issue | Severity | Status |
|---|-------|----------|--------|
| A-1 | **Portrait alignment:** Should character portraits align to top of image area (not centered)? Shows more face/upper body. | LOW | Open — optional, not yet confirmed |
| A-2 | **Denomination cards removal:** Should health/energy denomination cards be removed in v3? Numeric tracking works online; IRL players can use dice/dials. Removes complexity from deck and print. | MEDIUM | Open — proposed in refactor plan, needs confirmation |
| A-3 | **Special card balance:** Proposed special cards (Lucky Charm, Mirror Shield, etc.) need playtesting. 15% mix ratio may need adjustment. | MEDIUM | Open — requires playtesting |
| A-4 | **Loot box scope:** Loot box replaces the round pot, but the pot also received items from steals, destroyed equipment, and consumed items. Should loot box receive these too, or do they go elsewhere? | HIGH | Open — design decision needed |
| A-5 | **Initiative advantage compensation:** 3 vs 2 spots gives initiative winner 60% of actions. Should loser get a compensating mechanic? (e.g., +2 defense rolls, +1 draw next round) | MEDIUM | Open — design decision needed |

---

### B. Carried from v2 — Still Open

| # | Issue | Severity | Status | Source |
|---|-------|----------|--------|--------|
| B-1 | **Deck delete — server recursive deletion unverified.** `page.deleteObject("auth.group", grp.objectId)` should recursively delete children (Art/, saves/, campaign/, gameConfig/). If not, children are orphaned. Chat cleanup is resolved (`deleteDeck()` calls `deleteGameChats()`), but group-level recursive delete needs server-side verification. | HIGH | Open — needs backend testing | v2 Issue #1 (line 7601) |
| B-2 | **Backend model list deserialization error.** Jackson fails on `SWModelListResponse["files"]` — expects `ArrayList<Object>` but SD Forge/SwarmUI returns complex nested objects. Fix: update `SWModelListResponse.files` to use typed model or `JsonNode`. | HIGH | Open — backend fix needed | v2 Issue #2 (line 7606) |
| B-3 | **Multiplayer (IRL) not implemented.** Design supports 3-4 player round-robin with directional combat, per-player encounters. IRL-only, requires print support. | LOW | Open — long-term feature | v2 Issue #10 (line 7637) |
| B-4 | **Balance tuning incomplete.** Magic energy costs, threat difficulty scaling, status effect stacking rules need balancing passes. | MEDIUM | Open — ongoing | v2 Next Steps (line 7655) |
| B-5 | **Print & export not implemented.** PDF generation, PNG export, TTS format, rules reference cards. Planned for v3 Phase v3.7. | HIGH | Open — Phase v3.7 | v2 Next Steps (line 7662) |
| B-6 | **Multiplayer rules documentation.** IRL play reference cards for 3-4 player mode not written. | LOW | Open — blocked by B-3 | v2 Next Steps (line 7663) |

---

### C. Codebase — Known Bugs & Incomplete Features

| # | Issue | Severity | File(s) | Details |
|---|-------|----------|---------|---------|
| C-1 | **`navigateBack` not wired.** Theme editor back-navigation is a stub: `console.warn("[CardGame] navigateBack not wired")`. | MEDIUM | `services/themes.js:677` | Callback never connected to actual navigation |
| C-2 | **Narration timeout fallback.** If LLM narration doesn't respond in time, initiative starts anyway. May cause desync between narration text and game state. | MEDIUM | `ui/phaseUI.js:32` | `"Narration ready timeout — starting initiative anyway"` |
| C-3 | **Auto-save can be skipped.** If storage is unavailable, auto-save is silently skipped. Player could lose progress without knowing. | HIGH | `ui/phaseUI.js:764-766` | No user-visible notification when save fails |
| C-4 | **No character card in deck.** If deck assembly produces no character card, game cannot start. Error logged but no graceful UI recovery. | CRITICAL | `state/gameState.js:142`, `ui/gameView.js:227` | Should show user-facing error instead of console-only |
| C-5 | **Unknown status effect.** `effects.js` warns on unrecognized effect IDs but does not prevent them from being applied. | MEDIUM | `engine/effects.js:26` | Could produce undefined behavior in combat resolution |
| C-6 | **Gallery load failure.** Image gallery overlay can fail silently if deck art directory is inaccessible. | LOW | `rendering/overlays.js:195,212` | Art browsing broken but gameplay unaffected |
| C-7 | **Action card placement — duplicate core card.** `actions.js` blocks duplicate core cards in a stack but logs error rather than showing user feedback. | MEDIUM | `engine/actions.js:298` | User sees nothing when placement is rejected |
| C-8 | **Voice profile resolution failures.** Multiple failure paths when voice profiles can't be found or loaded. Voice features silently disabled. | LOW | `state/gameState.js:508,529,535,584,602` | Voice is optional; graceful degradation works |
| C-9 | **Campaign stat gain application failure.** Applying saved campaign stat gains can throw, caught but gains are lost. | MEDIUM | `state/gameState.js:452` | Player levels up but stats don't apply on next game |
| C-10 | **Deck rename failure.** Group rename can fail with no user-visible feedback. | MEDIUM | `ui/deckList.js:147` | Deck appears renamed in UI but server state unchanged |

---

### D. Codebase — External Config Fallbacks

These are not bugs per se — the system falls back to hardcoded defaults when JSON configs are missing. However, this means the externalized config system is partially broken. These should either be fixed (configs created/loaded properly) or the fallback should be made the only path (remove the config load attempt).

| # | Config File | Fallback | File(s) |
|---|-------------|----------|---------|
| D-1 | `action-definitions.json` | Hardcoded action definitions in `gameConstants.js` | `constants/gameConstants.js:466` |
| D-2 | `game-balance.json` | Hardcoded balance values in `encounters.js` | `engine/encounters.js:24` |
| D-3 | `encounters.json` | Hardcoded encounter tables in `encounters.js` | `engine/encounters.js:67` |
| D-4 | `art-prompts.json` | Hardcoded SD prompts in `artPipeline.js` | `services/artPipeline.js:115` |
| D-5 | Narrator prompts | Hardcoded prompts in `narrator.js` | `ai/narrator.js:32` |
| D-6 | Director prompts | Hardcoded prompts in `director.js` | `ai/director.js:55` |
| D-7 | Chat prompts | Hardcoded prompts in `chatManager.js` | `ai/chatManager.js:32` |
| D-8 | Voice profiles | Voice features disabled | `services/artPipeline.js:271` |

---

### E. Codebase — Storage & Persistence Error Paths

The storage module (`state/storage.js`) has 14 error/warning paths covering deck, game save, and campaign persistence. All are caught and logged, but several lack user-visible feedback.

| # | Operation | Error | User Feedback? | Line(s) |
|---|-----------|-------|---------------|---------|
| E-1 | Create group | Group creation fails | No | `storage.js:18` |
| E-2 | Load data record | Group not found | No | `storage.js:48` |
| E-3 | Load data record | Record found but `dataBytesStore` empty | No | `storage.js:63` |
| E-4 | Save deck | Save fails | No | `storage.js:89` |
| E-5 | Load deck | Load fails | No | `storage.js:98` |
| E-6 | List decks | List fails | No | `storage.js:113` |
| E-7 | Remove deck | Remove fails | No | `storage.js:126` |
| E-8 | Save game | Group creation fails | No | `storage.js:170` |
| E-9 | Save game | Save fails | No | `storage.js:195` |
| E-10 | Load game save | Load fails | No | `storage.js:208` |
| E-11 | List saves | List fails | No | `storage.js:226` |
| E-12 | Delete saves | Delete fails | No | `storage.js:239` |
| E-13 | Save cleanup | Cleanup fails | No | `storage.js:250` |
| E-14 | Save campaign | Save fails | No | `storage.js:292` |
| E-15 | Load campaign | Load fails | No | `storage.js:301` |

---

### F. Codebase — AI/LLM Integration Robustness

| # | Issue | Severity | File(s) |
|---|-------|----------|---------|
| F-1 | **LLMConnector unavailable.** All LLM features (narration, chat, director, voice) disabled when connector missing. No user-facing indicator that AI features are off. | HIGH | `ai/llmBase.js:79` |
| F-2 | **LLM initialization failure.** `initializeLLM` can fail; caught but AI features silently broken for entire session. | HIGH | `ai/llmBase.js:125` |
| F-3 | **Chat folder creation failure.** `~/CardGame/Chats` folder can't be created; chat history won't persist. | MEDIUM | `ai/llmBase.js:36` |
| F-4 | **Director parse/retry loop.** Director LLM response parsing can fail; retries once then falls back. Fallback placement quality unknown. | MEDIUM | `ai/director.js:155,237,246,249` |
| F-5 | **Director placement failure.** AI card placement request can fail entirely; falls back to basic auto-placement. | MEDIUM | `ai/director.js:171,374` |
| F-6 | **Narrator failure.** LLM narration calls can fail; game continues without narration. | LOW | `ai/narrator.js:143` |
| F-7 | **Chat message failure.** Individual chat messages can fail to send. | LOW | `ai/chatManager.js:206` |
| F-8 | **Chat history seeding failure.** Server chat history can't be loaded; chat starts fresh. | LOW | `ai/chatManager.js:159` |
| F-9 | **Banter generation failure.** NPC banter generation can fail; banter skipped. | LOW | `ai/chatManager.js:246`, `state/gameState.js:983` |

---

### G. Codebase — Art Pipeline Error Paths

| # | Issue | Severity | File(s) |
|---|-------|----------|---------|
| G-1 | **Background generation failure.** SD image generation for game background can fail. | MEDIUM | `services/artPipeline.js:410` |
| G-2 | **Tabletop generation failure.** SD image generation for tabletop texture can fail. | MEDIUM | `services/artPipeline.js:483` |
| G-3 | **Template art failure.** Card template art generation can fail. | MEDIUM | `services/artPipeline.js:727` |
| G-4 | **Card art generation failure.** Individual card art generation can fail. | HIGH | `services/artPipeline.js:829` |
| G-5 | **Image sequence failure.** Batch image generation pipeline can fail. | MEDIUM | `services/artPipeline.js:1190` |
| G-6 | **Incremental save failure.** Progress save during art generation can fail; generation continues but progress lost on crash. | LOW | `services/artPipeline.js:823` |
| G-7 | **Batch sequence failure.** Per-card batch sequence can fail. | MEDIUM | `services/artPipeline.js:1233` |

---

### H. Codebase — Character & Theme Service Issues

| # | Issue | Severity | File(s) |
|---|-------|----------|---------|
| H-1 | **Statistics resolution failure.** Character stat lookup can fail. | MEDIUM | `services/characters.js:94` |
| H-2 | **Deck character loading failure.** Loading characters from saved deck can fail. | HIGH | `services/characters.js:502,528` |
| H-3 | **Character template loading failure.** Template list from server can fail. | HIGH | `services/characters.js:546` |
| H-4 | **Character roll failure.** Rolling a new character from template can fail. | MEDIUM | `services/characters.js:577` |
| H-5 | **Character generation failure.** Full character creation from template can fail. | MEDIUM | `services/characters.js:624` |
| H-6 | **Character folder creation failure.** Deck's Characters subfolder can't be created. | HIGH | `services/characters.js:643` |
| H-7 | **Character persistence failure.** Saving character back to server can fail. | HIGH | `services/characters.js:693` |
| H-8 | **Theme config not found.** Theme ID lookup returns nothing; falls back to default. | MEDIUM | `services/themes.js:45` |
| H-9 | **Color library missing.** `/Library/Colors` group not found on server. | LOW | `services/themes.js:76` |
| H-10 | **Apparel creation failure.** Generating apparel cards for character can fail. | MEDIUM | `services/themes.js:225` |
| H-11 | **Character outfit failure.** Full outfit assembly for character can fail. | MEDIUM | `services/themes.js:281` |
| H-12 | **Theme save failure.** Saving theme config to server can fail. | MEDIUM | `services/themes.js:309` |
| H-13 | **Theme remove failure.** Deleting theme from server can fail. | MEDIUM | `services/themes.js:340` |

---

### Issue Counts by Severity

| Severity | Count |
|----------|-------|
| CRITICAL | 1 |
| HIGH | 14 |
| MEDIUM | 28 |
| LOW | 11 |
| **Total** | **54** |

### Issue Counts by Category

| Category | Count | Description |
|----------|-------|-------------|
| A — Design Questions | 5 | v3 design decisions pending |
| B — Carried from v2 | 6 | Open v2 issues still unresolved |
| C — Bugs & Incomplete | 10 | Codebase bugs and missing features |
| D — Config Fallbacks | 8 | External JSON configs not loading |
| E — Storage Errors | 15 | Persistence error paths with no user feedback |
| F — AI/LLM Robustness | 9 | LLM integration failure modes |
| G — Art Pipeline Errors | 7 | Image generation failure modes |
| H — Character/Theme | 13 | Character and theme service failures |

---

*End of Card Game v3 specification. For unchanged mechanics, see [cardGame-v2.md](cardGame-v2.md).*
