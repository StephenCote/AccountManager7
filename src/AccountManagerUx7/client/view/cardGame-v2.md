# Card Game v2 — Card-Based RPG

A web-based card RPG playable online (1v1 vs AI, expandable to free-for-all multiplayer) and printable for real-life tabletop play. Built on the AM7 backend with LLM-driven outcomes online and dice + card modifiers for RL.

---

## Table of Contents

1. [Design Philosophy](#design-philosophy)
2. [Card Types](#card-types)
3. [Card Anatomy](#card-anatomy)
4. [Deck Composition](#deck-composition)
5. [Round Structure](#round-structure) — initiative, AP, placement, pot, resolution, cleanup
6. [Character Stack & Action Stacks](#character-stack--action-stacks)
7. [Mid-Round Disruption](#mid-round-disruption) — insert, remove, modify
8. [Combat Resolution](#combat-resolution) — per-position interleaved resolution
9. [Talk / Chat Action Card](#talk--chat-action-card)
10. [Magic System](#magic-system)
11. [Needs & Survival](#needs--survival)
12. [AI Modes](#ai-modes) — opponent, GM, AI stack selection protocol
13. [Card Arrangement UX](#card-arrangement-ux) — drag-and-drop, smart assist
14. [Poker Face](#poker-face-online-only) — MoodRing emotion banter (online only)
15. [Live Narration System](#live-narration-system-online-only)
16. [Image Generation Pipeline](#image-generation-pipeline)
17. [Print Specifications](#print-specifications)
18. [Deck Theme Configuration](#deck-theme-configuration)
19. [Deck Builder & Snapshot Architecture](#deck-builder--snapshot-architecture)
20. [Online Implementation](#online-implementation)
21. [Rules Quick Reference](#rules-quick-reference)
22. [Rules Documentation](#rules-documentation-help-content) — markdown help content for online and IRL play
23. [v1 Migration](#v1-migration) — archive cardGame.js v1, rename v2 to "Card Game"
24. [Open Design Questions](#open-design-questions)

**Companion files:**
- [cardGame-v2-themes.md](cardGame-v2-themes.md) — Complete card pool definitions for High Fantasy, Sci-Fi, and Post Apocalyptic themes

---

## Design Philosophy

### Core Principles

1. **Cards are the interface.** Every game element — characters, items, actions, encounters — is a card. No map, no board. The world comes to you through drawn cards.
2. **Stacks are sentences.** A single card is a noun. A stack is a verb phrase: "Character + Weapon + Action = I attack." Stacking is the primary mechanic.
3. **Use it or lose it.** Consumable cards played in a round are spent whether their effect triggered or not. This forces commitment and risk assessment every round.
4. **Do it or don't.** Each round, you build stacks and commit them. No take-backs. Simultaneous reveal prevents reactive play.
5. **Same rules, two surfaces.** Online and RL play follow identical rules. Online uses LLM for outcome narration and AI opponent behavior; RL uses dice + card modifiers. The math is the same.
6. **Print-first card design.** Cards are designed to be physically printed and playable. Online rendering mirrors the physical layout exactly.

### Simplifications from RPG.md

| RPG.md Feature | v2 Card Approach |
|---------------|-----------------|
| MGRS spatial grid, cell navigation | Eliminated — no map, encounters drawn from deck |
| 16 base statistics | Reduced to 6 core stats on the character card |
| 2d20 resolution with personality/instinct modifiers | Simplified to 1d20 + stack modifier vs target number or opposed roll (d20 maps directly to 1-20 stat range) |
| Maslow hierarchy (5 levels, 30+ individual needs) | 4 need tracks on the character card (Health, Energy, Hunger, Morale) |
| Skill proficiency 0-100% with decay formula | Skill cards with flat modifier values; decay = discard oldest skill card between sessions |
| Seal-based magic with reagents and channeling | Three skill types (Imperial, Undead, Psionic) + Magic Effect cards |
| Real-time Overwatch simulation | Turn-based rounds with simultaneous stack commitment |
| Fog of war, intra-cell sub-grid | Encounter deck replaces exploration |
| Party formation, reputation ledger | Alliance cards, reputation tracked as card count |

---

## Card Types

There are 8 card types, each with a distinct card back color/design.

| Type | Back Color | Purpose | Persistence |
|------|-----------|---------|-------------|
| **Character** | Gold | Your persona — stats, portrait, needs | Persistent (never discarded) |
| **Apparel** | Silver | Armor, clothing — defensive modifiers | Equipped until destroyed or replaced |
| **Item** | Green | Weapons, tools, consumables | Weapons persist; consumables are use-or-lose |
| **Action** | Red | What you do this round (Attack, Defend, Flee, etc.) | Played and returned to hand after round |
| **Talk** | Blue | Initiate conversation / negotiate | Played and returned after round |
| **Encounter** | Purple | Threats, events, discoveries — drawn from shared deck | Resolved and discarded |
| **Skill** | Orange | Learned abilities that modify actions | Persist until decay (campaign mode) |
| **Magic Effect** | Teal | Spell effects requiring skill type + stat threshold | Consumable or reusable (per card) |

### Card Back Designs

Each type has a unique card back with:
- Type icon centered (sword for Item, shield for Apparel, etc.)
- Type name at bottom
- Consistent border pattern per type
- Color-coded for quick identification in hand

The **Character** card is the only type where the back is purely decorative (gold filigree pattern). All card info is on the front since the character card sits face-up in front of the player at all times.

---

## Card Anatomy

### Card Face Corner Icons

Every card face has a **small type icon** in the top-left and bottom-right corners (like suit pips on playing cards). This allows quick identification when cards are fanned, stacked, or partially overlapping. The icon is theme-styled — generated as part of the deck assets — and tinted to match the card type's accent color.

```
+-------------------------------+
| [⚔]                    [⚔] |     ← type icon (top-left) + mirror (top-right)
|  ...card content...           |
|                         [⚔] |     ← type icon (bottom-right, rotated 180°)
+-------------------------------+
```

| Card Type | Icon Symbol | Default Color |
|-----------|-------------|---------------|
| Character | Shield crest | Gold |
| Apparel | Helmet/armor | Silver |
| Item (Weapon) | Crossed swords | Red |
| Item (Consumable) | Potion flask | Green |
| Action | Lightning bolt | Red-orange |
| Talk | Speech bubble | Blue |
| Encounter | Portal/mist | Purple |
| Skill | Star/compass | Orange |
| Magic Effect | Arcane circle | Teal |

Corner icons are rendered at **24×24px** on screen (scaled for print). They use the themed `cardTypeCornerIcons` from deck assets, falling back to simple SVG glyphs if deck assets haven't generated yet.

### Character Card (Front — Full Info, Back — Gold Type Backing)

```
+---------------------------------------+
| [🛡]                           [🛡] |
| [Portrait Image]           [Race Icon] |
| ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ |
| CHARACTER NAME              Level  N  |
| Race / Alignment                      |
|---------------------------------------|
| STR [##] | AGI [##] | END [##]       |
| INT [##] | MAG [##] | CHA [##]       |
|---------------------------------------|
| HP  [████████░░] 80/100              |
| NRG [██████░░░░] 60/100              |
| HNG [████░░░░░░] 40/100              |
| MRL [██████████] 100/100             |
|---------------------------------------|
| Skill Slots: [_] [_] [_] [_]         |
| Equip Slots: [Head] [Body] [Hand×2]  |
|               [Feet] [Ring] [Back]    |
|                                 [🛡] |
+---------------------------------------+
```

**Stats (6 core):**

| Stat | Abbrev | Governs |
|------|--------|---------|
| Strength | STR | Melee damage, carry capacity |
| Agility | AGI | Dodge, initiative, ranged accuracy |
| Endurance | END | HP total, resist effects, stamina |
| Intelligence | INT | Undead magic, crafting, skill learning |
| Magic | MAG | Imperial magic, spell power |
| Charisma | CHA | Talk outcomes, trade prices, morale |

Each stat is 1-20. The stat value **is** the modifier added to d20 rolls — a direct comparison against the 20-based scale. Roll 1d20 + stat modifier + card bonuses vs opposing roll. This keeps math simple: your stat value is exactly how much you add.

**Need Tracks (4):**

| Track | Depleted By | Restored By |
|-------|------------|-------------|
| Health (HP) | Combat damage, hazards | Healing items, rest, Life magic |
| Energy (NRG) | Actions, magic casting, movement | Rest, food, stimulants |
| Hunger (HNG) | Every 3 rounds automatically | Food item cards |
| Morale (MRL) | Failed actions, ally defeat, isolation | Successful Talk, ally victory, rest |

All tracks are 0-100. At 0 HP = defeated. At 0 Energy = can only play Defend or Talk. At 0 Hunger = lose 10 HP per round. At 0 Morale = cannot play Talk or cooperative actions.

**Equip Slots:**

| Slot | Accepts |
|------|---------|
| Head | Helmet, hat, circlet |
| Body | Armor, robe, vest |
| Hand (×2) | Weapon, shield, tool, staff (two-handed items take both) |
| Feet | Boots, sandals |
| Ring | Ring items (stat bonuses) |
| Back | Cloak, pack, wings |

### Apparel Card

```
+-------------------------------+
| [🛡]                    [🛡] |
| [Item Image]                  |
| APPAREL NAME                  |
| Slot: Body     Rarity: ★★★  |
|-------------------------------|
| DEF +3  | HP +10              |
| Special: Resist Fire (halve   |
|   fire magic damage)          |
|-------------------------------|
| "Forged in the deep..."      |
| [Type icon]  [Durability: 5] |
|                         [🛡] |
+-------------------------------+
```

**Fields:**
- **Slot**: Which equip slot it occupies
- **DEF**: Defense modifier added to opposed rolls when defending
- **HP bonus**: Added to max HP while equipped
- **Special**: Optional passive effect
- **Durability**: Number of hits before destroyed (reduced by 1 each time wearer takes damage). 0 = card discarded.
- **Rarity**: ★ Common, ★★ Uncommon, ★★★ Rare, ★★★★ Epic, ★★★★★ Legendary

### Item Card (Weapon)

```
+-------------------------------+
| [⚔]                    [⚔] |
| [Weapon Image]                |
| WEAPON NAME                   |
| Slot: Hand    Rarity: ★★    |
|-------------------------------|
| ATK +4  | Range: Melee       |
| Type: Slashing                |
| Requires: STR 8              |
|-------------------------------|
| Special: Cleave (on kill,     |
|   deal 2 dmg to adjacent foe)|
|-------------------------------|
| [Type icon]  [Durability: 8] |
|                         [⚔] |
+-------------------------------+
```

**Fields:**
- **ATK**: Attack modifier added to attack action rolls
- **Range**: Melee or Ranged (Ranged weapons can attack without being in the same encounter)
- **Type**: Slashing, Piercing, Blunt, Ranged (matters for armor interactions)
- **Requires**: Minimum stat to equip
- **Special**: Optional active or passive effect
- **Durability**: Reduced by 1 each time used in attack. 0 = broken, discarded.

### Item Card (Consumable)

```
+-------------------------------+
| [🧪]                    [🧪] |
| [Item Image]                  |
| HEALTH POTION                 |
| Consumable       Rarity: ★  |
|-------------------------------|
| Effect: Restore 30 HP        |
|                               |
| USE IT OR LOSE IT             |
| If played in a round, this   |
| card is consumed whether the  |
| effect was needed or not.     |
|-------------------------------|
| [Type icon]              [🧪] |
+-------------------------------+
```

**Key rule**: Consumables are committed at round start. A health potion played preemptively restores HP *and* is discarded — even if you took no damage that round. This is the core tension of consumable play.

### Action Card

```
+-------------------------------+
| [⚡]                    [⚡] |
| [Action Icon]                 |
| ATTACK                        |
| Action Type: Offensive        |
|-------------------------------|
| Stack with: Character +       |
|   Weapon (required) +         |
|   Skill (optional)            |
|                               |
| Roll: 1d20 + ATK + STR       |
|   vs target's 1d20 + DEF+END |
|                               |
| On hit: Deal weapon ATK value |
|   + STR modifier as damage    |
|-------------------------------|
| Cost: 10 Energy               |
| [Type icon]              [⚡] |
+-------------------------------+
```

**Action Types:**

| Action | Type | Stack Requirements | Roll Formula | Energy Cost |
|--------|------|--------------------|-------------|-------------|
| **Attack** | Offensive | Character + Weapon (req) + Skill (opt) | 1d20 + STR + ATK bonus vs target DEF roll | 10 |
| **Defend** | Defensive | Character + Apparel (opt) + Skill (opt) | 1d20 + END + DEF bonus (sets defense for round) | 5 |
| **Flee** | Movement | Character only | 1d20 + AGI vs encounter difficulty | 5 |
| **Investigate** | Discovery | Character + Skill (opt) | 1d20 + INT vs encounter hidden threshold | 5 |
| **Trade** | Social | Character + Item(s) to offer | CHA determines price modifier | 0 |
| **Rest** | Recovery | Character only (no other actions) | Auto-success: restore 20 NRG, 10 HP | 0 |
| **Use Item** | Utility | Character + Consumable item | Auto-success: apply item effect | 0 |
| **Craft** | Creation | Character + Material items + Skill | 1d20 + INT vs recipe difficulty | 15 |

### Talk Card

```
+-------------------------------+
| [💬]                    [💬] |
| [Chat Bubble Icon]            |
| TALK                          |
| Action Type: Social           |
|-------------------------------|
| ONLINE: Opens LLM chat with   |
|   target. Outcome determined  |
|   by conversation quality +   |
|   CHA modifier.               |
|                               |
| RL: You may speak to the      |
|   target player/GM. Negotiate,|
|   bluff, persuade. Outcome    |
|   roll: 1d20 + CHA mod vs     |
|   target's 1d20 + CHA mod.    |
|                               |
| WITHOUT THIS CARD:            |
|   No communication allowed.   |
|-------------------------------|
| Cost: 5 Energy                |
| [Type icon]              [💬] |
+-------------------------------+
```

**Critical rule**: Players may NOT speak to each other about game strategy or negotiate deals unless they play a Talk card. In RL, this means actual silence between players until a Talk card is played. Online, the chat interface is locked unless a Talk card is active.

### Encounter Card

```
+-------------------------------+
| [🌀]                    [🌀] |
| [Encounter Image]             |
| WOLF PACK                     |
| Encounter: Threat             |
|-------------------------------|
| Difficulty: 8                 |
| ATK: +3  | DEF: +2  | HP: 25|
|                               |
| Behavior: Attacks lowest HP   |
|   character first.            |
|                               |
| Loot on defeat:               |
|   - Wolf Pelt (Apparel mat.)  |
|   - Raw Meat (Consumable)     |
|-------------------------------|
| [Type icon]              [🌀] |
+-------------------------------+
```

**Encounter subtypes:**

| Subtype | What Happens |
|---------|-------------|
| **Threat** | Must fight, flee, or talk. Has ATK/DEF/HP stats. |
| **Event** | Environmental effect: storm (-5 to all rolls this round), found campsite (free Rest), etc. |
| **Discovery** | Loot/resource find. Investigate action may reveal bonus items. |
| **NPC** | Non-hostile character. Can Talk, Trade, or ignore. May offer quests (scenario mode). |

### Skill Card

```
+-------------------------------+
| [⭐]                    [⭐] |
| [Skill Icon]                  |
| SWORDSMANSHIP                 |
| Skill: Combat                 |
|-------------------------------|
| Modifier: +2 to Attack rolls  |
|   when using a Slashing weapon|
|                               |
| Requires: STR 10              |
|                               |
| Category: COMBAT              |
| Tier: MEDIEVAL                |
|-------------------------------|
| [Type icon]  [Uses: ∞]  [⭐] |
+-------------------------------+
```

**Skill categories** (from RPG.md, simplified):

| Category | Modifies |
|----------|----------|
| COMBAT | Attack rolls with melee weapons |
| RANGED | Attack rolls with ranged weapons |
| DEFENSE | Defend rolls |
| SURVIVAL | Investigate rolls, need management |
| CRAFTING | Craft action success |
| SOCIAL | Talk rolls, Trade prices |
| STEALTH | Flee rolls, ambush attacks |
| KNOWLEDGE | Investigate rolls, magic effect identification |
| MOVEMENT | Flee rolls, initiative |

Skills occupy **skill slots** on the character card (max 4 active skills). Swapping requires a full round with no other actions.

### Magic Effect Card

```
+-------------------------------+
| [🔮]                    [🔮] |
| [Spell Effect Image]          |
| FIREBALL                      |
| Magic Effect: Offensive       |
|-------------------------------|
| Skill Type: Imperial          |
| Requires: MAG 12              |
|                               |
| Effect: Deal 15 fire damage   |
|   to target. Ignores 2 DEF.  |
|                               |
| Stack with: Character +       |
|   Action (Attack) +           |
|   [Imperial skill card]       |
|-------------------------------|
| Cost: 20 Energy               |
| Reusable: Yes                 |
| [Type icon]              [🔮] |
+-------------------------------+
```

See [Magic System](#magic-system) for full details.

---

## Deck Composition

### Starter Deck (Per Player)

Generated from the player's AM7 character data. Each player begins with:

| Category | Cards | Source |
|----------|-------|--------|
| Character | 1 | Generated from `olio.charPerson` (stats, portrait, needs) |
| Starting Apparel | 2-3 | From character's `olio.apparel` / `olio.wearable` equipment |
| Starting Weapons | 1-2 | From character's `olio.store` inventory |
| Starting Consumables | 3-5 | Basic supplies: 2 rations, 1 health potion, 1 bandage, 1 torch |
| Action Cards | 1 of each type | Full set: Attack, Defend, Flee, Investigate, Trade, Rest, Use Item, Craft |
| Talk Card | 1 | Always included |
| Starting Skills | 1-2 | Derived from character's highest proficiency `data.trait` records |
| Magic Effects | 0-1 | Only if character has MAG ≥ 10 or INT ≥ 12 or relevant skill type |

**Total starter deck**: ~15-20 cards

### Shared Encounter Deck

A communal deck drawn from during play:

| Subtype | Count (2-player) | Notes |
|---------|-----------------|-------|
| Threat encounters | 15 | Scaled to character levels |
| Event encounters | 10 | Weather, terrain effects, time-of-day |
| Discovery encounters | 10 | Loot finds, resource caches |
| NPC encounters | 8 | Traders, travelers, quest-givers |
| Item cards (loot) | 12 | Weapons, apparel, consumables mixed in |
| Skill cards (learnable) | 6 | Found or earned through encounters |
| Magic Effect cards | 4 | Rare, found through Discovery or NPC |

**Total shared deck**: ~65 cards for 2 players. Add ~20 per additional player.

### Deck Building (Between Sessions / Campaign Mode)

Between game sessions in campaign play:
- Keep all persistent cards (Character, equipped Apparel/Weapons, Skills, reusable Magic Effects)
- Discard all consumables (they don't keep between sessions)
- May swap up to 3 cards from a "collection" (cards earned in previous sessions but not in current deck)
- Character stat changes from leveling persist on the character card

---

## Round Structure

### Round Flow

Each game round follows this sequence:

```
1. DRAW PHASE
   └─ Active player draws 1 card from encounter deck
      (or 2 if Energy < 30 — desperation draw)
   └─ Reveal drawn encounter card
   └─ If Threat: must be addressed this round (fight, flee, or talk)
   └─ If Event: effect applies immediately
   └─ If Discovery/NPC: optional interaction

2. INITIATIVE PHASE
   └─ All players roll 1d20 + AGI
   └─ Highest total wins initiative (ties: re-roll, or highest raw AGI wins)
   └─ Initiative winner fills ODD action bar positions (1, 3, 5, 7...)
   └─ Other player(s) fill EVEN positions (2, 4, 6...)
   └─ Encounter threats (if any) also receive AP and fill positions
   └─ CRITICAL INITIATIVE: Nat 1 on initiative roll triggers a Per-Round Threat
      at the BEGINNING of the action bar (see Per-Round Threats)

3. PLACEMENT PHASE (simultaneous, open)
   └─ All players simultaneously build and place action stacks
      onto the shared Action Bar — stacks are OPEN (face-up)
   └─ Number of stacks limited by Action Points (AP = floor(END / 5) + 1)
   └─ Each stack also costs Energy per its action type — AP and Energy are separate limits
   └─ Consumables committed here are LOCKED IN (use-or-lose)
   └─ Players can rearrange their stacks until all players confirm "Ready"
   └─ Character Stack (charPerson + equipped gear) is always active on the sidebar —
      its modifiers apply to every action stack automatically

4. RESOLUTION PHASE (left to right, interleaved)
   └─ Resolve action bar position by position: 1, 2, 3, 4...
   └─ Each position resolves fully before the next begins
   └─ Players alternate — position 1 (initiative winner), position 2 (other), etc.
   └─ Mid-round disruptions may INSERT new stacks or REMOVE upcoming stacks
   └─ Online: each position narrated by LLM, dice rolled server-side
   └─ RL: dice rolls + card modifier math per position

5. CLEANUP PHASE
   └─ Discard consumed items (core cards of action stacks that were consumable)
   └─ Return non-consumed Action cards and modifier cards to hand
   └─ Reduce durability on used equipment
   └─ Hunger tick: -10 Hunger every 3rd round
   └─ Check defeat conditions (0 HP = eliminated)
   └─ Unresolved threats carry to next round (escalate: +2 ATK)
```

### Action Points (AP)

AP determines how many action stacks a player can place on the Action Bar per round. Derived from the Endurance stat:

**Formula:** `AP = floor(END / 5) + 1`

| END Range | AP | Effect |
|-----------|-----|--------|
| 1–4       | 1   | 1 action stack per round |
| 5–9       | 2   | 2 action stacks per round |
| 10–14     | 3   | 3 action stacks per round |
| 15–19     | 4   | 4 action stacks per round |
| 20        | 5   | 5 action stacks per round |

**AP vs Energy:** AP is the hard cap on stack count. Energy is the resource cost per action. A player with 4 AP but only 15 Energy remaining may only be able to afford 2 actions (if each costs 10 Energy). Unused AP is not banked — use it or lose it each round.

**Encounter AP:** Encounter threats also get AP based on their difficulty tier:

| Difficulty | Encounter AP |
|-----------|-------------|
| 1–4 (Easy) | 1 |
| 5–8 (Medium) | 2 |
| 9–12 (Hard) | 3 |
| 13+ (Boss) | 4 |

Encounter stacks are placed by the system (online: server/AI decides; RL: draw from a behavior card or follow the encounter card's behavior text).

### Initiative & Position Interleaving

**Initiative roll:** Each round, every player rolls `1d20 + AGI`. Highest total wins.

**Position assignment (2-player):**
- Total positions on the Action Bar = Player A's AP + Player B's AP + Encounter AP (if any) + Per-Round Threats (0–3)
- Beginning threats (from critical initiative) are placed **before** position 1
- Initiative winner fills **odd** positions: 1, 3, 5, 7...
- Initiative loser fills **even** positions: 2, 4, 6...
- Encounter threat fills remaining positions (slotted by the system based on behavior)
- End threats (from scenario/action cards) are placed **after** the last player position

**Example (2 players, no encounter):**
```
Player A: END 16 → 4 AP, wins initiative
Player B: END 12 → 3 AP

Action Bar (7 total positions):
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│  1  │  2  │  3  │  4  │  5  │  6  │  7  │
│  A  │  B  │  A  │  B  │  A  │  B  │  A  │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┘
Resolves left to right: A acts, B acts, A acts, B acts, A acts, B acts, A acts
```

**Example (player vs encounter threat):**
```
Player: END 14 → 3 AP, wins initiative
Dire Wolf: Difficulty 8 → 2 AP

Action Bar (5 total positions):
┌─────┬─────┬─────┬─────┬─────┐
│  1  │  2  │  3  │  4  │  5  │
│ Plr │Wolf │ Plr │Wolf │ Plr │
└─────┴─────┴─────┴─────┴─────┘
```

**Multiplayer (3+ players):** Initiative order determines round-robin fill. 1st place fills positions 1, 4, 7...; 2nd fills 2, 5, 8...; 3rd fills 3, 6, 9... and so on.

### Round Timing

- Online: Placement phase has a configurable timer (default 60 seconds). Auto-defend if timer expires.
- RL: No timer enforced, but a 2-minute sand timer is recommended for the placement phase.

### Round Pot

Each round has a shared **pot** — a pool of cards and currency that both players ante into and that grows during the round from lost, stolen, or dropped items. The round winner claims the entire pot at the end of the Cleanup Phase.

**Ante (mandatory):**
At the start of each round (during the Draw Phase), every player must put **at least 1 card** from their hand into the pot. This can be:
- A currency/money card (if the theme includes them)
- Any item card (weapon, apparel, consumable)
- A skill card
- A magic effect card

Action and Talk cards cannot be anted (they are core gameplay cards). Character cards can never be anted.

**Mid-round pot additions:**
During the Resolution Phase, the pot grows from game effects:
- **Stolen items:** A successful steal/disarm action takes a card from the opponent and adds it to the pot
- **Dropped items:** A Critical Counter or fumble may cause the loser to drop an equipped item into the pot
- **Destroyed items:** When equipment hits 0 durability, the broken card goes into the pot (salvage value)
- **Consumed items:** Consumable cards that are used/spent during the round go into the pot (they're gone from both players' hands, but the winner may recover them)
- **Encounter loot:** When an encounter threat is defeated, its loot cards go into the pot (not directly to the player who dealt the killing blow)

**Round winner determination:**
The "round winner" is determined by **net advantage** during the round:
- If a player reduces the opponent's HP more than they lost → that player wins the round
- If equal HP change → the player who completed more successful actions wins
- If still tied → the initiative winner claims the pot
- In GM mode (no opponent): the player always claims the pot if they survive the round

**Claiming the pot:**
During the Cleanup Phase, the round winner takes all cards from the pot into their hand. This includes:
- Usable items and equipment (can be equipped immediately or held)
- Currency/money cards (tracked as wealth)
- Broken items (can be repaired via Craft action in a future round)
- Consumables from the pot are refreshed (a used potion recovered from the pot is usable again)

**Pot UI:**
The pot is displayed as a card pile in the center of the play area, between the two character stacks. Cards in the pot are visible (face-up) so both players can see the stakes.

```
┌──────────────────┐
│    ROUND POT     │
│  ┌───┐ ┌───┐    │
│  │💰│ │🗡️│    │  ← Anted and accumulated cards
│  └───┘ └───┘    │
│  ┌───┐           │
│  │🧪│           │  ← Items added mid-round
│  └───┘           │
│  Value: 3 cards  │
│  Winner: TBD     │
└──────────────────┘
```

**Strategic implications:**
- Players must decide what to ante — a junk card or a valuable one. Anting a powerful item raises the stakes but risks losing it.
- Steal/disarm actions become more valuable because they add opponent's cards to the pot (which you can win back)
- The pot creates a "winner takes all" tension each round, encouraging aggressive play
- In multiplayer, the pot can become very large, making round wins extremely impactful

### Per-Round Threats

A **third play dimension** beyond the two players: independent threats that appear on the action bar during a round. Threats add unpredictability and resource pressure — players must deal with environmental dangers on top of each other.

**Threat triggers:**

| Trigger | Threat Position | Max per Trigger |
|---------|----------------|-----------------|
| **Critical initiative failure** (Nat 1 on initiative roll) | **Beginning** of the action bar (before position 1) | 1 per player (max 2 from initiative) |
| **Scenario / encounter card** | **End** of the action bar (after all player positions) | 1 per card drawn |
| **Action card effect** (e.g., "Summon Beast" backfire, trap card) | **End** of the action bar | 1 per triggering effect |

**Maximum threat positions per round:** 3 (hard cap). This means the action bar's total length is: `Player A AP + Player B AP + threat count (0–3)`.

**Threat composition:**
Each threat is drawn from the encounter deck (or a dedicated threat sub-deck for scenario mode). A threat comes with:
- A threat card (creature/hazard with ATK, DEF, HP, difficulty)
- 1–2 random loot cards attached (drawn from encounter deck alongside the threat)
- The threat card shows its loot face-up, so players can see what's at stake

#### Beginning Threats (Critical Initiative Failure)

When a player rolls a **Natural 1** on their initiative roll, a threat is inserted at the **beginning** of the action bar — before position 1.

```
THREAT at beginning (1 threat from Player A's Nat 1):

Action Bar:
┌──────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│  T1  │  1  │  2  │  3  │  4  │  5  │  6  │  7  │
│Threat│  A  │  B  │  A  │  B  │  A  │  B  │  A  │
└──────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
         ↑ threat resolves FIRST, before any player action
```

**Beginning threat rules:**
- The threat **attacks the initiative winner** (the player acting first) regardless of who caused the critical initiative failure
- The initiative winner has no warning and no choice — the threat acts against them with its own attack stack
- The initiative winner's Character Stack passively defends (same as normal passive defense: `1d20 + END + DEF`)
- **If the initiative winner beats the threat:** They keep the threat's loot cards immediately (added to hand, not pot). This rewards surviving the ambush.
- **If the initiative winner loses to the threat:** They take damage, and the threat's loot goes into the pot

If BOTH players roll Nat 1: two threats at the beginning, resolved in sequence (T1, T2, then player positions). T1 attacks initiative winner, T2 attacks initiative loser.

#### End Threats (Scenario/Card Triggered)

Threats triggered by encounter draws or action card effects are inserted at the **end** of the action bar — after all player positions.

```
END THREAT (1 threat from scenario card):

Action Bar:
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬──────┐
│  1  │  2  │  3  │  4  │  5  │  6  │  7  │  T1  │
│  A  │  B  │  A  │  B  │  A  │  B  │  A  │Threat│
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴──────┘
                                             ↑ threat resolves LAST
```

**End threat rules:**
- The end threat **only applies to the round winner** (the player winning the round based on net HP advantage). This is a "final boss" surprise — you won the round, but can you survive one more fight?
- The round winner is **out of Action Points** at this point — they've already used all their stacks
- The round winner gets a **choice:**
  1. **Play 1 bonus stack** — they may build ONE action stack from cards in their hand (at no AP cost — it's a desperate last stand). This is their only chance to fight the threat.
  2. **Roll to flee** — roll `1d20 + AGI` vs the threat's difficulty. Success = escape, no damage, but the loot goes to the pot.
- **If the round winner beats the end threat:** They keep the threat's loot AND still claim the pot normally
- **If the round winner loses OR flees:** They **lose the entire pot** — all pot cards are discarded (or go to the opponent in PvP). The threat's loot is also discarded.
- **If the round winner doesn't know** about the end threat in advance (e.g., trap card triggered mid-resolution), this creates a surprise twist at the end of the round. Online: the narrator reveals the threat dramatically. RL: the GM or the threat card is flipped at the end.

**Hidden vs visible end threats:**
- Scenario-triggered threats are **visible** during placement (players see the threat card at the end of the bar and can plan for it)
- Action card / trap-triggered threats are **hidden** until their trigger condition is met during resolution. They appear on the bar only when triggered.

#### Combined Example

```
Player A: 3 AP, Nat 1 initiative → triggers beginning threat
Player B: 3 AP, wins initiative
Scenario card: triggers end threat

Action Bar (9 positions: 1 beginning + 3 + 3 + 1 end + 1 end scenario):
┌──────┬─────┬─────┬─────┬─────┬─────┬─────┬──────┐
│  T1  │  1  │  2  │  3  │  4  │  5  │  6  │  T2  │
│ Wolf │  B  │  A  │  B  │  A  │  B  │  A  │Bandit│
│(Beg) │     │     │     │     │     │     │(End) │
└──────┴─────┴─────┴─────┴─────┴─────┴─────┴──────┘

Resolution order:
T1 (wolf) attacks B (initiative winner) → B passively defends
1: B's action → 2: A's action → 3: B's action → 4: A's action
5: B's action → 6: A's action
T2 (bandit) → round winner faces bandit: 1 bonus stack or flee
```

#### Threat AP and Scaling

Per-round threats always have **1 AP** (1 action stack). They are simpler than encounter threats drawn during the Draw Phase (which can have up to 4 AP and interleave with player positions). Per-round threats are hit-and-run ambushes — one attack, resolved quickly.

**Threat difficulty scaling:**
- Beginning threats: difficulty = `current round number + 2` (escalates as game progresses)
- End threats from scenario: difficulty set by the scenario card
- End threats from action effects: difficulty set by the triggering card

**Threat loot scaling:**
- Easy threat (diff 1–4): 1 common loot card
- Medium threat (diff 5–8): 1 uncommon loot card
- Hard threat (diff 9+): 1 rare loot card + 1 common loot card

#### RL Handling

- Beginning threats: draw a threat card from the encounter deck, place it left of position 1. Initiative winner rolls defense immediately.
- End threats: threat card placed right of the last position. Round winner flips it after cleanup determination.
- Bonus stack: round winner may physically play 1 stack from their hand as a last-stand action.
- Flee roll: 1d20 + AGI vs threat difficulty. Success = safe, loot goes to pot. Fail = lose pot.

---

## Character Stack & Action Stacks

### Two Stack Types

The v2 system separates cards into two distinct stack categories:

**Character Stack** — your persistent "base state," always visible on the sidebar:
```
CHARACTER STACK = CharPerson + [Apparel (head, body, feet, back, ring)] + [Weapon(s) (handL, handR)] + [Magic Focus]
```
- Always in play — never placed on the action bar
- Its modifiers (DEF from armor, ATK from weapon, stat bonuses from gear) apply to ALL action stacks automatically
- Changing equipment (equip/unequip) requires spending 1 AP on an **Equip action stack** on the bar
- Visual: a vertical card stack on the left (your character) or right (opponent) sidebar

**Action Stack** — placed on the Action Bar, one per AP spent:
```
ACTION STACK = CoreCard(s) + [ModifierCards...]
```
- **Core cards:** The action itself, potentially consumable. Examples:
  - Attack card (returned to hand after round)
  - Spell card + Reagent (reagent consumed, spell card returned)
  - Gun card (from hand) + Ammo card (consumed)
  - Health Potion (consumed whether effect needed or not)
  - Investigate card (returned to hand)
  - Talk card (returned to hand)
- **Modifier cards:** Adjacent to the core, these add bonuses but are NOT consumed unless explicitly taken by an opponent action (steal, disarm, sunder). Examples:
  - Skill card: Swordsmanship (+2 to melee attacks)
  - Bonus card: "+1 ATK" tactical advantage
  - Defensive modifier: "+2 Parry" (reduces incoming damage)

### Stack Composition Rules

1. Each action stack must have at least 1 **core card** (the action being performed)
2. A stack can have 0 or more **modifier cards** — there is no hard limit, but cards must be relevant to the action type
3. Magic Effect core cards require a matching Skill Type card as a modifier (see Magic System)
4. Consumable core cards are consumed regardless of outcome (use-or-lose)
5. Modifier cards persist in hand across rounds unless an opponent action removes them
6. The same modifier card cannot be used in multiple action stacks in the same round
7. Character Stack modifiers stack with action stack modifiers (additive)

### Stack Modifier Calculation

Total roll modifier for any action stack = Character Stack base + Action Stack bonuses:

```
Character Stack base:
  Melee attack base  = STR + Weapon ATK
  Ranged attack base = AGI + Weapon ATK
  Defense base       = END + total Apparel DEF
  Magic base         = MAG (Imperial) or INT (Undead/Psionic)
  Social base        = CHA
  Mental base        = INT

Action Stack adds:
  Core card bonuses (if any)
  + Modifier card bonuses (skill card, bonus cards, etc.)
  = Action stack modifier

Total modifier = Character Stack base + Action stack modifier
Roll = 1d20 + total modifier
```

### Action Stack Examples

**Melee Attack (Position 1):**
```
Character Stack (sidebar): Warrior + Plate Armor (DEF +5) + Iron Sword (ATK +4)
Action Stack on bar: [Attack card] + [Swordsmanship +2] + [Power Strike +1]
Roll = 1d20 + STR(14) + 4(sword) + 2(skill) + 1(bonus) = 1d20 + 21
```

**Magic Blast (Position 3):**
```
Character Stack (sidebar): Mage + Silk Robes (DEF +2, MAG DEF +4) + Crystal Staff (MAG +3)
Action Stack on bar: [Fireball spell (consumed)] + [Fire Crystal reagent (consumed)] + [Imperial Mastery skill +2]
Roll = 1d20 + MAG(16) + 3(staff) + 2(skill) = 1d20 + 21
Energy cost: 20 (Fireball) — on hit: 15 fire damage, ignores 2 DEF
```

**Defensive Heal (Position 2):**
```
Character Stack (sidebar): Cleric + Chainmail (DEF +3) + Mace (ATK +2)
Action Stack on bar: [Defend card] + [Health Potion (consumed, +30 HP)]
Defense this position = 1d20 + END(12) + 3(armor) = 1d20 + 15
Also restore 30 HP (potion consumed whether damaged or not)
```

**Talk (Position 4):**
```
Character Stack (sidebar): Rogue + Leather Armor + Dagger
Action Stack on bar: [Talk card] + [Persuasion skill +3]
Online: Opens LLM chat with target. Outcome: 1d20 + CHA + 3(skill)
RL: Player may speak to target. Roll 1d20 + CHA + 3 vs opposed
```

**Investigate + Found Item (Position 5):**
```
Action Stack on bar: [Investigate card] + [Survival skill +2]
Roll = 1d20 + INT(13) + 2(skill) = 1d20 + 15 vs Discovery DC
On success: draw 1 card from encounter deck (may find items, NPCs, etc.)
```

**Flee (Position 6):**
```
Action Stack on bar: [Flee card]
Character Stack adds: AGI(15) + Boots of Speed (+2 from equipped apparel)
Roll = 1d20 + 15 + 2 = 1d20 + 17 vs encounter difficulty
On success: encounter discarded. On fail: threat gets free attack, persists.
```

---

## Mid-Round Disruption

### Concept

During the Resolution Phase, the action bar is not static. Certain spells, skills, and effects can **alter the bar mid-resolution** — inserting new stacks, removing upcoming stacks, or modifying stack bonuses. This creates dynamic, reactive gameplay where a well-timed spell can turn the tide.

### INSERT — Add Stacks Mid-Round

A successful spell or skill with an INSERT effect adds one or more new action stacks immediately after the current position. These resolve before the next scheduled position.

**Trigger:** The core card of the current action stack has an `insert` property. If the action succeeds (passes its roll), the insert activates.

**Example — Freeze Time:**
```
Action Bar before:
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│  1  │  2  │  3  │  4  │  5  │  6  │  7  │
│  A  │  B  │  A  │  B  │  A  │  B  │  A  │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┘

Position 6 resolves: Player B plays "Freeze Time" spell → SUCCESS
→ B inserts 1 action stack from hand at position 6.5

Action Bar after insert:
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│  1  │  2  │  3  │  4  │  5  │  6  │ 6.5 │  7  │
│  A  │  B  │  A  │  B  │  A  │  B  │ *B* │  A  │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
                                       ↑ inserted
Position 6.5 resolves next (before position 7)
```

**Insert count** scales with the card or skill level:
- Level 1 ability: insert 1 stack
- Level 2: insert up to 2 stacks
- Level 3+: insert up to 3 stacks (rare, powerful cards)

Inserted stacks are built from cards in hand and placed immediately (no planning time in RL; online: quick-place UI with 10-second timer).

### REMOVE — Strip Upcoming Stacks

Traps, counters, and disarm effects can remove an opponent's **upcoming** (not yet resolved) action stacks from the bar.

**Rules:**
- Can only remove stacks that have NOT yet resolved (positions ahead of the current one)
- Removed stacks return their cards to the owner's hand (not consumed)
- The bar contracts — subsequent positions shift down

**Example — Disrupt:**
```
Position 3 resolves: Player A plays "Disrupt" skill → SUCCESS
→ Removes Player B's next action stack (position 4)

Action Bar after remove:
┌─────┬─────┬─────┬─────┬─────┬─────┐
│  1  │  2  │  3  │  5  │  6  │  7  │    (position 4 removed)
│  A  │  B  │  A  │  A  │  B  │  A  │
└─────┴─────┴─────┴─────┴─────┴─────┘
```

### MODIFY — Alter Stack Bonuses

Some effects change the modifiers on an opponent's upcoming stack without removing it entirely.

**Examples:**
- **Weaken:** Halve all modifier bonuses on target's next stack (round down)
- **Fortify:** Double your own next stack's modifier bonuses
- **Curse:** Target's next stack suffers -5 to roll
- **Bless:** Your next stack gains +5 to roll

Modifications are applied as temporary adjustments and cleared after the affected stack resolves.

### Disruption Priority

If multiple disruptions trigger at the same position, they resolve in this order:
1. REMOVE effects (strip stacks first)
2. MODIFY effects (alter remaining stacks)
3. INSERT effects (add new stacks)

### RL Disruption Handling

In physical play:
- INSERT: Player takes cards from hand and physically places a new stack after the current position marker
- REMOVE: Opponent picks up their next stack and returns cards to hand
- MODIFY: Place a modifier token (e.g., a coin or marker) on the affected stack to indicate the change

---

## Combat Resolution

### Per-Position Resolution

In the interleaved action bar system, combat resolves **one position at a time**, left to right. Each position's action stack is fully resolved before moving to the next.

#### Active Position Marker

A **marker token** tracks which position is currently being resolved on the action bar. This is the single source of truth for "where are we in the round."

**Online:** The marker is an animated indicator (glowing arrow or highlighted border) that slides from position to position as each resolves. The active position is visually elevated, with resolved positions dimming and unresolved positions at normal brightness.

```
Action bar during resolution (position 3 active):

  ┌───┬───┬═══╗───┬───┬───┬───┐
  │ 1 │ 2 ║▶3 ║ 4 │ 5 │ 6 │ 7 │
  │ ✓ │ ✓ ║ A ║ B │ A │ B │ A │
  └───┴───╚═══╝───┴───┴───┴───┘
   dim  dim  ▲    normal brightness
            ACTIVE
            MARKER
```

**Marker states:**
| Position State | Visual (Online) | Visual (RL) |
|---------------|-----------------|-------------|
| Resolved (done) | Dimmed, check mark, muted color | Card stack turned sideways |
| **Active (current)** | **Glowing border, elevated, marker arrow below** | **Physical token/coin placed on position** |
| Pending (upcoming) | Normal brightness, full color | Cards face-up, untouched |
| Disabled (removed by disruption) | Crossed out, grayed | Card stack removed from bar |
| Inserted (mid-round disruption) | Slides in with animation, pulse effect | Card placed between positions |

**RL token:** Use any small physical token — a coin, a d20 placed on its side, a themed marker piece (the printed deck includes a cut-out marker token on the reference card sheet). Move the token one position to the right after each resolution step.

**Threat position markers:** Beginning threats (before position 1) and end threats (after the last position) use a distinct **red/danger marker** to differentiate them from normal player positions.

```
Resolution walkthrough (7-position bar + beginning threat):

  ┌──────┬───┬───┬───┬───┬───┬───┬───┐
  │  T1  │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │
  │ Wolf │ B │ A │ B │ A │ B │ A │ A │
  └──────┴───┴───┴───┴───┴───┴───┴───┘
    ▲🔴
   THREAT
   MARKER

Marker sequence: T1 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → [End threats if any]
```

**WebSocket event:** `game.v2.position.resolving` fires each time the marker advances, carrying the position number, owner, and action type. The client animates the marker transition.

```
Resolution walkthrough (7-position bar):

Position 1 (Player A): Attack action → roll vs target → apply damage → check disruptions
Position 2 (Player B): Defend action → set defense → apply heal if consumable used
Position 3 (Player A): Investigate → roll vs DC → find item or fail
Position 4 (Player B): Attack action → roll vs A's defense → apply damage
Position 5 (Player A): Spell (Freeze Time) → roll → SUCCESS → INSERT 1 stack at 5.5
Position 5.5 (Player A, inserted): Attack with found item → roll → apply damage
Position 6 (Player B): Flee → roll vs encounter → success/fail
Position 7 (Player A): Rest → recover Energy
```

### Opposed Roll System (1d20)

When an action stack targets an opponent (attack, talk, disarm, etc.), both sides roll:

```
Active player:  1d20 + Character Stack base + Action Stack modifiers
Target:         1d20 + Character Stack base + relevant defense modifiers
                (or passive defense if target has no Defend stack at this position)

Difference = Active player total - Target total
```

**Passive defense:** If the target has no Defend action stack at the current position, they still get a passive defense roll: `1d20 + END + equipped Apparel DEF`. Active Defend stacks add their modifier cards on top of this.

### Outcome Table

| Difference | Outcome | Effect |
|-----------|---------|--------|
| +10 or more | **Critical Hit** | Double damage. Defender's armor loses 2 durability. **Defender drops 1 item** (not apparel) from character stack → goes to pot. |
| +5 to +9 | Solid Hit | Full damage. Defender's armor loses 1 durability. |
| +1 to +4 | Glancing Blow | Half damage (round down). No durability loss. |
| 0 | Stalemate | No damage to either side. |
| -1 to -4 | Deflected | No damage. Attacker's weapon loses 1 durability. |
| -5 to -9 | Countered | Defender deals half their weapon damage to attacker. |
| -10 or less | **Critical Counter** | Defender deals full weapon damage to attacker. **Attacker drops 1 item** (not apparel) → goes to pot. **Attacker loses next action** — their next unresolved action stack on the bar is disabled (removed, cards return to hand). |

**Natural 20 rule:** If the raw d20 roll (before modifiers) is a 20, the outcome is automatically upgraded by one tier (e.g., Solid Hit becomes Critical Hit). Applies to both sides.

**Natural 1 rule:** If the raw d20 roll is a 1, the outcome is automatically downgraded by one tier (e.g., Glancing Blow becomes Stalemate). **Additionally**, a Natural 1 by the attacker causes them to drop 1 item (not apparel) into the pot. Fumbles hurt.

### Critical Effects — Drop & Disable

**Item drop:** When a Critical Hit or Critical Counter forces an item drop, the dropped item is selected from the character stack:
- Weapons first (handL, handR) — the character is disarmed
- If no weapons, ring or back slot items
- Apparel (head, body, feet) is never dropped — it stays equipped
- The dropped card goes into the Round Pot immediately
- The character stack updates visually (weapon disappears from sidebar)

**Action disable (Critical Counter only):** The attacker's next unresolved action stack on the bar is disabled:
- The stack is grayed out / crossed off on the action bar
- When the bar reaches that position, it is skipped
- Cards from the disabled stack return to the attacker's hand (not consumed)
- This is functionally equivalent to a REMOVE disruption triggered by the defender

### Damage Calculation

```
Base damage = Weapon ATK (from Character Stack) + governing stat (STR melee / AGI ranged)
              + Action Stack core card bonus (if any)
Armor reduction = Defender's total DEF (from Character Stack Apparel)
Net damage = max(1, base damage - armor reduction)   // minimum 1 on a hit

Apply outcome multiplier:
  Critical Hit: net damage × 2
  Solid Hit: net damage × 1
  Glancing Blow: net damage × 0.5 (round down, minimum 1)
```

### Unarmed Attack

If no weapon is equipped in the Character Stack, the character fights unarmed:
- Base ATK = floor(STR / 3) (represents raw physical power, range 0–6)
- No durability concerns — fists don't break
- Cannot deal Critical Hit unless a combat skill modifier is in the action stack

### Encounter Combat (vs Encounter Cards)

Encounter threats are participants in the action bar, not passive targets:
- The encounter has its own AP (based on difficulty tier) and fills action bar positions
- At each of the encounter's positions, it performs its behavior action (attack, defend, special)
- The encounter rolls: 1d20 + encounter ATK/DEF mod
- Players defend with passive defense or active Defend stacks
- Reduce encounter HP by damage dealt. At 0 HP, encounter is defeated → collect loot.
- Undefeated encounters carry forward to the next round with **+2 ATK** (they escalate)

### Per-Step Roll & Stat Infographic

At each action bar position, the active player **rolls** for their action. Online, the player clicks a "Roll" button (or the roll can auto-fire). RL, the player physically rolls a d20.

**Before the roll, a Stat Infographic is displayed** showing exactly what goes into this roll:

```
┌─────────────────────────────────────────────────────┐
│  POSITION 3 — YOUR ATTACK                           │
├─────────────────────────────────────────────────────┤
│                                                      │
│  Base Stat:       STR 14           ← from character │
│  Weapon ATK:      +4  (Iron Sword) ← from char stack│
│  Skill Modifier:  +2  (Swordsman)  ← from action stack│
│  Bonus Card:      +1  (+1 ATK)     ← from action stack│
│  ─────────────────────────────                       │
│  TOTAL MODIFIER:  +21                                │
│                                                      │
│  Roll: 1d20 + 21 = ???                               │
│                                                      │
│  CRITICAL RANGE:                                     │
│  ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░  Crit Hit chance: ~50%       │
│  Crit fail range: Nat 1 only (skilled)               │
│                                                      │
│  vs OPPONENT DEFENSE: ~17 (END 12 + Armor DEF 5)    │
│  Estimated outcome: Solid Hit likely                 │
│                                                      │
│               [ 🎲 ROLL ]                            │
└─────────────────────────────────────────────────────┘
```

**Infographic breakdown:**

| Line | Source | Notes |
|------|--------|-------|
| Base Stat | Character card stat (STR/AGI/INT/MAG/CHA/END) | Determined by action type |
| Weapon/Focus | Character Stack equipped weapon or magic focus | Always applies |
| Skill Modifier | Modifier card in the action stack (if any) | Only 1 skill per stack |
| Bonus Cards | Other modifier cards in the action stack | All bonuses stacked |
| Total Modifier | Sum of all above | This is what gets added to the d20 |
| Critical Range | Calculated from stat + skill match (see below) | Visual bar showing probability |
| vs Defense | Opponent's estimated defense value | From their visible character stack |

### Critical Range — Skilled vs Unskilled Actions

The chance of critical outcomes (both success and failure) is influenced by whether the action is **skilled** (has a matching skill modifier card) and the **relevant stat level**. This is displayed on the infographic and is easy to calculate IRL.

**Simple rule: Critical Failure Range**

```
Unskilled action (no matching skill card):
  Critical failure on: Nat 1, 2, or 3  (15% chance)
  → Fumble zone is WIDE when you don't know what you're doing

Skilled action (matching skill card equipped):
  Critical failure on: Nat 1 only  (5% chance)
  → Training reduces fumble chance

Low stat penalty (relevant stat < 8):
  Add +1 to critical failure range
  → Stat 7 or below, unskilled: Nat 1-4 (20% fumble)
  → Stat 7 or below, skilled: Nat 1-2 (10% fumble)
```

**Simple rule: Critical Success Range (expanded Nat 20)**

```
High stat bonus (relevant stat ≥ 16):
  Critical success on: Nat 19 or 20  (10% chance)
  → Exceptional ability expands your crit window

Skilled + high stat (skill card + stat ≥ 16):
  Critical success on: Nat 18, 19, or 20  (15% chance)
  → Master-level performance

Otherwise:
  Critical success on: Nat 20 only  (5% chance)
```

**Summary table (printable for RL reference):**

| Condition | Crit Fail Range | Crit Success Range |
|-----------|----------------|-------------------|
| Unskilled, low stat (<8) | Nat 1–4 (20%) | Nat 20 (5%) |
| Unskilled, normal stat (8-15) | Nat 1–3 (15%) | Nat 20 (5%) |
| Unskilled, high stat (≥16) | Nat 1–3 (15%) | Nat 19–20 (10%) |
| Skilled, low stat (<8) | Nat 1–2 (10%) | Nat 20 (5%) |
| Skilled, normal stat (8-15) | Nat 1 (5%) | Nat 20 (5%) |
| **Skilled, high stat (≥16)** | **Nat 1 (5%)** | **Nat 18–20 (15%)** |

These ranges modify the Nat 20/Nat 1 tier upgrade/downgrade rules. If you roll within your expanded crit range, the tier shift applies. If you roll outside it, no tier shift even on a "normal" 19 or 2.

**Infographic critical range bar** (online only):

The bar visually shows the d20 roll distribution with color-coded zones:
```
1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20
[RED RED RED|                    NORMAL                         |GRN GRN GRN]
  ↑ crit fail zone                                                ↑ crit success zone
```
- Red = critical failure zone (expanded based on skill/stat)
- Green = critical success zone (expanded based on skill/stat)
- The player sees exactly how risky their action is before rolling

### Online Resolution

At each position, the online UI shows:
1. **Stat Infographic** — full modifier breakdown + critical range for the active player's stack
2. **Roll button** — player clicks to roll (or auto-roll if timer-based)
3. Server generates the d20 roll, applies modifiers, determines outcome tier
4. **Outcome display** — roll animation, numbers shown, outcome tier highlighted
5. Narrator LLM generates 1-2 sentence narration for this position
6. Disruptions (INSERT/REMOVE/MODIFY) processed immediately, bar state updated
7. Poker Face emotion data (if enabled) included in narration context
8. Advance to next position

**For the opponent's positions:** The stat infographic shows the opponent's modifier breakdown (since character stacks are visible). The roll is automatic (server-side). The player sees the opponent's roll result and narration.

### RL Resolution

Physical play resolves position by position:
1. Move a position marker along the action bar (left to right)
2. Active player calculates their modifier: **stat + weapon/focus + skill + bonus cards** (simple addition)
3. Check the critical range table (printed on reference card): skilled? stat level? → know your crit zones
4. Roll 1d20. Add modifier. Check if the natural roll is in a crit zone.
5. If opposed: target rolls 1d20 + their defense. If unopposed (vs DC): compare to card's DC value.
6. Compare totals using the outcome table. Apply tier shift if in crit zone. Apply damage, durability, effects.
7. Check for disruptions (INSERT/REMOVE/MODIFY) — handle physically
8. Move marker to next position. Repeat until bar is empty.

---

## Talk / Chat Action Card

### The Silence Rule

**Without a Talk card in play, players cannot communicate about the game.** This applies to:
- Verbal communication (RL)
- Chat/messaging (online)
- Gestures, signals, or any form of strategic communication

Exceptions:
- Declaring attacks/targets during the Placement Phase (required)
- Rules clarifications (always allowed)

### Online Talk (LLM-Powered)

When a Talk card is played targeting another character (NPC or player-controlled):

1. Chat interface opens (WebSocket streaming via existing `cardGame.js` chat system)
2. Player types messages to the target
3. **NPC targets**: LLM responds in character, using:
   - NPC personality from `olio.charPerson` narrative data
   - Reputation score between player and NPC (from interaction history)
   - Current game context (encounter state, needs, nearby threats)
4. **Player targets**: Direct player-to-player chat channel opens (no LLM intermediary)
5. Chat concludes when either party ends it or round timer expires
6. `POST /rest/game/concludeChat` evaluates the conversation:
   - LLM assesses outcome: agreement reached? Information exchanged? Deception detected?
   - Outcome feeds into the opposed Talk roll: favorable chat = +2 bonus, hostile chat = -2 penalty
   - The dice/modifier roll still determines the mechanical outcome

### RL Talk

When a Talk card is played:
1. The player may now speak directly to the target player or GM
2. Conversation is free-form — persuade, threaten, negotiate, bluff
3. After conversation, both sides roll: 1d20 + CHA mod (opposed)
4. If negotiating a deal: the winning roll determines final terms
5. If attempting persuasion: winner gets their desired outcome
6. Talk card returns to hand after the round — it's reusable

### Talk Outcomes

| Talk Context | Success Effect | Failure Effect |
|-------------|---------------|----------------|
| Negotiate with NPC trader | Better trade prices (-20% cost) | Worse prices (+20% cost) |
| Persuade NPC to help | NPC provides info or item | NPC refuses, may become hostile |
| Threaten enemy | Enemy flees or surrenders | Enemy gains +2 ATK from anger |
| Bluff another player | Target believes your claim | Target sees through bluff, you lose 10 Morale |
| Alliance proposal | Temporary alliance formed (3 rounds) | Cannot propose again for 5 rounds |

---

## Magic System

### Three Skill Types

Magic is not a single school — it is divided into three fundamentally different approaches, each powered by a different stat.

| Skill Type | Stat | Philosophy | Flavor |
|-----------|------|-----------|--------|
| **Imperial** | MAG (Magic) | Command and control. Bend reality through force of will. | Classical magic: fire, ice, lightning, force fields |
| **Undead** | INT (Intelligence) | Understand and manipulate the mathematics of existence. | Necromancy, probability manipulation, entropy, animation |
| **Psionic** | INT (Intelligence) | Project consciousness to affect the physical world through mental discipline. | Telekinesis, telepathy, illusion, mind control |

### Magic Effect Cards

Magic Effect cards are spell-like abilities that can be stacked with an Action card. Each Magic Effect specifies:

1. **Required Skill Type**: Imperial, Undead, or Psionic (must have a matching Skill card in the stack)
2. **Minimum Stat**: The stat threshold to cast (e.g., "MAG 12" or "INT 14")
3. **Energy Cost**: Deducted from the caster's Energy track
4. **Effect**: What the spell does mechanically
5. **Reusable**: Whether the card returns to hand (powerful spells) or is consumed (scroll-type)

### Casting Requirements

To play a Magic Effect card, your stack must contain:

```
[Character] + [Action] + [Skill card matching the required Skill Type] + [Magic Effect card]
```

**All three requirements must be met:**
1. A Skill card of the correct type (Imperial, Undead, or Psionic) is in the stack
2. The Character's relevant stat meets or exceeds the Magic Effect's minimum
3. The Character has enough Energy to pay the cost

If any requirement is not met, the Magic Effect card fizzles — it's still consumed if consumable, and Energy is still spent. This is part of the use-it-or-lose-it design.

### Multi-Type Magic Effects

Some powerful Magic Effect cards require **two** skill types (e.g., "Imperial + Psionic"). The caster needs:
- A Skill card for each required type (uses 2 of their 4 skill slots)
- Stats meeting BOTH minimums
- Enough Energy for the (usually higher) cost

### Sample Magic Effect Cards

| Name | Skill Type | Min Stat | Energy | Effect | Reusable |
|------|-----------|----------|--------|--------|----------|
| Fireball | Imperial | MAG 12 | 20 | 15 fire dmg, ignores 2 DEF | Yes |
| Ice Wall | Imperial | MAG 10 | 15 | +5 DEF this round, negates Flee attempts against you | Yes |
| Raise Thrall | Undead | INT 14 | 25 | Defeated encounter becomes your ally for 3 rounds (fights with its original stats) | No (consumed) |
| Entropy Touch | Undead | INT 12 | 15 | Target's equipped weapon loses 3 durability | Yes |
| Mind Read | Psionic | INT 12 | 10 | See one target player's hand (or reveal encounter's loot before fighting) | Yes |
| Telekinetic Slam | Psionic | INT 14 | 20 | 12 force dmg + push target (they cannot attack you next round) | Yes |
| Soul Drain | Undead + Psionic | INT 16 | 30 | Deal 10 dmg and restore 10 HP to self | No (consumed) |
| Arcane Storm | Imperial + Psionic | MAG 14, INT 14 | 35 | 20 dmg to ALL enemies in current encounter | No (consumed) |

### Magic in RL Play

Magic resolution uses the same dice system with magic-specific modifiers:

```
Magic attack: 1d20 + MAG mod (Imperial) or INT mod (Undead/Psionic) + Skill mod
vs target: 1d20 + END mod (resist physical magic) or INT mod (resist mental magic)
```

On hit: apply the Magic Effect's stated damage/effect. On miss: effect fizzles but Energy is still spent and consumable cards are still consumed.

---

## Needs & Survival

### Need Track Mechanics

The four need tracks create ongoing pressure independent of encounters:

| Track | Starts At | Drain | At Zero |
|-------|----------|-------|---------|
| HP | 50 + (END × 5) | Combat damage, hazards, hunger drain | **Defeated.** Character is out. |
| Energy | 50 + (END × 3) | Action costs (5-35 per action) | Can only Defend or Talk. No attacks, magic, crafting, or fleeing. |
| Hunger | 100 | -10 every 3 rounds automatically | Lose 10 HP per round (starvation damage). |
| Morale | 50 + (CHA × 3) | -10 on failed actions, -15 on ally defeat | Cannot Talk or cooperate. -2 to all rolls (despair). |

### Restoring Needs

| Track | Restoration Methods |
|-------|-------------------|
| HP | Health Potion (+30), Bandage (+10), Rest action (+10), Life magic (varies) |
| Energy | Rest action (+20), Ration (+10), Stimulant Potion (+25), sleep event (+full) |
| Hunger | Ration (+30), Cooked Meal (+50), Raw Meat (+15, risk: lose 10 HP from illness), Foraged Berries (+10) |
| Morale | Successful Talk (+15), Ally victory in combat (+10), Rest action (+5), finding loot (+5) |

### Hunger Timer

Every 3 rounds, all players lose 10 Hunger. This creates a clock — you have roughly 30 rounds before starvation without food. Food items become strategically valuable.

---

## AI Modes

### Mode 1: AI as Character Opponent

The AI controls a full NPC character with its own deck, following the same rules as the player — including initiative, AP, and action bar placement.

**Online implementation:**
- Server generates an NPC character via `GET /rest/olio/roll` with stats, inventory, skills
- NPC deck built from the same starter deck rules
- Each round, the AI:
  1. Rolls initiative (server-side 1d20 + AGI)
  2. Receives a condensed game state and its available cards
  3. LLM selects and orders action stacks based on AP, hand, and opponent's visible character stack
  4. Stacks placed on the action bar at the AI's assigned positions
  5. During resolution, the AI responds to mid-round disruptions (see AI Mid-Turn Response below)
- AI personality profile influences strategy (aggressive AI front-loads attacks; cautious AI reserves defense stacks for later positions)

**RL equivalent:** In tabletop play without AI, another player controls the opponent. For solo RL play, a simple decision table is printed on a reference card:

```
AI Priority (check in order):
1. HP < 20%  → Flee or Defend + Heal
2. Enemy HP < 30% → Attack (best weapon)
3. Has magic + Energy > 30 → Magic Attack
4. Hunger < 30 → Use food item
5. Default → Attack with best available weapon
Assign stacks to positions in priority order.
```

### Mode 2: AI as Game Master

The AI doesn't play a character — it runs the world. It controls:
- Which encounter cards are drawn (weighted by narrative context)
- Encounter stack placement (encounter threats get their own AP and positions)
- NPC behavior during encounters
- Narrating events and discoveries
- Adapting difficulty based on player performance

**Online implementation:**
- LLM receives the full game state each round
- LLM selects encounter cards to play (from weighted pool, not random)
- LLM places encounter action stacks on the bar (encounter gets AP based on difficulty tier)
- LLM narrates the encounter context before resolution begins
- During resolution, LLM controls encounter behavior position by position
- LLM responds to mid-turn disruptions affecting encounter stacks

**GM Decision Prompt:**
```
You are the Game Master for a card RPG.
Player: {player state summary, character stack, AP, needs}
Encounter deck: {top 5 available encounters}
Narrative arc: {last 3 rounds summary}
Player performance: {win/loss ratio}

1. Select 1 encounter card. Consider narrative flow, difficulty scaling, resource pressure.
2. The encounter has {N} AP. Place its action stacks as JSON.
3. Narrate the encounter introduction (2-3 sentences).

Output: { encounter, stacks: [...], narration: "..." }
```

**RL equivalent:** In tabletop play, one player acts as GM. They draw encounter cards, place encounter stacks on the bar, and roleplay NPCs. The GM does not have their own character or deck.

### Mode Switching

Online, the player selects mode at game start:
- **"Play vs AI"** → Mode 1 (AI as opponent)
- **"Story Mode"** → Mode 2 (AI as GM)
- Can switch between modes between sessions (campaign mode) but not mid-session

### AI Stack Selection — Condensed LLM Protocol

The AI opponent (Mode 1) and encounter behavior (Mode 2) both use a compact, structured prompt for stack selection and mid-turn response. These prompts are kept small to minimize latency and token usage.

**Round Decision Prompt (sent at the start of each Placement Phase):**

```json
{
  "type": "placement",
  "round": 7,
  "initiative": { "winner": "ai", "aiRoll": 18, "playerRoll": 12 },
  "ai": {
    "ap": 4, "energy": 55, "hp": 70, "hunger": 60, "morale": 80,
    "charStack": "Goblin Warlord | Spiked Plate (DEF+5) | War Axe (ATK+6) | Battle Rage skill",
    "hand": [
      { "id": "a1", "type": "action", "name": "Attack", "energyCost": 10 },
      { "id": "a2", "type": "action", "name": "Defend", "energyCost": 5 },
      { "id": "s1", "type": "skill", "name": "Intimidate", "mod": "+3 CHA" },
      { "id": "c1", "type": "consumable", "name": "Rage Potion", "effect": "+5 ATK 1 round" },
      { "id": "m1", "type": "magic", "name": "Fear Aura", "cost": 15, "insert": 1 }
    ]
  },
  "player": {
    "charStack": "Elf Ranger | Leather Armor (DEF+2) | Longbow (ATK+5, ranged)",
    "needs": { "hp": 45, "energy": 30, "hunger": 40, "morale": 55 }
  },
  "encounter": null,
  "pokerFace": { "emotion": "worried", "confidence": 0.78 }
}
```

**System instructions (included once per game session, ~15 lines):**

```
You are {name}, a {personality} {race}. You are playing a card RPG.

RULES:
- You have {N} AP. Place up to {N} action stacks on the bar.
- You fill POSITIONS: {your assigned positions} (initiative {winner/loser}).
- Each stack: 1 core action card + optional modifier/consumable cards.
- Character Stack (always active) provides base modifiers to all stacks.
- Energy is spent per action. You cannot play actions you can't afford.
- INSERT cards: if your action succeeds, you may add stacks mid-round.
- REMOVE cards: you can strip opponent's next unresolved stack.
- Consumable core cards are lost whether they work or not.

STRATEGY:
- Opponent is visible: {player charStack summary}. Adapt to their gear.
- If opponent looks {pokerFace emotion}: exploit or ignore at your discretion.
- Play in character. {personality} means you {personality behavior hint}.

OUTPUT FORMAT (JSON only, no explanation):
{ "stacks": [
    { "position": 1, "core": ["a1"], "modifiers": ["s1"], "target": "player" },
    { "position": 3, "core": ["a1", "c1"], "modifiers": [], "target": "player" }
  ]
}
```

**Mid-Turn Response Prompt (sent when a disruption alters the bar during resolution):**

```json
{
  "type": "disruption",
  "trigger": "insert",
  "triggerPosition": 5,
  "detail": "Player cast Freeze Time — inserting 1 extra stack after position 5",
  "barState": [
    { "pos": 6, "owner": "player", "resolved": false },
    { "pos": 7, "owner": "ai", "resolved": false },
    { "pos": 8, "owner": "ai", "resolved": false }
  ],
  "aiHand": [
    { "id": "a2", "type": "action", "name": "Defend", "energyCost": 5 }
  ],
  "aiEnergy": 25
}
→ AI responds with updated stacks for its remaining unresolved positions.
→ May swap, replace, or keep current stacks. JSON only.
```

**Response time budget:** AI placement prompt targets < 2 seconds response time. Mid-turn response targets < 1 second. Use fast models (e.g., Haiku-class) for mid-turn responses.

---

## Card Arrangement UX

### Placement Phase Interface

During the Placement Phase, players need to quickly build and arrange action stacks on the Action Bar. The UI supports drag-and-drop with smart assistance.

### Hand Tray

Cards in hand are displayed in a scrollable tray at the bottom of the screen, organized by type:

```
┌──────────────────────────────────────────────────────────────────┐
│  [Actions ▼]  [Consumables ▼]  [Skills ▼]  [Modifiers ▼]  [Equip ▼]  │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐       │
│  │Atk  │ │Def  │ │Flee │ │Heal │ │Sword│ │+1ATK│ │+2Par│       │
│  │     │ │     │ │     │ │Pot. │ │skill│ │     │ │    │       │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘       │
└──────────────────────────────────────────────────────────────────┘
```

- **Type tabs** filter cards by category — click a tab to show only that type
- **All tab** shows everything, grouped by type with dividers
- Cards sorted within each type by relevance (most-used first, then alphabetical)

### Drag-and-Drop

| Action | Gesture | Result |
|--------|---------|--------|
| Add card to stack | Drag from hand → drop on action bar position | Card added to that position's stack |
| Reorder positions | Drag stack between action bar slots | Stacks swap positions |
| Remove from stack | Drag card off the bar → drop back on hand tray | Card returns to hand |
| Move card between stacks | Drag from one bar position → drop on another | Card moves to target stack |

### Smart Assist

- **Compatibility highlighting:** When dragging a card, valid drop targets glow green. Invalid targets (wrong type, insufficient energy, duplicate skill) show red with a tooltip.
- **Auto-build:** Right-click an action card on the bar → "Suggest Modifiers" — system scans hand for compatible modifiers and auto-attaches the best ones. Uses a simple heuristic: match action type to skill category, pick highest bonus modifiers first.
- **Auto-arrange:** Button in the header — calls the AI (online) or applies a heuristic (RL) to suggest optimal stack placement for all AP. Player can accept, modify, or dismiss.
- **Quick-add:** Double-click a modifier card in hand → auto-attaches to the first compatible action stack on the bar. If no compatible stack exists, card bounces back.
- **Shake reject:** Dropping a card on an invalid target triggers a shake animation + "Cannot add: {reason}" tooltip (e.g., "Cannot add: action stack already has a skill card").

### Stack Visual on the Action Bar

Each action bar position shows its stack as a compact card cluster:

```
┌─────────────────────────────────────────┐
│  Position 1 (YOU)                       │
│  ┌─────────┐                            │
│  │ ATTACK  │ ← Core card (prominent)    │
│  │  ⚔️  10E │                            │
│  ├─────────┤                            │
│  │+2 Sword │ ← Modifier (smaller, below)│
│  │+1 ATK   │                            │
│  └─────────┘                            │
└─────────────────────────────────────────┘
```

- Core card is shown prominently (name, icon, energy cost)
- Modifier cards are shown as smaller chips below/beside the core
- Consumable core cards have a flame/spark icon indicating they'll be consumed
- Energy cost displayed on each stack; total Energy cost shown in the header

### Keyboard Shortcuts (Online)

| Key | Action |
|-----|--------|
| `1`–`9` | Select action bar position 1–9 |
| `A` | Quick-add: cycle through action cards for selected position |
| `M` | Quick-add: cycle through modifiers for selected position |
| `Backspace` | Remove last card from selected position |
| `Tab` | Move to next position |
| `Enter` | Confirm placement ("Ready") |
| `Ctrl+Z` | Undo last placement action |

### RL Physical Arrangement

In tabletop play, the action bar is represented by a **play mat** or simply the table space in front of the player:

```
Character Stack          Action Bar (left to right)
┌────────┐      ┌────────┐  ┌────────┐  ┌────────┐
│ Person │      │ Pos 1  │  │ Pos 3  │  │ Pos 5  │
│ Armor  │      │ Attack │  │ Defend │  │ Flee   │
│ Weapon │      │ +Skill │  │ +Potion│  │        │
└────────┘      └────────┘  └────────┘  └────────┘
(to the side)   (core on top, modifiers tucked underneath)
```

Players place cards physically. Core cards face-up on top, modifier cards partially visible underneath. A position marker (token/coin) tracks the current resolution position.

---

## Poker Face (Online Only)

### Concept

"Poker Face" adapts the existing MoodRing webcam emotion capture system (`moodRing.js`, `camera.js`) for in-game use. It reads the player's facial expressions during gameplay and feeds emotion data to the narrator and AI opponent LLMs, enabling witty, friendly, or antagonizing banter. The name reflects the poker-like bluffing element — can you keep a straight face when your HP is critical?

### Technical Foundation

Poker Face reuses the existing MoodRing pipeline:
- **Camera capture:** `camera.js` captures face frame every 5 seconds via `captureAndSend()`
- **Face analysis:** `POST /rest/face/analyze` returns emotion scores (happy, sad, angry, fear, surprise, disgust, neutral), confidence values, and dominant emotion
- **State tracking:** Same emotion color mappings and crossfade animations from `moodRing.js`
- **No new server code** — all existing endpoints and analysis APIs are reused

### Poker Face State

```javascript
pokerFace: {
    enabled: false,                  // opt-in toggle
    banterLevel: 'moderate',         // 'subtle' | 'moderate' | 'aggressive'
    currentEmotion: 'neutral',       // dominant emotion from face analysis
    emotionScores: {},               // full confidence map: { happy: 0.1, fear: 0.7, ... }
    emotionHistory: [],              // last 10 readings with timestamps
    dominantTrend: 'neutral',        // most common emotion over last 60 seconds
    lastTransition: null,            // { from: 'neutral', to: 'fear', secsAgo: 5 }
    commentary: null                 // last LLM banter line (displayed as subtitle)
}
```

### Integration Points

| Component | How Poker Face Data Is Used |
|-----------|---------------------------|
| **Narrator** | Emotion included in narration prompt context. Narrator weaves it in: "Our hero's hands tremble as the dire wolf approaches..." (when player emotion = fear). Banter level controls how directly the narrator references the player's visible emotion. |
| **AI Opponent** | Emotion added to AI decision prompt (`pokerFace` field). AI can exploit detected nervousness (play aggressively, taunt) or respond to confidence (play defensively). Does NOT change dice rolls — purely behavioral/strategic. |
| **Talk/Chat** | When Talk card is played, player's current emotion is sent as context to the NPC/opponent LLM. NPC may react: "You look nervous... perhaps this deal isn't in your favor?" |
| **After-Action Image** | Emotion hint added to scene image prompt (e.g., "warrior with fearful expression" if player emotion = fear during resolution) |
| **Game Log** | Emotion transitions logged in event log: "Round 7: Player shifted from confident to worried" |

### Banter Levels

| Level | Narrator Behavior | AI Opponent Behavior | Talk/Chat Behavior |
|-------|------------------|---------------------|-------------------|
| **Subtle** | Narrator uses emotion as atmospheric context only. Never directly references the player's face. "The air grows tense..." | AI ignores emotion data. Makes decisions purely on game state. | No emotion context sent to chat. |
| **Moderate** | Narrator occasionally references body language. "The hero steels their nerves..." | AI considers emotion for strategy but doesn't taunt. May play more aggressively if player looks scared. | Emotion sent as context. NPC may notice: "You seem uneasy." |
| **Aggressive** | Narrator directly calls out emotion. "Fear flickers across the hero's face!" | AI taunts explicitly: "I can see the fear in your eyes!" Plays to exploit emotion. | Full emotional context. NPCs needle: "You're sweating. Bad sign." |

### Poker Face Prompt Fragment

Added to narrator and AI prompts when Poker Face is enabled:

```
POKER FACE DATA:
Player emotion: {currentEmotion} (confidence: {score}%)
Trend (last 60s): {dominantTrend}
Recent shift: {lastTransition.from} → {lastTransition.to} ({secsAgo}s ago)
Banter level: {banterLevel}

Guidelines for banter level "{banterLevel}":
- subtle: Use emotion as atmosphere only. Do not reference the player's face.
- moderate: Reference body language indirectly. "Steels their nerves", "eyes narrow".
- aggressive: Call out emotion directly. Taunt, needle, commiserate. Be entertaining.
```

### UI Display

The Poker Face widget appears on the player's sidebar (and optionally the opponent's sidebar if both players are human with cameras):

```
┌──────────────┐
│  🎭 Poker Face │
│  ┌──────────┐ │
│  │ 😨 Fear  │ │  ← Current emotion emoji + label
│  │ ████░░░  │ │  ← Confidence bar
│  │ Trend: 😰│ │  ← Dominant trend
│  └──────────┘ │
│  [Subtle|Mod|Aggro] │  ← Banter level toggle
└──────────────┘
```

### Privacy & Configuration

- **Opt-in only:** Off by default. Toggled in game settings or via the sidebar widget.
- **No recording:** Camera frames are analyzed by `/rest/face/analyze` and immediately discarded. Only emotion labels and confidence scores are stored (in session memory, not persisted).
- **No server-side storage:** Poker Face data lives only in the client-side `gameState.pokerFace` object. It is NOT included in game saves or snapshots.
- **Camera permission:** Uses existing `camera.js` permission flow. If camera is denied, Poker Face gracefully disables with a tooltip.
- **Config file:** `media/cardGame/prompts/{deckName}.pokerFace.promptConfig.json` stores the banter prompt template per theme.

---

## Live Narration System (Online Only)

### Concept

Each round of online play is **narrated by an LLM** and **spoken aloud via voice synthesis**. The narrator acts as a sports commentator, dungeon master, or dramatic storyteller — describing the action as it unfolds, reacting to outcomes, building tension, and creating an immersive audio-visual experience. After each round's resolution, an **after-action image** is generated to depict the key moment of the round.

This system is online-only — RL play relies on the players themselves for narration.

### Narrator Personality Profiles

The narrator style is configurable per game or theme. Each profile defines the LLM's persona and voice characteristics:

| Profile | Personality | Voice Style | Use Case |
|---------|------------|-------------|----------|
| **Arena Announcer** | Bombastic sports commentator. Over-the-top excitement, play-by-play analysis. | Fast-paced, deep voice, dramatic pauses | vs AI (Mode 1), combat-heavy |
| **Dungeon Master** | Classic tabletop DM. Atmospheric, descriptive, world-building. | Measured pace, storyteller cadence | Story Mode (Mode 2) |
| **War Correspondent** | Gritty battlefield reporter. Terse, factual with emotional undertones. | Clipped, urgent, slightly breathless | Dark themes, high mortality |
| **Bard** | Poetic narrator. Speaks in rhythm, references lore, foreshadows. | Melodic, flowing, theatrical | High Fantasy themes |
| **Custom** | Player-defined persona via free-text description. | Configurable voice profile | Any |

### Narration Trigger Points

The LLM is called at specific moments during each round:

```
ROUND START
└─ "Opening narration" — sets the scene for this round
   Prompt context: round number, current game state, pending threats, need levels
   Duration: 1-2 sentences
   Voice: synthesized + played before draw phase begins

ENCOUNTER REVEAL
└─ "Encounter introduction" — describes what the player encounters
   Prompt context: encounter card data, terrain flavor, creature/NPC description
   Duration: 2-3 sentences
   Voice: synthesized + played during encounter reveal animation

STACK REVEAL
└─ "Action call" — announces what each side is doing
   Prompt context: player's stack composition, opponent's stack composition
   Duration: 1-2 sentences per side
   Voice: synthesized + played during the reveal phase

RESOLUTION
└─ "Outcome narration" — the big moment, describes what happened
   Prompt context: dice rolls (actual numbers), damage dealt, effects applied,
                   items consumed, durability changes, defeat/victory
   Duration: 3-5 sentences (the longest narration, most dramatic)
   Voice: synthesized + played during resolve phase
   → TRIGGERS: after-action image generation

ROUND END
└─ "Wrap-up" — quick status summary, foreshadowing
   Prompt context: updated need levels, carried threats, round count
   Duration: 1 sentence
   Voice: synthesized + played during cleanup phase
```

### LLM Narration Prompts

**System prompt (set once per game):**
```
You are the narrator for a card-based RPG.
Your personality: {narratorProfile.personality}
Your style: {narratorProfile.voiceStyle}
The game theme is: {theme.name} — {theme.description}
The player character is: {player.character.name}, a {player.character.race}
The opponent is: {opponent.character.name} (AI) / "the wilds" (GM mode)

Rules for narration:
- Keep it SHORT. Max {maxSentences} sentences per narration point.
- Be dramatic but concise. Every word counts.
- Reference card names and character names by name.
- Build running narrative continuity — reference previous rounds' events.
- Never reveal hidden information (opponent's hand in plan phase).
- Use present tense for action, past tense for recaps.
- Match the energy to the stakes: low HP = tense, big hit = explosive.
```

**Per-trigger prompt (example: Resolution):**
```
ROUND {roundNumber} — RESOLUTION

Player's stack: {player.stacks[0].description}
  Roll: {player.roll.natural} + {player.roll.modifier} = {player.roll.total}
Opponent's stack: {opponent.stacks[0].description}
  Roll: {opponent.roll.natural} + {opponent.roll.modifier} = {opponent.roll.total}

Outcome: {outcomeName} (difference: {difference})
Damage dealt: {damageDealt} to {damageTarget}
Effects: {effectsSummary}
Items consumed: {consumedItems}
Defeat: {defeatInfo or "none"}

Previous round summary: {lastRoundNarration}

Narrate this resolution in {narratorProfile.personality} style. 3-5 sentences.
Also output a one-line scene description for image generation (prefix with "IMAGE:").
```

### Voice Synthesis Pipeline

Voice narration uses the existing AM7 voice synthesis infrastructure — the same `AudioEngine` and `VoiceService` used by Magic8.

**Flow:**
```
1. LLM generates narration text
   └─ POST /rest/game/v2/narrate { roundId, trigger, context }
   └─ Returns: { text: "...", imagePrompt: "..." }

2. Text sent to voice synthesis
   └─ POST /rest/voice/{hash} { content, profileId, ... }
   └─ Returns: { dataBytesStore: base64-encoded audio }
   └─ Uses narrator's voiceProfileId (from narrator profile config)

3. Audio decoded and queued
   └─ AudioEngine.createVoiceSource(text, profileId)
   └─ Returns AudioBuffer ready for playback

4. Playback triggered at the appropriate phase
   └─ AudioEngine.playSource(sourceId)
   └─ Audio plays through browser speakers
   └─ Narration text also displayed as subtitle overlay on the game UI

5. Next phase waits for narration to complete (or player skips)
   └─ onended callback advances the game phase
   └─ Skip button available: stops audio, advances immediately
```

**Voice profile configuration (per narrator):**
```json
{
  "narratorId": "arena-announcer",
  "voiceProfileId": "uuid-of-voice-profile",
  "voiceSettings": {
    "speed": 1.1,
    "pitch": "low",
    "emphasis": "dramatic",
    "pauseBetweenSentences": 400
  }
}
```

Uses the existing `POST /rest/voice/{hash}` endpoint with the same `vprops` body format as Magic8's `AudioEngine.createVoiceSource()`. The `voiceProfileId` references a pre-configured voice profile on the server (same as Magic8 voice profiles).

### Pre-Synthesis for Low Latency

To avoid waiting for synthesis during gameplay, narrations are **pre-synthesized** where possible:

- **Round Start** narration: synthesized during the previous round's cleanup phase (predicted from game state)
- **Encounter Reveal** narration: synthesized immediately when the encounter card is drawn (before animation plays)
- **Stack Reveal / Resolution**: must wait for actual data — synthesized during the reveal animation (which provides ~2-3 seconds of buffer)
- **Fallback**: if synthesis hasn't completed by the time it's needed, display text-only subtitles and play audio when ready (slightly delayed, not blocking)

### Subtitle Display

All narration text is displayed as a **subtitle overlay** on the game UI, synchronized with voice playback:

```
+----------------------------------------------------+
|                                                    |
|            [Game content as normal]                |
|                                                    |
+----------------------------------------------------+
| 🎙 "Aelindra raises her blazing staff as the      |
|     wolf pack closes in — this could get ugly!"    |
+----------------------------------------------------+
```

- Subtitles appear at the bottom of the play area
- Text fades in word-by-word synced to voice playback speed
- Dismiss on click/tap or auto-dismiss when voice completes
- Configurable: voice + subtitles, subtitles only, voice only, or off

### After-Action Image Generation

After each round's resolution, an **after-action image** is generated to depict the key moment. This creates a visual story log of the game.

**How it works:**
1. The LLM's resolution narration includes a scene description line prefixed with `IMAGE:` (part of the narration prompt output)
2. That scene description is used as the SD prompt for after-action image generation
3. The image is generated asynchronously while the next round proceeds
4. When ready, the image appears in the **Event Log** panel alongside the round's narration text

**SD prompt construction:**
```
Scene: {llm-generated IMAGE: line}
Characters: {player.character.shortDescription}, {opponent.character.shortDescription}
Style: {theme.artStyle.promptSuffix}, action scene, dramatic lighting, wide shot
Negative: text, UI elements, card borders, watermark
```

**Example after-action images:**

| Round Event | LLM IMAGE: Output | Resulting Image |
|-------------|-------------------|-----------------|
| Player crits wolf pack | "A silver-haired elf drives her staff through a lunging wolf, arcane fire scattering the pack" | Action scene: elf with staff vs wolves, fire, forest |
| Player takes heavy damage | "The wolf alpha's jaws close on the warrior's shield arm, blood spattering the snow" | Dramatic close-up: wolf bite, damaged armor, snow |
| Player flees successfully | "Boots pounding through the underbrush, the ranger barely escapes the cave bear's swipe" | Motion blur: character running through forest, bear behind |
| Trade with NPC | "Gold coins exchange hands over a weathered merchant's cart, both parties eyeing the deal" | Calm scene: two characters at a trade cart |

**After-action image config (sdConfig addition):**
```json
{
  "configId": "afterAction",
  "dimensions": { "width": 768, "height": 512 },
  "promptTemplate": "{sceneDescription}, {characterDescriptions}, action scene, dramatic lighting, {theme.artStyle.promptSuffix}",
  "negativePrompt": "text, UI, card border, watermark, frame, HUD, game interface",
  "aspectRatio": "3:2",
  "regenerateOn": "never",
  "priority": 9
}
```

This becomes the **6th SD config** — `sdConfig.afterAction`. It has the lowest priority in the generation queue since it's for the event log, not card display.

### Event Log as Visual Story

The event log panel becomes a visual narrative timeline:

```
+-----------------------------------+
| ROUND 5                          |
| [After-action image]             |
| 🎙 "With a decisive swing,      |
|  Aelindra cleaves through the   |
|  wolf alpha. The pack scatters   |
|  into the darkness."             |
| ✅ Critical Hit — 24 damage     |
| 🐺 Wolf Pack defeated           |
| 🎒 Loot: Wolf Pelt, Raw Meat   |
+-----------------------------------+
| ROUND 4                          |
| [After-action image]             |
| 🎙 "Steel meets fang as the     |
|  wolves circle closer..."       |
| ⚔️ Glancing Blow — 6 damage    |
+-----------------------------------+
```

Each round entry in the log contains:
- After-action image (when generated; placeholder shimmer until ready)
- Narration text (from LLM)
- Mechanical outcome summary (damage, effects, loot)

### Audio Controls

```
+--[🔊]--[🎙 Arena Announcer ▼]--[⏭ Skip]--[CC]--+
```

| Control | Function |
|---------|----------|
| Volume | Master narration volume (0-100%) |
| Narrator select | Switch narrator profile mid-game |
| Skip | Skip current narration, advance immediately |
| CC | Toggle subtitles on/off |
| Mute | Silence voice but keep subtitles |

### Narrator Configuration in Theme

The narrator profile can be specified in the deck theme config:

```json
{
  "themeId": "dark-medieval",
  "narrator": {
    "defaultProfile": "dungeon-master",
    "voiceProfileId": "uuid",
    "maxSentencesPerTrigger": {
      "roundStart": 2,
      "encounterReveal": 3,
      "stackReveal": 2,
      "resolution": 5,
      "roundEnd": 1
    },
    "enableAfterActionImages": true,
    "enableVoice": true,
    "enableSubtitles": true
  }
}
```

---

## Image Generation Pipeline

### Dynamic Card Art Generation

Card images are generated via the existing AM7 SD (Stable Diffusion) image pipeline. Cards render with placeholder graphics immediately, then update as images are generated asynchronously.

**Every card face must have a representative image.** The image conveys what the card *is* at a glance — a portrait for characters, a mannequin display for apparel, a detailed rendering of weapons and items, etc.

#### Card Image Source Map

| Card Type | Image Content | Generation Source | Endpoint |
|-----------|--------------|-------------------|----------|
| **Character** | Portrait of the character wearing their equipped gear | `sdConfig.charPerson` (Config 3) | `POST /rest/olio/charPerson/{id}/reimage` |
| **Apparel** | Clothing/armor displayed on a mannequin form | **Existing mannequin pipeline** — `NarrativeUtil.getMannequinPrompt()` → `SDUtil.generateMannequinImages()` | `POST /rest/olio/apparel/{id}/reimage` |
| **Item (Weapon)** | Isolated weapon on dark background | `sdConfig.item` subtype `weapon` (Config 5) | `POST /rest/game/asset/generate` |
| **Item (Consumable)** | Potion, food, material on dark background | `sdConfig.item` subtype `consumable` (Config 5) | `POST /rest/game/asset/generate` |
| **Action** | Themed action-type illustration (attack = clashing swords, defend = raised shield, etc.) | `sdConfig.item` subtype `action` (Config 5) | `POST /rest/game/asset/generate` |
| **Talk** | Themed social/diplomatic illustration | `sdConfig.item` subtype `action` (Talk variant) (Config 5) | `POST /rest/game/asset/generate` |
| **Encounter (Threat)** | Creature or hostile NPC in aggressive pose | `sdConfig.animal` (Config 4) for beasts; `sdConfig.charPerson` (Config 3) for humanoid NPCs | `/rest/olio/animal/{id}/reimage` or `/rest/olio/charPerson/{id}/reimage` |
| **Encounter (Event/Discovery)** | Scene depicting the event or location | `sdConfig.item` subtype `encounter` (Config 5) | `POST /rest/game/asset/generate` |
| **Skill** | Symbolic emblem or rune representing the skill | `sdConfig.item` subtype `skill` (Config 5) | `POST /rest/game/asset/generate` |
| **Magic Effect** | Spell energy, particle effect, ethereal visual | `sdConfig.item` subtype `magicEffect` (Config 5) | `POST /rest/game/asset/generate` |

**Apparel uses the mannequin pipeline** — not the generic item config. The existing `NarrativeUtil.getMannequinPrompt()` generates prompts showing clothing on a "full body retail mannequin" at cumulative wear levels (base layer → under → suit → outer). This produces clothing images that clearly show the garment's shape, material, and coverage without human features. The mannequin negative prompt excludes "human face, realistic skin, hair, eyes, hands" to keep focus on the apparel itself. Generated images are stored in `~/Gallery/Apparel/{apparelName}/` with wear level metadata.

**Action and Talk cards** get unique themed illustrations (not just icons). During deck build, each action type generates a card-face image showing the action in the theme's art style — e.g., High Fantasy "Attack" shows a dramatic sword clash, Sci-Fi "Attack" shows a laser firefight. These are generated once per theme and reused across games.

There are **seven distinct SD image configurations**, each targeting a different generation domain with its own prompt strategy, dimensions, and style constraints.

### SD Image Configs (sdConfig)

#### Config 1: Card Backs (`sdConfig.cardBack`)

Generates the type-specific card back designs. These are generated **once per theme** (not per card) and cached/reused for every card of that type.

```json
{
  "configId": "cardBack",
  "dimensions": { "width": 744, "height": 1039 },
  "batchMode": true,
  "perType": true,
  "promptTemplate": "{cardTypeName} card back, {theme.artStyle.promptSuffix}, ornate border, centered {cardTypeIcon} emblem, {theme.artStyle.cardBackVariant} texture, no text, symmetrical design, card game back",
  "negativePrompt": "text, words, letters, numbers, asymmetrical, photo, realistic face",
  "referenceImage": "deckAssets.gameBackground",
  "referenceWeight": 0.25,
  "count": 8,
  "regenerateOn": "theme-change"
}
```

**Generation triggers:**
- New theme selected → generate all 8 type backs
- Never regenerated during gameplay (stable reference images)
- Stored at `~/Gallery/CardGame/cardBack/{themeId}/{cardType}.png`

**Per-type prompts:**
| Type | Prompt Fragment |
|------|----------------|
| Character | "golden shield crest, royal filigree" |
| Apparel | "silver chainmail weave, metallic sheen" |
| Item | "green leather pouch, crossed tools" |
| Action | "red lightning burst, dynamic energy" |
| Talk | "blue speech scroll, diplomatic seal" |
| Encounter | "purple mist portal, unknown danger" |
| Skill | "orange star compass, knowledge runes" |
| Magic Effect | "teal arcane circle, elemental sigils" |

#### Config 2: Card Theme / Style (`sdConfig.cardStyle`)

This is the most unique config — it can use an **LLM call** to compose a distinct visual card layout style, which is then used to dynamically render all cards in the game.

Rather than generating a static image, this config produces a **card style definition** — a structured format describing borders, frame elements, text regions, and decorative elements. The LLM designs the aesthetic; the client renders cards dynamically using that style + actual data + generated/placeholder images.

```json
{
  "configId": "cardStyle",
  "mode": "llm-composed",
  "llmPrompt": "Design a visual card layout style for a {theme.name} card game. The style should feel {theme.description}. Output a JSON card style definition with these fields: borderStyle (CSS-like border properties), frameImage (description for SD generation of card frame overlay), textRegions (array of named regions with position/font/color), iconStyle (how stat icons look), cornerIcons (placement for small type-suit icons at top-left, top-right, and bottom-right corners — 24x24px, tinted per card type), colorScheme (foreground, background, accent per card type), decorativeElements (corner flourishes, divider lines, etc). The style must work for all 8 card types with type-specific color variations. Corner icons must remain visible when cards are fanned or partially overlapping.",
  "outputFormat": "json",
  "sdFollowUp": true
}
```

**LLM-Composed Style Flow:**

```
1. LLM STYLE COMPOSITION
   └─ POST /rest/game/v2/generateCardStyle { themeId }
   └─ LLM receives theme config + card type list
   └─ LLM outputs a cardStyleDef JSON:

   {
     "styleName": "Weathered Parchment",
     "borderSpec": {
       "width": 12,
       "cornerRadius": 8,
       "texture": "rough-hewn wood grain",
       "innerBevel": true
     },
     "frameOverlay": {
       "sdPrompt": "ornate wooden card frame, medieval, burnt edges, {theme.artStyle.promptSuffix}",
       "opacity": 0.9
     },
     "textRegions": {
       "title": { "x": "10%", "y": "52%", "w": "80%", "h": "8%", "font": "bold 16px serif", "color": "#2B1810", "align": "center" },
       "subtitle": { "x": "10%", "y": "60%", "w": "80%", "h": "5%", "font": "italic 11px serif", "color": "#5A3A28" },
       "imageArea": { "x": "8%", "y": "5%", "w": "84%", "h": "45%" },
       "statsArea": { "x": "8%", "y": "66%", "w": "84%", "h": "18%" },
       "flavorText": { "x": "10%", "y": "85%", "w": "80%", "h": "8%", "font": "italic 9px serif", "color": "#6B4E3A" },
       "footer": { "x": "5%", "y": "93%", "w": "90%", "h": "5%" }
     },
     "cornerIcons": {
       "topLeft": { "x": "3%", "y": "2%", "w": "24px", "h": "24px" },
       "topRight": { "x": "89%", "y": "2%", "w": "24px", "h": "24px" },
       "bottomRight": { "x": "89%", "y": "93%", "w": "24px", "h": "24px", "rotate": 180 },
       "tintFromType": true,
       "opacity": 0.85
     },
     "statIcons": {
       "style": "embossed metal",
       "sdPrompt": "small {statName} icon, embossed bronze, medieval, 32x32"
     },
     "dividers": {
       "style": "thin gold line with center diamond",
       "sdPrompt": "horizontal ornamental divider, gold filigree, diamond center, transparent background"
     },
     "typeColorOverrides": {
       "character": { "borderTint": "#C5A55A", "accentGlow": "#FFD700" },
       "encounter": { "borderTint": "#6B3FA0", "accentGlow": "#9B59B6" },
       "magic": { "borderTint": "#2E8B8B", "accentGlow": "#00CED1" }
     }
   }

2. SD GENERATION OF FRAME ELEMENTS
   └─ Frame overlay image generated from frameOverlay.sdPrompt
   └─ Divider image generated from dividers.sdPrompt
   └─ Stat icons generated from statIcons.sdPrompt (one per stat)
   └─ Corner flourishes (if specified) generated
   └─ All stored under ~/Gallery/CardGame/cardStyle/{themeId}/

3. CLIENT CARD RENDERER
   └─ Loads cardStyleDef JSON
   └─ For each card render:
       a. Draw card background (type color from theme palette)
       b. Overlay frame image at full card size
       c. Draw corner type icons (top-left, top-right, bottom-right) from cardTypeCornerIcons
          └─ Tinted to card type accent color, 24×24px (32×32 print)
          └─ Bottom-right rotated 180° (like playing card suit pips)
       d. Place card-specific image (or placeholder) in imageArea region
       e. Render text (name, stats, effects) into defined textRegions
       f. Apply type-specific color overrides (borderTint, accentGlow)
       g. Draw stat icons from generated icon set
       h. Draw dividers between sections
   └─ Same renderer produces both screen and print output
```

**Why LLM-composed?** A human designer would need to manually lay out card frames, choose fonts, position text areas, and design decorative elements for each theme. The LLM does this automatically — given a theme description ("dark medieval, gritty, parchment textures"), it outputs a complete card layout spec. The SD pipeline then generates the visual elements (frames, icons, dividers) that the layout references. This means a new theme can go from a one-line description to a fully styled card set with no manual design work.

**Fallback:** If the LLM style generation fails or produces invalid JSON, the client falls back to a hardcoded default card style (clean white background, black text, simple colored borders).

#### Config 3: Character Portraits (`sdConfig.charPerson`)

Generates character card images — portraits of the player character and NPCs. This is the most frequently regenerated config due to the reimage-on-equip mechanic.

```json
{
  "configId": "charPerson",
  "dimensions": { "width": 512, "height": 768 },
  "promptTemplate": "{race} {gender}, {physicalDescription}, {apparelDescription}, {weaponDescription}, {magicAffinity}, portrait, {theme.artStyle.promptSuffix}, card art",
  "negativePrompt": "deformed, blurry, text, watermark, frame, border, multiple people",
  "dynamicPromptFields": {
    "apparelDescription": "built from equipped Apparel cards (body, head, feet, back)",
    "weaponDescription": "built from equipped Weapon cards (handL, handR)",
    "magicAffinity": "visual cue from equipped magic skill type (Imperial=radiant aura, Undead=dark mist, Psionic=glowing eyes)"
  },
  "reimageTriggers": ["equip-change", "level-up", "manual"],
  "cacheHistory": true,
  "debounceMs": 500
}
```

**Prompt assembly example:**
```
Race/gender: "female elf"
Physical: "sharp features, green eyes, silver hair"
Apparel: "wearing steel breastplate, leather boots, dark hooded cloak"
Weapon: "holding a glowing staff in right hand"
Magic: "faint teal psionic shimmer around temples"
Style: "portrait, dark fantasy, oil painting style, medieval, muted colors, card art"

Full prompt: "female elf, sharp features, green eyes, silver hair, wearing steel breastplate, leather boots, dark hooded cloak, holding a glowing staff in right hand, faint teal psionic shimmer around temples, portrait, dark fantasy, oil painting style, medieval, muted colors, card art"
```

Uses existing `/rest/olio/charPerson/{id}/reimage` endpoint with extended prompt data.

#### Config 4: Animals / Creatures (`sdConfig.animal`)

Generates images for animal and creature encounter cards. These are typically generated once when the encounter card is created and not regenerated.

```json
{
  "configId": "animal",
  "dimensions": { "width": 512, "height": 512 },
  "promptTemplate": "{creatureName}, {creatureDescription}, {behaviorHint}, {terrainContext}, creature illustration, {theme.artStyle.promptSuffix}",
  "negativePrompt": "cute, cartoon, chibi, text, watermark, human, person",
  "dynamicPromptFields": {
    "behaviorHint": "aggressive pose if threat, neutral if NPC encounter",
    "terrainContext": "environment hint from encounter card (forest, cave, desert, etc.)"
  },
  "reimageTriggers": ["manual"],
  "squareCrop": true
}
```

**Prompt examples:**
| Creature | Prompt |
|----------|--------|
| Wolf Pack | "pack of wolves, snarling, aggressive stance, dark forest, creature illustration, dark fantasy, oil painting style" |
| Cave Bear | "enormous brown bear, rearing up, aggressive, cave entrance, creature illustration, dark fantasy, oil painting style" |
| Giant Spider | "giant spider, venomous fangs, web-covered lair, creature illustration, dark fantasy, oil painting style" |
| Wandering Merchant (NPC) | "traveling merchant with pack mule, friendly, dusty road, character illustration, dark fantasy, oil painting style" |

Uses existing `/rest/olio/animal/{id}/reimage` endpoint for animals. NPC encounter portraits use the charPerson config instead.

#### Config 5: Items / Assets (`sdConfig.item`)

Generates images for Item cards (weapons, consumables), Action cards, Encounter scene cards, Skill cards, and Magic Effect cards. These are object- or scene-focused images — isolated items on clean backgrounds, or dramatic action illustrations.

**Note:** Apparel cards do NOT use this config. Apparel uses the dedicated mannequin pipeline — see [Card Image Source Map](#card-image-source-map) above.

```json
{
  "configId": "item",
  "dimensions": { "width": 512, "height": 512 },
  "promptTemplate": "{itemName}, {itemType}, {materialDescription}, {specialProperties}, item icon, isolated on dark background, {theme.artStyle.promptSuffix}",
  "negativePrompt": "person, hand, holding, worn, equipped, text, watermark, multiple items",
  "subtypeOverrides": {
    "weapon": {
      "promptSuffix": "weapon, sharp detail, metallic sheen",
      "negativeAppend": "broken, rusty"
    },
    "consumable": {
      "promptSuffix": "potion bottle, food item, glowing",
      "negativeAppend": "empty, broken"
    },
    "action": {
      "dimensions": { "width": 512, "height": 768 },
      "promptTemplate": "{actionName}, dramatic {actionDescription}, {theme.artStyle.promptSuffix}, card illustration, dynamic composition",
      "negativePrompt": "text, UI, icon, simple, flat, watermark",
      "perAction": true,
      "note": "Generated once per theme per action type (8 action + 1 talk = 9 images)"
    },
    "encounter": {
      "promptTemplate": "{encounterName}, {encounterDescription}, environment scene, {terrainContext}, {theme.artStyle.promptSuffix}, dramatic lighting",
      "negativePrompt": "text, watermark, person, UI",
      "note": "For Event and Discovery encounter subtypes. Threat encounters use animal or charPerson config instead."
    },
    "skill": {
      "promptSuffix": "skill emblem, symbolic icon, glowing rune",
      "negativeAppend": "person, realistic"
    },
    "magicEffect": {
      "promptSuffix": "spell effect, magical energy, particle effect, ethereal",
      "negativeAppend": "person, caster, hands"
    }
  },
  "reimageTriggers": ["manual"],
  "squareCrop": true
}
```

**Prompt examples:**
| Item | Subtype | Prompt |
|------|---------|--------|
| Iron Sword | weapon | "Iron Sword, longsword, forged iron with leather grip, item icon, isolated on dark background, weapon, sharp detail, metallic sheen, dark fantasy, oil painting style" |
| Health Potion | consumable | "Health Potion, red glowing liquid in glass flask, item icon, isolated on dark background, potion bottle, glowing, dark fantasy, oil painting style" |
| Attack | action | "Attack, dramatic sword clash between two warriors, sparks flying, dark fantasy, oil painting style, card illustration, dynamic composition" |
| Defend | action | "Defend, warrior bracing behind raised shield, incoming blow deflected, dark fantasy, oil painting style, card illustration, dynamic composition" |
| Talk | action | "Talk, diplomatic meeting between two figures, candlelit table, scrolls and seals, dark fantasy, oil painting style, card illustration, dynamic composition" |
| Sandstorm | encounter | "Sandstorm, swirling desert storm engulfing ruins, environment scene, arid wasteland, dark fantasy, oil painting style, dramatic lighting" |
| Swordsmanship | skill | "Swordsmanship, crossed swords emblem, combat mastery, item icon, isolated on dark background, skill emblem, glowing rune, dark fantasy, oil painting style" |
| Fireball | magicEffect | "Fireball, sphere of flames, explosive fire magic, item icon, isolated on dark background, spell effect, magical energy, ethereal, dark fantasy, oil painting style" |

**Apparel card images** use the existing mannequin pipeline:
```
POST /rest/olio/apparel/{objectId}/reimage
  └─ NarrativeUtil.getMannequinPrompt(apparel, wearLevel)
     → "8k highly detailed ((professional fashion photography)) of a ((full body retail mannequin)) displaying: {cumulative clothing description}"
  └─ SDUtil.generateMannequinImages(user, galleryPath, apparel, ...)
     → Generates at 512×768, stores in ~/Gallery/CardGame/apparel/{name}/
  └─ Negative prompt: "human face, realistic skin, hair, eyes, hands, fingers, feet, toes, skin texture"
```

Uses `POST /rest/game/asset/generate` for non-apparel items.

#### Config 6: Deck Visual Assets (`sdConfig.deckAssets`)

Generates **thematic UI and gameplay assets** — backgrounds, icons, titles, and decorative elements used throughout the game interface. These are generated **once per theme** during deck build and cached for reuse.

```json
{
  "configId": "deckAssets",
  "mode": "batch",
  "assets": {
    "gameBackground": {
      "dimensions": { "width": 1920, "height": 1080 },
      "promptTemplate": "game table background, {theme.artStyle.promptSuffix}, top-down view, subtle texture, dark vignette edges, no objects, seamless pattern",
      "negativePrompt": "text, cards, people, hands, bright center, logo",
      "variants": 3,
      "usage": "Main game board background, cycles between variants"
    },
    "potBackground": {
      "dimensions": { "width": 512, "height": 512 },
      "promptTemplate": "treasure pile surface, {theme.artStyle.promptSuffix}, velvet cloth, coins scattered, top-down, centered",
      "negativePrompt": "text, hands, cards, person",
      "usage": "Round Pot area background"
    },
    "actionBarBackground": {
      "dimensions": { "width": 1024, "height": 128 },
      "promptTemplate": "horizontal battle line, {theme.artStyle.promptSuffix}, faded terrain strip, abstract, panoramic",
      "negativePrompt": "text, people, cards, UI elements",
      "usage": "Action bar track background"
    },
    "titleBanner": {
      "dimensions": { "width": 800, "height": 200 },
      "promptTemplate": "{theme.name} title banner, ornate lettering background, {theme.artStyle.promptSuffix}, no text, decorative header frame",
      "negativePrompt": "actual text, letters, words, numbers",
      "textOverlay": true,
      "usage": "Game title / theme name banner (text rendered as vector overlay)"
    },
    "d20Icon": {
      "dimensions": { "width": 128, "height": 128 },
      "promptTemplate": "twenty-sided die icon, {theme.artStyle.promptSuffix}, blank face, single die, isolated, transparent background",
      "negativePrompt": "numbers, text, multiple dice, hand",
      "usage": "Roll button icon, initiative display, stat infographic"
    },
    "statIcons": {
      "dimensions": { "width": 64, "height": 64 },
      "promptTemplate": "{statName} stat icon, {theme.artStyle.promptSuffix}, emblem, symbolic, 64px, isolated",
      "negativePrompt": "text, numbers, person, realistic",
      "perStat": true,
      "stats": ["STR", "AGI", "END", "INT", "MAG", "CHA"],
      "usage": "Stat icons on character cards, infographic, needs panel"
    },
    "needIcons": {
      "dimensions": { "width": 48, "height": 48 },
      "promptTemplate": "{needName} icon, {theme.artStyle.promptSuffix}, small emblem, iconic, 48px",
      "negativePrompt": "text, person, realistic",
      "perNeed": true,
      "needs": ["HP", "Energy", "Hunger", "Morale", "AP"],
      "usage": "Need bar icons on sidebar panels"
    },
    "actionTypeIcons": {
      "dimensions": { "width": 64, "height": 64 },
      "promptTemplate": "{actionName} action icon, {theme.artStyle.promptSuffix}, symbolic emblem, bold, 64px",
      "negativePrompt": "text, person, realistic",
      "perAction": true,
      "actions": ["Attack", "Defend", "Flee", "Rest", "Use Item", "Investigate", "Craft", "Watch", "Talk"],
      "usage": "Action card icons, action bar position headers"
    },
    "outcomeIcons": {
      "dimensions": { "width": 64, "height": 64 },
      "promptTemplate": "{outcomeName}, {theme.artStyle.promptSuffix}, dramatic icon, bold, glowing",
      "negativePrompt": "text, person",
      "perOutcome": true,
      "outcomes": ["Critical Hit", "Solid Hit", "Glancing Blow", "Stalemate", "Deflected", "Countered", "Critical Counter"],
      "usage": "Outcome display during resolution phase"
    },
    "phaseHeaders": {
      "dimensions": { "width": 512, "height": 96 },
      "promptTemplate": "{phaseName} phase banner, {theme.artStyle.promptSuffix}, decorative header, horizontal",
      "negativePrompt": "text, letters, actual words",
      "textOverlay": true,
      "perPhase": true,
      "phases": ["Draw", "Initiative", "Placement", "Resolution", "Cleanup"],
      "usage": "Phase indicator banners in the game header"
    },
    "rulesCover": {
      "dimensions": { "width": 744, "height": 1039 },
      "promptTemplate": "rules book cover page, {theme.artStyle.promptSuffix}, ornate border, centered emblem, decorative frame, no text, parchment texture",
      "negativePrompt": "text, words, letters, numbers, photo, realistic face",
      "referenceImage": "gameBackground",
      "referenceWeight": 0.3,
      "usage": "Cover page for Rules Documentation (help content), print and online"
    },
    "cardTypeCornerIcons": {
      "dimensions": { "width": 48, "height": 48 },
      "promptTemplate": "{cardTypeName} symbol, {theme.artStyle.promptSuffix}, tiny emblem, clean silhouette, isolated, transparent background, miniature icon",
      "negativePrompt": "text, numbers, person, realistic, large, detailed background",
      "perType": true,
      "types": ["Character", "Apparel", "Weapon", "Consumable", "Action", "Talk", "Encounter", "Skill", "MagicEffect"],
      "renderSize": "24x24 on screen, 32x32 in print",
      "tintByType": true,
      "usage": "Card face corner suit icons (top-left, top-right, bottom-right) for quick type identification when fanned/stacked"
    }
  },
  "generationOrder": "gameBackground FIRST, then all other assets use gameBackground as img2img reference",
  "referenceStrategy": {
    "anchor": "gameBackground",
    "referenceWeight": 0.25,
    "note": "Background generates first. Card backs, covers, banners, and pot/bar backgrounds use the gameBackground as an img2img reference at low weight to maintain visual palette and texture consistency across all theme assets."
  },
  "regenerateOn": "theme-change",
  "storage": "~/Gallery/CardGame/deckAssets/{themeId}/"
}
```

**Generation triggers:**
- New theme selected → generate all deck assets in batch
- Never regenerated during gameplay (stable UI elements)
- Can be regenerated manually via Batch Reimage → "Reimage Deck Assets"

**Generation order (reference chain):**

The `gameBackground` generates **first** and becomes the visual anchor for the entire theme. All subsequent assets use it as an `img2img` reference at low weight (0.2–0.3) to maintain palette, texture, and atmospheric consistency:

```
gameBackground (generates first, 3 variants)
    ├── potBackground       (ref weight 0.25)
    ├── actionBarBackground (ref weight 0.25)
    ├── titleBanner         (ref weight 0.2)
    ├── phaseHeaders ×5     (ref weight 0.2)
    └── rulesCover          (ref weight 0.3)
```

The `cardBack` images (Config 1) also use the `gameBackground` as a reference — see [Card Backs](#config-1-card-backs-sdconfigcardback). This ensures the card backs, board background, pot, action bar, and help cover all feel like they belong to the same visual family without being identical.

Small icons (d20, stat, need, action, outcome) do **not** use the background reference — they are too small for img2img to help and need crisp isolated shapes instead.

**Asset count per theme:**
| Asset Category | Count | Total Images |
|---------------|-------|-------------|
| Backgrounds (game, pot, action bar) | 3 types | 5 (3 game bg variants + pot + bar) |
| Title banner | 1 | 1 |
| d20 icon | 1 | 1 |
| Stat icons | 6 (STR, AGI, END, INT, MAG, CHA) | 6 |
| Need icons | 5 (HP, Energy, Hunger, Morale, AP) | 5 |
| Action type icons | 9 | 9 |
| Outcome icons | 7 | 7 |
| Phase headers | 5 | 5 |
| Rules cover | 1 | 1 |
| Card type corner icons | 9 (per card subtype) | 9 |
| **Total** | | **49 images** |

**Text overlay assets:** Title banner, phase headers, and rules cover are generated as decorative backgrounds only (no text in the SD prompt). Actual text (theme name, phase name, "Rules" title) is rendered as a vector overlay by the client, ensuring crisp text at any resolution and correct spelling.

**Fallback:** Before deck assets are generated, the game uses built-in SVG icon defaults and solid-color backgrounds. These look functional but unstyled. Once the themed assets generate, they replace the defaults with smooth fade transitions.

### AI Config Storage (`Ux7/media/cardGame/`)

SD configs, LLM prompt templates, and chat configs are stored as **normal AM7 objects** — they are NOT snapshotted with the deck. Decks reference configs by name; the game loads them fresh each session. This means config improvements, prompt tuning, and model changes benefit all games without re-snapshotting.

All default templates ship under `AccountManagerUx7/media/cardGame/` and are imported into the AM7 server on first use. Users can then customize copies through the deck builder UI.

#### Directory Layout

```
AccountManagerUx7/media/cardGame/
├── sd/
│   ├── {deckName}.cardBack.sdConfig.json
│   ├── {deckName}.cardStyle.sdConfig.json
│   ├── {deckName}.charPerson.sdConfig.json
│   ├── {deckName}.animal.sdConfig.json
│   ├── {deckName}.item.sdConfig.json
│   └── {deckName}.afterAction.sdConfig.json
├── prompts/
│   ├── {deckName}.narrator.promptConfig.json
│   ├── {deckName}.cardStyleComposer.promptConfig.json
│   ├── {deckName}.combatEval.promptConfig.json
│   ├── {deckName}.interactionEval.promptConfig.json
│   ├── {deckName}.aiOpponent.promptConfig.json
│   └── {deckName}.gmEncounter.promptConfig.json
└── chat/
    ├── {deckName}.narrator.chatConfig.json
    ├── {deckName}.playerChat.chatConfig.json
    └── {deckName}.cardStyleComposer.chatConfig.json
```

#### Naming Convention

All config files follow: **`{deckName}.{purpose}.{configType}.json`**

| Segment | Description | Examples |
|---------|-------------|----------|
| `{deckName}` | Theme/deck identifier, kebab-case | `high-fantasy`, `sci-fi`, `post-apoc` |
| `{purpose}` | What this config is used for | `cardBack`, `narrator`, `playerChat`, `charPerson` |
| `{configType}` | AM7 schema type | `sdConfig`, `promptConfig`, `chatConfig` |

**Examples:**
```
high-fantasy.cardBack.sdConfig.json        → SD config for generating High Fantasy card backs
high-fantasy.narrator.promptConfig.json    → LLM system prompt for the High Fantasy narrator
high-fantasy.narrator.chatConfig.json      → Chat/model settings for narrator LLM calls
sci-fi.charPerson.sdConfig.json            → SD config for Sci-Fi character portraits
post-apoc.aiOpponent.promptConfig.json     → LLM prompt for AI opponent decisions in Post-Apoc
```

#### AM7 Object Storage

When imported to the server, configs are stored as standard AM7 objects under a per-user game config directory:

```
/{user}/Game/v2/configs/{deckName}/
├── olio.sd.config       → cardBack, cardStyle, charPerson, animal, item, afterAction
├── olio.llm.promptConfig → narrator, cardStyleComposer, combatEval, interactionEval, aiOpponent, gmEncounter
└── olio.llm.chatConfig   → narrator, playerChat, cardStyleComposer
```

Each object's `name` field uses the same `{deckName}.{purpose}` convention for lookup:
```json
{
  "schema": "olio.sd.config",
  "name": "high-fantasy.charPerson",
  "groupPath": "/home/steve/Game/v2/configs/high-fantasy",
  "...config fields..."
}
```

#### Config Inventory

**SD Configs (`olio.sd.config`)** — 7 per deck theme:

| Purpose | Name Suffix | Used For |
|---------|-------------|----------|
| `cardBack` | Card back images | Once per theme, 8 type backs |
| `cardStyle` | Frame/divider/icon generation | Once per theme, SD follow-up to LLM style |
| `charPerson` | Character portraits | On equip change, level-up, manual |
| `animal` | Creature/animal images | On encounter creation, manual |
| `item` | Weapons, apparel, consumables, skills, magic | On card creation, manual |
| `deckAssets` | Backgrounds, icons, titles, UI elements | Once per theme, ~39 images |
| `afterAction` | Post-resolution scene images | Each round's resolution |

**Prompt Configs (`olio.llm.promptConfig`)** — 6 per deck theme:

| Purpose | Name Suffix | Used For |
|---------|-------------|----------|
| `narrator` | Narrator system prompt | Round narration (all 5 trigger points) |
| `cardStyleComposer` | Card style LLM composition | Generating the cardStyleDef JSON |
| `combatEval` | Combat outcome evaluation | LLM-enhanced resolution descriptions |
| `interactionEval` | Chat/interaction evaluation | concludeChat → interaction record |
| `aiOpponent` | AI opponent decision-making | Mode 1: AI selects stacks |
| `gmEncounter` | GM encounter selection | Mode 2: GM shapes encounters |

**Chat Configs (`olio.llm.chatConfig`)** — 3 per deck theme:

| Purpose | Name Suffix | Used For |
|---------|-------------|----------|
| `narrator` | Narrator LLM call settings | Model, temperature, token limits for narration |
| `playerChat` | Player↔NPC chat settings | Talk card conversations |
| `cardStyleComposer` | Style composition LLM settings | Model/settings for card style generation |

#### Config Reference in Theme

The deck theme config references configs **by name**, not by embedding them:

```json
{
  "themeId": "high-fantasy",
  "name": "High Fantasy",

  "aiConfigs": {
    "sdConfigs": {
      "cardBack": "high-fantasy.cardBack",
      "cardStyle": "high-fantasy.cardStyle",
      "charPerson": "high-fantasy.charPerson",
      "animal": "high-fantasy.animal",
      "item": "high-fantasy.item",
      "deckAssets": "high-fantasy.deckAssets",
      "afterAction": "high-fantasy.afterAction"
    },
    "promptConfigs": {
      "narrator": "high-fantasy.narrator",
      "cardStyleComposer": "high-fantasy.cardStyleComposer",
      "combatEval": "high-fantasy.combatEval",
      "interactionEval": "high-fantasy.interactionEval",
      "aiOpponent": "high-fantasy.aiOpponent",
      "gmEncounter": "high-fantasy.gmEncounter"
    },
    "chatConfigs": {
      "narrator": "high-fantasy.narrator",
      "playerChat": "high-fantasy.playerChat",
      "cardStyleComposer": "high-fantasy.cardStyleComposer"
    }
  }
}
```

#### Theme Inheritance for Configs

Themes that extend a parent inherit all config references. Override only specific ones:

```json
{
  "themeId": "bright-medieval",
  "extends": "dark-medieval",
  "aiConfigs": {
    "sdConfigs": {
      "charPerson": "bright-medieval.charPerson"
    }
  }
}
```

Here `bright-medieval` only ships one custom SD config (for brighter character portraits). All other configs resolve to `dark-medieval.*` via inheritance.

#### Config Customization (Deck Builder Step)

During deck building (between Steps 1 and 6), the player can optionally open a **Config Editor** to view and customize any AI config for the selected theme. Changes are saved as user-specific copies, leaving the default templates untouched.

```
┌────────────────────────────────────────────────────┐
│  CONFIG EDITOR (accessible from deck builder)       │
├────────────────────────────────────────────────────┤
│                                                     │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │ SD Configs  │ │ Prompts     │ │ Chat/Model  │  │
│  │ (6)         │ │ (6)         │ │ (3)         │  │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘  │
│         │               │               │          │
│  ┌──────▼──────────────────────────────────────┐   │
│  │ Selected: high-fantasy.charPerson (sdConfig)│   │
│  ├─────────────────────────────────────────────┤   │
│  │ Prompt Template:                             │   │
│  │ ┌─────────────────────────────────────────┐ │   │
│  │ │ {race} {gender}, {physicalDescription}, │ │   │
│  │ │ {apparelDescription}, portrait,         │ │   │
│  │ │ {theme.artStyle.promptSuffix}, card art │ │   │
│  │ └─────────────────────────────────────────┘ │   │
│  │ Negative Prompt:                             │   │
│  │ ┌─────────────────────────────────────────┐ │   │
│  │ │ deformed, blurry, text, watermark ...   │ │   │
│  │ └─────────────────────────────────────────┘ │   │
│  │ Dimensions: [512] × [768]                   │   │
│  │ CFG Scale: [7.5]  Steps: [30]               │   │
│  │ Denoising: [0.75]  Seed: [_____]           │   │
│  │                                              │   │
│  │ [Preview] [Reset to Default] [Save Copy]    │   │
│  └──────────────────────────────────────────────┘   │
│                                                     │
│  [Use Defaults]              [Save All & Continue]  │
└────────────────────────────────────────────────────┘
```

**Customization flow:**
1. Player clicks "Customize AI Configs" in the deck builder
2. Three tabs: SD Configs, Prompts, Chat/Model — each lists configs for the current theme
3. Selecting a config loads the full AM7 object into an editable form (uses existing `formDef.js` definitions for `olio.sd.config`, `olio.llm.promptConfig`, `olio.llm.chatConfig`)
4. **Preview** button generates a sample output (SD: generates a test image; Prompt: shows rendered prompt with sample data; Chat: shows model/settings summary)
5. **Save Copy** creates a user-owned copy: `{deckName}.{purpose}.custom` stored under the user's config directory. The theme's `aiConfigs` reference is updated to point to the custom copy.
6. **Reset to Default** reverts to the shipped template
7. **Save All & Continue** returns to the deck builder with all config choices locked in

**Custom config naming:** User copies append `.custom` or a user-chosen suffix:
```
high-fantasy.charPerson.custom.sdConfig.json
high-fantasy.narrator.v2.promptConfig.json
```

#### Config Loading at Runtime

When a game session starts, configs are loaded by name from the AM7 server:

```javascript
// Load SD config by theme reference name
async function loadSdConfig(configName) {
    // configName = "high-fantasy.charPerson" or "high-fantasy.charPerson.custom"
    const configs = await am7client.list("olio.sd.config", configDir.objectId, null, 0, 0);
    return configs.find(c => c.name === configName);
}

// Load all AI configs for a theme
async function loadThemeAiConfigs(themeConfig) {
    const ai = themeConfig.aiConfigs;
    return {
        sd: {
            cardBack: await loadSdConfig(ai.sdConfigs.cardBack),
            cardStyle: await loadSdConfig(ai.sdConfigs.cardStyle),
            charPerson: await loadSdConfig(ai.sdConfigs.charPerson),
            animal: await loadSdConfig(ai.sdConfigs.animal),
            item: await loadSdConfig(ai.sdConfigs.item),
            afterAction: await loadSdConfig(ai.sdConfigs.afterAction)
        },
        prompts: {
            narrator: await loadPromptConfig(ai.promptConfigs.narrator),
            // ... etc
        },
        chat: {
            narrator: await loadChatConfig(ai.chatConfigs.narrator),
            // ... etc
        }
    };
}
```

Since configs are loaded fresh (not snapshotted), prompt tuning, model upgrades, or SD parameter changes take effect on the next game session without any re-snapshot.

### Generation Priority Queue

When multiple images need generation, the queue is ordered by config priority:

```
Priority 1a: deckAssets.gameBackground — generates FIRST, visual anchor for the theme
Priority 1b: deckAssets (remaining) — covers, icons, titles, banners (ref gameBackground)
Priority 2:  cardStyle frame elements (needed before any card can render properly)
Priority 3:  cardBack images (ref gameBackground for palette consistency)
Priority 4:  charPerson — player character portrait (most visible card)
Priority 5:  charPerson — opponent/NPC portraits
Priority 6:  item — equipped weapons/apparel
Priority 7:  encounter — as drawn from deck
Priority 8:  item — consumables, skills, magic effects in hand
Priority 9:  animal — creature encounter images
Priority 10: afterAction — generated on demand after resolution
```

The `gameBackground` at Priority 1a is the **visual anchor** — it establishes palette and texture for the theme. Priorities 1b and 3 use it as an img2img reference to maintain visual consistency across all board surfaces and card backs.

Max concurrent SD generations: **2** (configurable per server capacity). Queue is FIFO within each priority level.

### Generation Flow

```
1. CARD CREATED (new card enters game)
   └─ Card renders immediately with placeholder image
   └─ Image generation request queued

2. PLACEHOLDER DISPLAY
   └─ Type-colored rectangle with card type icon
   └─ Card name in bold text overlay
   └─ Subtle "generating..." indicator (pulsing border)

3. GENERATION REQUEST (routed by card type)
   └─ Character:  POST /rest/olio/charPerson/{id}/reimage
   └─ Apparel:    POST /rest/olio/apparel/{id}/reimage (mannequin pipeline)
   └─ Animal:     POST /rest/olio/animal/{id}/reimage
   └─ All others: POST /rest/game/asset/generate (weapons, consumables, actions, skills, magic, encounters)

4. IMAGE STORAGE
   └─ All card game images stored under ~/Gallery/CardGame/{configName}/
       Characters:   ~/Gallery/CardGame/charPerson/{characterName}/
       Apparel:      ~/Gallery/CardGame/apparel/{apparelName}/
       Animals:      ~/Gallery/CardGame/animal/{creatureName}/
       Weapons:      ~/Gallery/CardGame/item/{itemName}/
       Consumables:  ~/Gallery/CardGame/item/{itemName}/
       Actions:      ~/Gallery/CardGame/action/{themeId}/{actionType}.png
       Encounters:   ~/Gallery/CardGame/encounter/{encounterName}/
       Skills:       ~/Gallery/CardGame/skill/{skillName}/
       Magic:        ~/Gallery/CardGame/magicEffect/{spellName}/
       Card Backs:   ~/Gallery/CardGame/cardBack/{themeId}/
       Card Style:   ~/Gallery/CardGame/cardStyle/{themeId}/
       Deck Assets:  ~/Gallery/CardGame/deckAssets/{themeId}/

5. IMAGE READY
   └─ Server pushes notification via WebSocket (game.asset.ready)
   └─ Client fetches image: /thumbnail/{path}/{size}
   └─ Card re-renders with actual image (smooth fade transition)
   └─ Image cached in service worker for future sessions

6. SUCCESSIVE GENERATION
   └─ Cards generated in priority order (see Generation Priority Queue above)
   └─ Max concurrent generations: 2 (to avoid overloading SD pipeline)
   └─ Queue managed client-side with server-side throttling
```

### Placeholder Design (Per Card Type)

| Card Type | Placeholder Background | Placeholder Icon | Border Style |
|-----------|----------------------|-----------------|-------------|
| Character | Gold gradient | Silhouette figure | Gold, pulsing |
| Apparel | Silver gradient | Shield outline | Silver, pulsing |
| Item | Green gradient | Crossed swords outline | Green, pulsing |
| Action | Red gradient | Lightning bolt outline | Red, pulsing |
| Talk | Blue gradient | Speech bubble outline | Blue, pulsing |
| Encounter | Purple gradient | Question mark | Purple, pulsing |
| Skill | Orange gradient | Star outline | Orange, pulsing |
| Magic Effect | Teal gradient | Spiral outline | Teal, pulsing |

All card types use pulsing borders during generation. Action and Talk cards generate themed illustrations during deck build (see Config 5 `action` subtype), so they also start as placeholders before their images are ready.

### Image Sizing

| Context | Image Size | Aspect Ratio |
|---------|-----------|-------------|
| Card face (in-hand view) | 256×384 | 2:3 (standard card ratio) |
| Card face (zoomed/detail) | 512×768 | 2:3 |
| Card thumbnail (stack view) | 64×96 | 2:3 |
| Print resolution | 744×1039 | 2:3 (at 300 DPI for 2.5"×3.5") |

### Character Reimage on Equip

**When a player equips or unequips Apparel, Weapon, or Skill cards, the Character card portrait is automatically regenerated** to reflect the current loadout. This creates a visual progression — a character who starts in rags with a stick looks different from the same character in plate armor wielding a flaming sword.

**Reimage trigger events:**
- Equip an Apparel card → reimage (new armor/clothing visible)
- Equip a Weapon card → reimage (weapon in hand)
- Learn a Magic skill type → reimage (visual indicator of magical affinity — e.g., glowing eyes for Psionic, dark aura for Undead, radiant hands for Imperial)
- Level up (stat increase) → reimage (character looks more confident/powerful)

**SD prompt construction for character reimage:**
```
Base: "{race} {gender}, {physicalDescription}"
+ Apparel layer: "wearing {body.name}, {head.name}, {feet.name}, {back.name}"
+ Weapon layer: "holding {handR.name}" or "wielding {handL.name} and {handR.name}"
+ Skill layer: "{imperialGlow}" or "{undeadAura}" or "{psionicEyes}" (if magic skills equipped)
+ Style: "portrait, fantasy RPG card art, {deckTheme.artStyle}"
```

**Flow:**
1. Player equips a new card → equipment change detected
2. Reimage request queued with updated prompt (incorporating all currently equipped cards)
3. Character card shows current portrait with a subtle shimmer/pulse border indicating generation in progress
4. New portrait arrives → smooth crossfade from old to new portrait
5. Previous portrait is NOT discarded — it's cached. Player can browse portrait history and select a preferred version.

**Batch suppression:** If multiple equip changes happen in rapid succession (e.g., equipping a full set of loot after a big encounter), reimage requests are debounced — only the final loadout triggers generation. 500ms debounce timer.

**RL / Print impact:** When exporting cards for print, the most recent generated portrait is used. If a reimage is pending, the export waits or uses the last completed portrait.

### Reimage (Manual Regenerate)

Players can also manually request re-generation of any card's image:
- Online: Click reimage button on card detail view
- Uses existing `/rest/olio/{type}/{id}/reimage/false` endpoint
- Shows spinner overlay during generation (same as current `cardGame.js` reimage)
- New image replaces old one with fade transition

### Batch Reimage

Players can regenerate images for multiple cards at once — either the entire deck or filtered by card type.

**UI options:**
- **Reimage All** — Regenerate every card in the current deck. Queues all cards through the priority system.
- **Reimage by Type** — Select one or more card types (Character, Apparel, Item, Encounter, Skill, Magic Effect) and regenerate only those. Useful after a theme or art style change.
- **Reimage Card Backs** — Regenerate all 8 type-specific card back images. Triggered automatically on theme change, but can also be done manually.

**Batch flow:**
```
1. Player selects scope (all, by type, or card backs)
2. Client builds queue of card IDs to regenerate
3. Cards queued in priority order (same as generation priority queue)
4. Progress bar shows: "Regenerating 12/47 cards..."
5. Each card updates in-place as its image arrives (fade transition)
6. Cards not yet regenerated continue showing their current image (not placeholder)
7. Cancel button available — stops queue, keeps whatever has been generated
```

**Endpoint:**
```
POST /rest/game/v2/reimage/batch
Body: {
  "scope": "all" | "type" | "backs",
  "cardTypes": ["character", "apparel"],  // only if scope=type
  "themeId": "dark-medieval"
}
→ Returns: { "queued": 47, "estimatedBatchId": "batch-uuid" }

GET /rest/game/v2/reimage/batch/{batchId}/status
→ Returns: { "total": 47, "completed": 12, "failed": 0, "inProgress": 2 }
```

**WebSocket events for batch progress:**
| Event | Payload |
|-------|---------|
| `game.v2.reimage.batch.progress` | `{ batchId, total, completed, currentCardId }` |
| `game.v2.reimage.batch.complete` | `{ batchId, total, succeeded, failed }` |
| `game.v2.reimage.card.ready` | `{ cardId, imageUrl }` (per-card, same as single reimage) |

### Image Generation Queue Manager

During initial deck build, the full card pool can produce **60–90+ images** across all configs (deck assets, card backs, card style, characters, apparel, items, actions, encounters, skills, magic effects). The queue manager gives the player full control over this process.

**Queue Manager UI:**

```
+------------------------------------------------------------------+
|  IMAGE GENERATION QUEUE                          [⏸ Pause] [⏹ Stop] |
+------------------------------------------------------------------+
|  Progress: 23 / 87 images   ████████░░░░░░░░░░  26%              |
|  Currently generating: "Iron Sword" (weapon)     [Spinner]        |
|  Next up: "Chainmail Vest" (apparel/mannequin)                    |
+------------------------------------------------------------------+
|  Filter: [All] [Pending] [Completed] [Failed]    [🔍 Search]     |
+------------------------------------------------------------------+
|  ┌──────────────────────────────────────────────────────────────┐ |
|  │ ✅ gameBackground (1/3)      [Preview] [Redo]               │ |
|  │ ✅ gameBackground (2/3)      [Preview] [Redo]               │ |
|  │ ✅ gameBackground (3/3)      [Preview] [Redo]               │ |
|  │ ✅ cardBack — Character      [Preview] [Redo]               │ |
|  │ ✅ cardBack — Apparel        [Preview] [Redo]               │ |
|  │ ⏳ Iron Sword (weapon)       [Preview] [Skip] [Redo]        │ |
|  │ ⬚  Chainmail Vest (apparel)  [Skip] [Move Up] [Move Down]  │ |
|  │ ⬚  Health Potion (consumable)[Skip] [Move Up] [Move Down]  │ |
|  │ ❌ Wolf Pack (animal) FAILED [Retry] [Skip] [View Error]    │ |
|  │ ...                                                          │ |
|  └──────────────────────────────────────────────────────────────┘ |
+------------------------------------------------------------------+
|  [▶ Resume]  [↻ Retry All Failed]  [⏭ Skip All Remaining]       |
+------------------------------------------------------------------+
```

**Queue controls:**

| Control | Action |
|---------|--------|
| **Pause** | Suspend queue processing. Current generation finishes, no new ones start. |
| **Stop** | Abort all pending items. Current generation finishes. Queue can be restarted later. |
| **Resume** | Continue processing from where paused/stopped. |
| **Skip** (per item) | Remove item from queue. Card uses placeholder until manually reimaged later. |
| **Redo** (per item) | Re-queue a completed image for regeneration. Old image preserved until new one arrives. |
| **Retry** (failed item) | Re-attempt a failed generation with same parameters. |
| **Retry All Failed** | Re-queue all failed items at once. |
| **Move Up / Move Down** | Reorder pending items in the queue (within same priority tier). |
| **Preview** | Opens full-size image preview of a completed item. |
| **View Error** | Shows the server error message for a failed generation. |
| **Skip All Remaining** | Mark all pending items as skipped. Game can start immediately with placeholders. |

**Queue persistence:** The queue state is saved to the game session. If the player closes the browser and returns, the queue resumes from where it stopped. Completed images are already stored — only pending items need to resume.

**During gameplay:** The queue manager is accessible from the settings/menu panel as a sidebar. New images generated during gameplay (reimage, encounter draws) are appended to the bottom of the queue and processed in the background.

### Setup Test & Preview

On **initial configuration** — before the full deck build generates 60–90+ images — the system runs an automated **test pass** that generates **one sample of every image type** and tests every unique LLM call, voice synthesis, and playback path. This catches config errors, missing API keys, SD model problems, and connectivity issues before committing to a large batch.

**Test pass items:**

| Test # | Category | What It Tests | Expected Output |
|--------|----------|--------------|-----------------|
| T1 | SD: deckAssets | Generate 1 gameBackground sample | 1920×1080 background image |
| T2 | SD: cardBack | Generate 1 card back (Character type) | 744×1039 card back image |
| T3 | SD: cardStyle | LLM compose style → generate frame overlay | JSON style def + frame image |
| T4 | SD: charPerson | Generate 1 character portrait | 512×768 portrait image |
| T5 | SD: apparel (mannequin) | Generate 1 mannequin apparel image | 512×768 mannequin image |
| T6 | SD: animal | Generate 1 creature image | 512×512 creature image |
| T7 | SD: item (weapon) | Generate 1 weapon image | 512×512 item image |
| T8 | SD: item (consumable) | Generate 1 consumable image | 512×512 item image |
| T9 | SD: item (action) | Generate 1 action card illustration | 512×768 action image |
| T10 | SD: item (skill) | Generate 1 skill emblem | 512×512 skill image |
| T11 | SD: item (magicEffect) | Generate 1 magic effect | 512×512 effect image |
| T12 | SD: cardTypeCornerIcons | Generate 1 corner icon (Character) | 48×48 corner icon |
| T13 | SD: afterAction | Generate 1 after-action scene | 768×512 scene image |
| T14 | LLM: narrator | Narrate a sample round event | Text narration response |
| T15 | LLM: combatEval | Evaluate a sample combat roll | JSON combat outcome |
| T16 | LLM: interactionEval | Evaluate a sample Talk outcome | JSON interaction outcome |
| T17 | LLM: aiOpponent | AI selects stacks for a sample game state | JSON stack selection |
| T18 | LLM: gmEncounter | Generate a sample encounter | JSON encounter definition |
| T19 | LLM: cardStyleComposer | Compose a sample card style | JSON style definition |
| T20 | Voice: narrator TTS | Synthesize a sample narration line | Audio playback ✓/✗ |
| T21 | Voice: chat TTS | Synthesize a sample chat response | Audio playback ✓/✗ |
| T22 | Chat: playerChat | Send a sample chat message and receive LLM response | Chat response text |
| T23 | Chat: streaming | Open WebSocket, stream a sample chat response | Streamed text chunks |

**Test UI:**

```
+------------------------------------------------------------------+
|  SETUP TEST & PREVIEW                                             |
|  Theme: High Fantasy          Config: high-fantasy.*              |
+------------------------------------------------------------------+
|  Running test pass... 14 / 23 complete                            |
|  ████████████████░░░░░░░░  61%                                   |
+------------------------------------------------------------------+
|  ✅ T1  SD: deckAssets (gameBackground)     3.2s  [Preview]      |
|  ✅ T2  SD: cardBack (Character)            2.8s  [Preview]      |
|  ✅ T3  SD: cardStyle (LLM + frame)         4.1s  [Preview]      |
|  ✅ T4  SD: charPerson                      3.5s  [Preview]      |
|  ✅ T5  SD: apparel (mannequin)             3.8s  [Preview]      |
|  ❌ T6  SD: animal                          FAIL  [View Error] [Retry] |
|         Error: SD model not found "sdXL_v10VAEFix.safetensors"   |
|  ✅ T7  SD: item (weapon)                   2.9s  [Preview]      |
|  ...                                                              |
|  ✅ T14 LLM: narrator                       1.2s  [Preview]      |
|  ✅ T15 LLM: combatEval                     0.8s  [Preview]      |
|  ⏳ T16 LLM: interactionEval               [Spinner]             |
|  ⬚  T17 LLM: aiOpponent                    pending               |
|  ...                                                              |
+------------------------------------------------------------------+
|  Summary: 14 passed, 1 failed, 8 pending                         |
|  [Retry Failed] [Skip Failed & Continue] [Abort Setup]           |
+------------------------------------------------------------------+
```

**Test behavior:**
- Runs automatically when the player clicks "Build Deck" for the first time with a new config
- Can also be triggered manually from the config editor: "Test All Configs" button
- **All test outputs are preview-only** — stored temporarily. They are NOT reused for the actual deck (the real batch generates fresh images with proper per-card prompts)
- **Preview button** opens the generated test image/text/audio in a modal for visual inspection
- **Retry** re-runs a single failed test with the same config
- **Skip Failed & Continue** proceeds to the full deck build, skipping configs that failed (cards of that type will use placeholders)
- If critical tests fail (T3 cardStyle, T14 narrator), the UI warns that the game experience will be degraded and recommends fixing before proceeding

**REST endpoint:**

```
POST /rest/game/v2/config/test
Body: { "themeId": "high-fantasy", "tests": ["all"] | ["sd", "llm", "voice", "chat"] }
→ Returns: { "testBatchId": "uuid" }

GET /rest/game/v2/config/test/{testBatchId}/status
→ Returns: { "tests": [ { "id": "T1", "status": "pass"|"fail"|"running"|"pending", "duration": 3200, "error": null, "previewUrl": "/thumbnail/..." } ] }
```

**WebSocket events:**
| Event | Payload |
|-------|---------|
| `game.v2.test.progress` | `{ testBatchId, testId, status, duration, error }` |
| `game.v2.test.complete` | `{ testBatchId, passed, failed, skipped }` |

---

## Print Specifications

### Card Dimensions

Standard poker card size: **2.5" × 3.5"** (63.5mm × 88.9mm)

At 300 DPI: **750 × 1050 pixels** (with 744×1039 safe area inside bleed)

### Print Layout

**PDF generation** via server endpoint:

```
GET /rest/game/cards/print/{deckId}?format=pdf
→ Returns PDF with cards laid out 3×3 per page (US Letter)
   or 3×3 per page (A4)
   with crop marks and bleed area
```

**Per page layout (US Letter 8.5" × 11"):**
```
+--+--+--+
|  |  |  |
+--+--+--+
|  |  |  |
+--+--+--+
|  |  |  |
+--+--+--+
= 9 cards per page, 0.25" margins, crop marks at corners
```

### Print Card Template

```
+---BLEED AREA (3mm beyond cut line)---+
| +---CUT LINE------------------------+ |
| | +---SAFE AREA (5mm inside cut)---+ | |
| | |                                | | |
| | | [Card content here]           | | |
| | |                                | | |
| | +--------------------------------+ | |
| +------------------------------------+ |
+----------------------------------------+
```

### Print Concerns

**Double-sided printing:**
- Card fronts printed on page A
- Card backs printed on page B (mirrored for alignment)
- Registration marks on each page for alignment
- Character cards: front has full card info, back has gold character-type design
- All other cards: front has card info, back has type-colored design

**Card back alignment:** Backs are printed in a separate pass. The PDF includes both front pages and back pages interleaved for duplex printing.

**Image quality for print:**
- SD-generated images upscaled to 300 DPI minimum
- Text rendered as vector (not rasterized) for crisp print
- Card borders and frames are SVG-based, rendered at print resolution
- Fallback: if SD image not yet generated, print placeholder with descriptive text

### Print-Ready Card Export

Players can export individual cards or full decks:

| Export Option | Format | Use Case |
|-------------|--------|----------|
| Full deck PDF | PDF (multi-page, duplex-ready) | Print entire deck at home or print shop |
| Individual card PNG | PNG (300 DPI) | Custom printing, insert into sleeves |
| Card sheet PNG | PNG (300 DPI, 3×3 grid) | Print single sheets |
| Tabletop Simulator export | JSON + PNGs | Import into TTS for virtual tabletop play |
| **ZIP Export** | ZIP (all assets + metadata) | Complete card archive, offline use, sharing |

### ZIP Export (Browser-Side)

All card images, card data, and theme assets are accumulated and packaged into a ZIP file **entirely in the browser** — no server-side ZIP generation needed. This uses a client-side ZIP library (e.g., JSZip or fflate).

**ZIP contents structure:**
```
cardGame-export-{themeId}-{timestamp}.zip
├── metadata.json                    // deck manifest: card list, theme config, game state
├── theme/
│   ├── theme.json                   // full theme config
│   └── style/
│       ├── cardStyleDef.json        // LLM-composed card style definition
│       ├── frame-overlay.png        // generated frame image
│       ├── divider.png              // generated divider
│       └── stat-icons/
│           ├── STR.png
│           ├── AGI.png
│           └── ...
├── backs/
│   ├── character-back.png
│   ├── apparel-back.png
│   ├── item-back.png
│   └── ...                          // all 8 type backs
├── cards/
│   ├── character/
│   │   ├── {charId}-front.png       // rendered card front at print resolution
│   │   ├── {charId}-portrait.png    // raw portrait image
│   │   └── {charId}.json            // card data (stats, name, etc.)
│   ├── apparel/
│   │   ├── {apparelId}-front.png
│   │   ├── {apparelId}-image.png
│   │   └── {apparelId}.json
│   ├── item/
│   │   └── ...
│   ├── action/
│   │   └── ...
│   ├── encounter/
│   │   └── ...
│   ├── skill/
│   │   └── ...
│   └── magic/
│       └── ...
├── sheets/
│   ├── fronts-page-1.png            // 3×3 card sheets for printing
│   ├── fronts-page-2.png
│   ├── backs-page-1.png
│   └── backs-page-2.png
└── reference-card.png               // quick reference card image
```

**Export flow:**
```
1. Player clicks "Export ZIP" in game menu
2. Client shows progress modal: "Preparing export..."
3. For each card in the deck:
   a. Render card front to canvas at print resolution (744×1039)
   b. Export canvas as PNG blob
   c. Fetch raw card image (portrait/item/etc.) from cache or server
   d. Serialize card data to JSON
   e. Add all three to the ZIP builder
4. Generate card sheet PNGs (3×3 grids of fronts and backs)
5. Include theme assets (style def, frame overlay, icons, backs)
6. Include metadata.json with full deck manifest
7. Finalize ZIP and trigger browser download
8. Progress: "Exporting card 23/91... Building sheets... Done!"
```

**Implementation notes:**
- Uses `canvas.toBlob()` for each card render → PNG conversion
- Card images already cached by the service worker — fetches from cache, not network
- If any card image hasn't been generated yet, the placeholder version is exported and the card's JSON includes `"imageStatus": "placeholder"`
- ZIP generation is streamed (fflate's streaming API) to avoid memory spikes on large decks
- Estimated ZIP size for a full 91-card 2-player deck: ~50-80 MB (300 DPI PNGs)
- Export includes a `metadata.json` that can be used to re-import the deck later:

```json
{
  "exportVersion": "1.0",
  "themeId": "dark-medieval",
  "exportDate": "2026-02-03T14:30:00Z",
  "cardCount": 91,
  "cards": [
    {
      "cardId": "uuid",
      "type": "character",
      "name": "Aelindra",
      "imageStatus": "generated",
      "frontFile": "cards/character/uuid-front.png",
      "imageFile": "cards/character/uuid-portrait.png",
      "dataFile": "cards/character/uuid.json"
    }
  ]
}
```

---

## Deck Theme Configuration

### Concept

Game card assets, character build modifications, encounter compositions, and visual styles are separated into a **Deck Theme Config** — a reusable configuration file that defines the flavor and balance of a game without changing the core rules. Multiple themes can exist, and players select one at game start.

This is analogous to a "card set" or "expansion" in physical card games. The rules stay the same; the cards, art style, stats, and encounter mix change.

### Theme Config Structure

```json
{
  "themeId": "dark-medieval",
  "name": "Dark Medieval",
  "version": "1.0",
  "description": "Gritty medieval setting with low magic and high mortality",

  "artStyle": {
    "promptSuffix": "dark fantasy, oil painting style, medieval, muted colors",
    "cardBorderStyle": "weathered-wood",
    "cardBackVariant": "parchment",
    "fontFamily": "IM Fell English",
    "colorPalette": {
      "character": "#8B7355",
      "apparel": "#707070",
      "item": "#2E5A2E",
      "action": "#8B2500",
      "talk": "#2F4F6F",
      "encounter": "#4A2060",
      "skill": "#8B5A00",
      "magic": "#2F6A6A"
    }
  },

  "characterBuild": {
    "statRange": [3, 18],
    "statPointBuy": 72,
    "startingHP": "40 + END * 5",
    "startingEnergy": "40 + END * 3",
    "startingMorale": "40 + CHA * 3",
    "skillSlots": 4,
    "equipSlots": ["head", "body", "handL", "handR", "feet", "ring", "back"],
    "availableRaces": ["HUMAN", "ELF", "DWARF", "ORC"],
    "availableAlignments": "all"
  },

  "starterDeck": {
    "apparel": { "min": 2, "max": 3, "rarity": "COMMON" },
    "weapons": { "min": 1, "max": 2, "rarity": "COMMON" },
    "consumables": [
      { "name": "Ration", "count": 2 },
      { "name": "Health Potion", "count": 1 },
      { "name": "Bandage", "count": 1 },
      { "name": "Torch", "count": 1 }
    ],
    "skills": { "min": 1, "max": 2 },
    "magicEffects": { "min": 0, "max": 1, "condition": "MAG >= 10 OR INT >= 12" }
  },

  "encounterDeck": {
    "threats": { "count": 15, "difficultyRange": [4, 12] },
    "events": { "count": 10 },
    "discoveries": { "count": 10 },
    "npcs": { "count": 8 },
    "lootItems": { "count": 12, "rarityWeights": { "COMMON": 50, "UNCOMMON": 30, "RARE": 15, "EPIC": 4, "LEGENDARY": 1 } },
    "skillCards": { "count": 6 },
    "magicEffects": { "count": 4 }
  },

  "balance": {
    "hungerDrainInterval": 3,
    "hungerDrainAmount": 10,
    "escalationPerRound": 2,
    "apFormula": "floor(END / 5) + 1",
    "apRange": [1, 5],
    "initiativeFormula": "1d20 + AGI",
    "potAnteRequired": true,
    "roundTimerSeconds": 60
  },

  "magicConfig": {
    "enabledSkillTypes": ["Imperial", "Undead", "Psionic"],
    "magicEffectPool": ["Fireball", "Ice Wall", "Mind Read", "Raise Thrall", "..."]
  },

  "encounterPool": [
    {
      "name": "Wolf Pack",
      "subtype": "threat",
      "difficulty": 8,
      "atk": 3, "def": 2, "hp": 25,
      "behavior": "Attacks lowest HP character first",
      "loot": ["Wolf Pelt", "Raw Meat"],
      "artPrompt": "pack of wolves, snarling, dark forest"
    }
  ],

  "itemPool": [
    {
      "name": "Iron Sword",
      "type": "weapon",
      "slot": "hand",
      "rarity": "COMMON",
      "atk": 4, "durability": 8,
      "damageType": "Slashing",
      "requires": { "STR": 8 },
      "artPrompt": "iron longsword, simple crossguard, worn leather grip"
    }
  ],

  "aiConfigs": {
    "sdConfigs": {
      "cardBack": "dark-medieval.cardBack",
      "cardStyle": "dark-medieval.cardStyle",
      "charPerson": "dark-medieval.charPerson",
      "animal": "dark-medieval.animal",
      "item": "dark-medieval.item",
      "afterAction": "dark-medieval.afterAction"
    },
    "promptConfigs": {
      "narrator": "dark-medieval.narrator",
      "cardStyleComposer": "dark-medieval.cardStyleComposer",
      "combatEval": "dark-medieval.combatEval",
      "interactionEval": "dark-medieval.interactionEval",
      "aiOpponent": "dark-medieval.aiOpponent",
      "gmEncounter": "dark-medieval.gmEncounter"
    },
    "chatConfigs": {
      "narrator": "dark-medieval.narrator",
      "playerChat": "dark-medieval.playerChat",
      "cardStyleComposer": "dark-medieval.cardStyleComposer"
    }
  }
}
```

### Theme Examples

| Theme | Setting | Magic Level | Mortality | Notes |
|-------|---------|------------|-----------|-------|
| **Dark Medieval** | Gritty feudal Europe | Low (Imperial only, rare) | High | Fewer consumables, tougher encounters |
| **High Fantasy** | Classic D&D-style | High (all 3 types common) | Medium | More magic effects, balanced encounters |
| **Sci-Psi** | Far-future space | Psionic only | Medium | No Imperial/Undead, tech items replace magic |
| **Undead Apocalypse** | Post-collapse necromancy | Undead dominant | Very High | Many threat encounters, undead loot |
| **Arena** | Gladiatorial combat | Minimal | High | All threats, no NPC/discovery, fast rounds |

### Theme Inheritance

Themes can extend a base theme, overriding only specific fields:

```json
{
  "themeId": "arena-magic",
  "extends": "arena",
  "name": "Arena (Magic Allowed)",
  "magicConfig": {
    "enabledSkillTypes": ["Imperial", "Psionic"],
    "magicEffectPool": ["Fireball", "Telekinetic Slam", "Ice Wall"]
  }
}
```

Unspecified fields are inherited from the parent theme. This allows rapid creation of theme variants.

### Theme Selection and Storage

- **Online:** Themes stored server-side as JSON resources under `/data/game/themes/`. Selected at game creation via `POST /rest/game/v2/newGame { themeId: "dark-medieval" }`. The server uses the theme config to build starter decks and encounter decks.
- **RL / Print:** Theme config determines which cards are included in the print PDF. Selecting a different theme generates a different set of printable cards.
- **Custom themes:** Players can create custom themes by copying and modifying an existing theme JSON. Custom themes stored per-user.
- **Campaign persistence:** The theme used for a campaign is locked at campaign start. Mid-campaign theme switching is not allowed (it would change the card pool).

### Asset Reuse Across Themes

Card images generated for one theme are tagged with the theme's `artStyle.promptSuffix`. If two themes share the same art style, generated images can be reused. If art styles differ, new images are generated. The image cache is keyed by `(cardId, artStyle)` to support this.

### Theme Card Pools

Complete card pool definitions for each theme are maintained in a separate file: **[cardGame-v2-themes.md](cardGame-v2-themes.md)**. Each theme defines ~80–90 cards across all types (apparel, weapons, consumables, skills, magic effects, threats, events, discoveries, NPCs) with full stats, art prompts, and theme-specific relabeling (e.g., Sci-Fi renames magic skill types to Energy Tech / Nano-Tech / Psi-Tech).

---

## Deck Builder & Snapshot Architecture

### Concept

Before a game session begins, the player goes through a **deck building process** that selects a theme, configures a character, draws starter cards, and assembles the encounter deck. Once a deck is "built," all card data is **snapshotted** — a frozen copy of every card's stats, images, and metadata is stored as the authoritative game record. The game session operates entirely from snapshot data, not from live AM7 objects.

This decoupling means:
- **Deletions** of source AM7 objects (characters, items, etc.) don't break in-progress or saved games
- **Edits** to source objects (stat rebalancing, description changes) don't retroactively alter ongoing games
- **Image regeneration** can update a card's visual without affecting stats (and vice versa)
- **Export/print** uses the snapshot, producing consistent output regardless of backend state

**What IS snapshotted (frozen at build time):**
- Character stats, needs, equipment slots
- Card stats (ATK, DEF, durability, effects, requirements)
- Card names, descriptions, art prompts
- Image references (URL/ID at time of snapshot)

**What is NOT snapshotted (referenced by name, loaded fresh):**
- SD configs (`olio.sd.config`) — loaded from `Ux7/media/cardGame/sd/` by name
- Prompt configs (`olio.llm.promptConfig`) — loaded from `Ux7/media/cardGame/prompts/` by name
- Chat configs (`olio.llm.chatConfig`) — loaded from `Ux7/media/cardGame/chat/` by name
- Theme config itself — the snapshot stores `themeId` + `themeVersion` as a reference

This split means AI config improvements (better prompts, upgraded models, tuned SD parameters) automatically benefit all game sessions without re-snapshotting, while card stats remain stable within a game.

### Deck Builder Flow

```
┌─────────────────────────────────────────────────────────┐
│                    DECK BUILDER UI                       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Step 1: SELECT THEME                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│  │ High     │ │ Sci-Fi   │ │ Post     │ ...            │
│  │ Fantasy  │ │          │ │ Apoc     │                │
│  └──────────┘ └──────────┘ └──────────┘               │
│                                                         │
│  Step 1b: CUSTOMIZE AI CONFIGS (optional)               │
│  [Customize AI Configs] button opens Config Editor      │
│  • SD Configs (7): cardBack, cardStyle, charPerson,     │
│    animal, item, deckAssets, afterAction                │
│  • Prompts (6): narrator, cardStyleComposer,            │
│    combatEval, interactionEval, aiOpponent, gmEncounter │
│  • Chat/Model (3): narrator, playerChat,                │
│    cardStyleComposer                                    │
│  Changes saved as user copies, defaults untouched       │
│                                                         │
│  Step 1c: RUN SETUP TEST (recommended on first build)   │
│  [Test All Configs] → automated 23-test pass            │
│  Generates 1 sample of each image type, tests LLM,     │
│  voice synth, chat streaming. See Setup Test & Preview. │
│                                                         │
│  Step 2: SELECT / CREATE CHARACTER                      │
│  ○ Pick from existing Olio characters (charPerson       │
│    picker — browse/search population, see portrait,     │
│    stats, current apparel at a glance)                  │
│  ○ Generate new random character (uses existing         │
│    CharacterUtil.randomPerson() — gender, race, name,   │
│    stats, alignment all randomized. Stored in           │
│    Olio Universe/World population, NOT adopted)         │
│  ○ Import from saved deck                               │
│                                                         │
│  Step 3: CONFIGURE CHARACTER                            │
│  [Name] [Race ▼] [Alignment ▼]                         │
│  Point-buy stats: STR[##] AGI[##] END[##]              │
│                   INT[##] MAG[##] CHA[##]              │
│  Remaining points: NN                                   │
│  (If existing character selected: stats are pre-filled  │
│  and can be adjusted for the card game session)         │
│                                                         │
│  Step 3b: THEME APPAREL SWAP                            │
│  All characters in the deck (player + NPCs) have their  │
│  current Olio apparel deactivated (inuse=false) and a   │
│  new theme-appropriate apparel set created.             │
│  See "Theme Apparel Swap" section below.                │
│                                                         │
│  Step 4: DRAW STARTER DECK                              │
│  Auto-draw based on theme starterDeck config:           │
│  • 2-3 Apparel cards (from character's new theme set)   │
│  • 1-2 Weapons (random from theme pool, COMMON rarity) │
│  • Fixed consumables (Rations, Potions, etc.)           │
│  • 1-2 Skills (random from pool)                        │
│  • 0-1 Magic Effects (if stat requirements met)         │
│  • 8 Action cards + 1 Talk card (always included)       │
│  [Re-draw] button to randomize again (before snapshot)  │
│                                                         │
│  Step 4b: ADD CUSTOM ITEMS (optional)                   │
│  [Add Item] → creates new items in the Olio            │
│  Universe/World. Items, weapons, consumables, and       │
│  apparel created here are stored as real AM7 objects    │
│  (not snapshot-only) so they persist for future decks.  │
│  Custom items are added to the theme pool for the       │
│  current deck and any future games using this theme.    │
│                                                         │
│  Step 5: BUILD ENCOUNTER DECK                           │
│  Auto-assembled from theme encounterDeck config:        │
│  • 15 Threats, 10 Events, 10 Discoveries, 8 NPCs       │
│  • Shuffled into single encounter deck                  │
│                                                         │
│  Step 6: REVIEW & BUILD                                 │
│  Preview all cards, adjust if desired, then:            │
│  [BUILD DECK] → snapshots all cards → game ready        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Character Picker (Step 2)

The character picker provides a visual browser for selecting or generating player characters. Characters do **not** need to be "adopted" into Olio — they just need to exist in the Universe/World population.

**Picker UI:**

```
+------------------------------------------------------------------+
|  SELECT CHARACTER                                                  |
|  [Search: ________] [Filter: Gender ▼] [Sort: Name ▼]            |
+------------------------------------------------------------------+
|  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐                 |
|  │Portrait│  │Portrait│  │Portrait│  │  + New  │                 |
|  │        │  │        │  │        │  │ Random  │                 |
|  │Aldric  │  │Brenna  │  │Caelum  │  │Character│                 |
|  │M Elf   │  │F Human │  │M Orc   │  │         │                 |
|  │STR:14  │  │STR:8   │  │STR:18  │  │[Generate│                 |
|  │AGI:12  │  │AGI:16  │  │AGI:7   │  │  ↻]     │                 |
|  └────────┘  └────────┘  └────────┘  └────────┘                 |
|  [Select]    [Select]    [Select]    [Generate]                   |
+------------------------------------------------------------------+
```

**Sources:**
- **Existing characters** — queried from the Olio Universe/World population (`GET /rest/olio/charPerson/list`)
- **New random character** — calls `CharacterUtil.randomPerson()` on the server. Gender, race, name, stats, alignment are all randomized. Created and stored in the Olio population directory. Player can re-roll repeatedly until they get a character they like.
- **Import from saved deck** — loads a character snapshot from a previous game save

**Endpoint:**
```
POST /rest/game/v2/character/random
Body: { "worldPath": "/Olio/Universes/My Grid Universe/Worlds/My Grid World" }
→ Returns: full charPerson record (stored in population, ready to use)
```

### Theme Apparel Swap (Step 3b)

When characters are added to a deck, their **existing Olio apparel is deactivated** and a new **theme-appropriate apparel set** is created. This is critical because:
- Character portraits are regenerated with the theme apparel (a fantasy knight shouldn't wear a sci-fi jumpsuit)
- Apparel cards in the deck must match the theme
- The mannequin images for apparel cards need the correct theme styling

**Swap process:**

```
FOR EACH character in deck (player + all NPCs/opponents):

1. DEACTIVATE current apparel
   └─ Set inuse=false on the character's current olio.apparel record
   └─ Set inuse=false on all child olio.wearable records
   └─ The old apparel is NOT deleted — it remains in the character's
      store for restoration after the game session

2. CREATE theme apparel set
   └─ ApparelUtil.constructApparel() with theme-appropriate outfit
   └─ Gender-aware: uses character's gender field to select
      male, female, or unisex wearable items
   └─ Layered per Olio wearable structure (see layer table below)
   └─ Set inuse=true on new apparel + all wearables
   └─ Store in character's olio.store.apparel list

3. REGISTER items in Olio Universe/World
   └─ New apparel/wearable records created as real AM7 objects
      under the world's apparel/wearables paths
   └─ Persists for future games with the same theme

4. GENERATE images
   └─ Character portrait reimaged with new apparel (sdConfig.charPerson)
   └─ Each apparel piece gets a mannequin image (mannequin pipeline)
```

**Apparel layer structure by gender:**

The Olio `WearLevelEnumType` defines a layering system from skin-contact to outermost:

| Level | Enum | Male Example (Fantasy) | Female Example (Fantasy) |
|-------|------|----------------------|------------------------|
| 4 | BASE | Underwear, undershirt | Underwear, bra, chemise |
| 5 | ACCENT | Belt, sash | Belt, corset, sash |
| 6 | SUIT | Pants, shirt, tunic | Skirt/pants, blouse, dress |
| 7 | GARNITURE | Buckles, buttons, ties | Ribbons, lace trim, brooches |
| 8 | ACCESSORY | Gloves, ring, bracelet | Gloves, ring, necklace, circlet |
| 9 | OVER | Vest, jerkin, chainmail | Vest, bodice, chainmail |
| 10 | OUTER | Cloak, overcoat | Cloak, mantle |
| 11 | FULL_BODY | Plate armor, full chain | Plate armor, full chain |

Each wearable also has **location** tags (from the `olio.wearable` `location` list): `head`, `torso`, `chest`, `leg`, `foot`, `hand`, `arm`, `waist`, `back`, `neck`, `finger`, etc.

**Theme outfit templates** define what a theme's apparel looks like at each layer:

```json
// In theme config: starterOutfit
{
  "starterOutfit": {
    "male": [
      "clothing:linen underclothes:4:m:torso+groin",
      "clothing:wool trousers:6:m:waist+leg",
      "clothing:linen shirt:6:m:torso+arm",
      "clothing:leather belt:5:m:waist",
      "clothing:leather boots:6:m:foot+ankle",
      "clothing:leather jerkin:9:m:torso+shoulder",
      "clothing:wool cloak:10:m:back+shoulder"
    ],
    "female": [
      "clothing:linen underclothes:4:f:torso+groin",
      "clothing:linen bra:4:f:chest+breast",
      "clothing:wool skirt:6:f:waist+leg+thigh",
      "clothing:linen blouse:6:f:torso+arm",
      "clothing:leather belt:5:f:waist",
      "clothing:leather boots:6:f:foot+ankle",
      "clothing:leather bodice:9:f:torso+chest",
      "clothing:wool cloak:10:f:back+shoulder"
    ]
  }
}
```

**Card image vs character portrait distinction:**
- **Apparel card image** (mannequin) — shows **only the specific garment** on a mannequin form (e.g., just the plate armor chest piece). Generated via the mannequin pipeline at the garment's specific wear level.
- **Character portrait** — shows the **full character** wearing ALL their equipped apparel layers combined. Generated via `sdConfig.charPerson` with the prompt built from all active wearables.

**Post-game restoration:**

When a game session ends (or the deck is deleted), the theme apparel can optionally be deactivated and the character's original apparel restored:

```
POST /rest/game/v2/apparel/restore/{characterId}
→ Sets theme apparel inuse=false
→ Sets original apparel inuse=true
→ Character reverts to their pre-game outfit in the Olio world
```

### Custom Item Creation (Step 4b)

During deck configuration, players can create new items that are stored as **real AM7 objects** in the Olio Universe/World — not as snapshot-only cards. This means custom items persist across games and can be reused in future decks.

**Creation flow:**

```
[Add Item] button
  ├── Select type: Weapon / Consumable / Apparel / Skill / Magic Effect
  ├── Fill in card fields (name, stats, effects, requirements)
  │   └── Uses existing formDef.js definitions for olio.item, olio.wearable, etc.
  ├── Item created as AM7 object under the Olio world's item/store paths
  ├── Card snapshot generated from the new item
  └── Added to current deck's card pool
```

**Storage:** Custom items are created under the Olio world's standard paths:
- Weapons/consumables: `{world}/Items/{itemName}`
- Apparel: `{world}/Apparel/{apparelName}` (with full wearable sublayer structure)
- Skills: linked to existing `olio.skill` records or new custom skills

**Endpoints:**
```
POST /rest/game/v2/item/create
Body: { "worldPath": "...", "type": "weapon", "name": "Flame Tongue", "stats": {...} }
→ Returns: created AM7 item record

POST /rest/game/v2/apparel/create
Body: { "worldPath": "...", "gender": "male", "wearables": [...], "name": "Dragon Scale Armor" }
→ Returns: created AM7 apparel record with full wearable sublayers
```

### Snapshot Data Model

When `[BUILD DECK]` is clicked, every card in the player's starter deck and the encounter deck is snapshotted into a `DeckSnapshot` object. This is the single source of truth for the game session.

```json
{
  "snapshotId": "snap-uuid",
  "createdAt": "2026-02-03T14:30:00Z",
  "updatedAt": "2026-02-03T14:30:00Z",
  "themeId": "high-fantasy",
  "themeVersion": "1.0",

  "aiConfigRefs": {
    "sdConfigs": {
      "cardBack": "high-fantasy.cardBack",
      "cardStyle": "high-fantasy.cardStyle",
      "charPerson": "high-fantasy.charPerson",
      "animal": "high-fantasy.animal",
      "item": "high-fantasy.item",
      "afterAction": "high-fantasy.afterAction"
    },
    "promptConfigs": {
      "narrator": "high-fantasy.narrator",
      "cardStyleComposer": "high-fantasy.cardStyleComposer",
      "combatEval": "high-fantasy.combatEval",
      "interactionEval": "high-fantasy.interactionEval",
      "aiOpponent": "high-fantasy.aiOpponent",
      "gmEncounter": "high-fantasy.gmEncounter"
    },
    "chatConfigs": {
      "narrator": "high-fantasy.narrator",
      "playerChat": "high-fantasy.playerChat",
      "cardStyleComposer": "high-fantasy.cardStyleComposer"
    }
  },

  "player": {
    "characterSnapshot": {
      "sourceObjectId": "am7-char-uuid",
      "snapshotVersion": 1,
      "name": "Aelindra",
      "race": "ELF",
      "alignment": "NEUTRAL_GOOD",
      "level": 1,
      "stats": { "STR": 10, "AGI": 14, "END": 12, "INT": 16, "MAG": 13, "CHA": 7 },
      "needs": { "hp": 100, "maxHp": 100, "energy": 76, "maxEnergy": 76, "hunger": 100, "morale": 61 },
      "skillSlots": 4,
      "equipSlots": ["head", "body", "handL", "handR", "feet", "ring", "back"],
      "portrait": {
        "imageId": "img-uuid",
        "imageUrl": "/thumbnail/path/256",
        "imageStatus": "generated",
        "generatedAt": "2026-02-03T14:30:05Z"
      }
    },
    "starterCards": [
      {
        "cardSnapshotId": "csnap-uuid",
        "sourceObjectId": "am7-item-uuid",
        "snapshotVersion": 1,
        "cardType": "apparel",
        "name": "Elven Silk Robes",
        "rarity": "COMMON",
        "stats": { "def": 2, "magDef": 4, "durability": 6, "maxDurability": 6 },
        "slot": "body",
        "requires": {},
        "special": "Magic Affinity: +1 to Magic Effect rolls",
        "artPrompt": "flowing elven silk robes, silver thread, forest motif",
        "image": {
          "imageId": "img-uuid",
          "imageUrl": "/thumbnail/path/256",
          "imageStatus": "placeholder",
          "generatedAt": null
        }
      }
    ]
  },

  "encounterDeck": [
    {
      "cardSnapshotId": "csnap-uuid",
      "sourceObjectId": "am7-encounter-uuid",
      "snapshotVersion": 1,
      "cardType": "encounter",
      "subtype": "threat",
      "name": "Dire Wolf Pack",
      "difficulty": 8,
      "stats": { "atk": 3, "def": 2, "hp": 25 },
      "behavior": "Attacks lowest HP character first",
      "loot": ["Wolf Pelt", "Raw Meat"],
      "artPrompt": "pack of wolves, snarling, moonlit forest",
      "image": {
        "imageId": null,
        "imageUrl": null,
        "imageStatus": "pending",
        "generatedAt": null
      }
    }
  ],

  "actionCards": [
    { "cardType": "action", "actionType": "ATTACK", "name": "Attack", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "DEFEND", "name": "Defend", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "FLEE", "name": "Flee", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "REST", "name": "Rest", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "USE_ITEM", "name": "Use Item", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "INVESTIGATE", "name": "Investigate", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "CRAFT", "name": "Craft", "snapshotVersion": 1 },
    { "cardType": "action", "actionType": "WATCH", "name": "Watch", "snapshotVersion": 1 },
    { "cardType": "talk", "name": "Talk", "snapshotVersion": 1 }
  ],

  "metadata": {
    "totalCards": 91,
    "imagesPending": 45,
    "imagesGenerated": 12,
    "imagesPlaceholder": 34
  }
}
```

### Snapshot Fields

Each card snapshot captures:

| Field | Purpose |
|-------|---------|
| `cardSnapshotId` | Unique ID for this snapshot record (not the source object ID) |
| `sourceObjectId` | Reference to the original AM7 object (nullable — action/talk cards have no AM7 source) |
| `snapshotVersion` | Incrementing version number, bumped on each re-snapshot |
| `cardType` | Card type identifier (character, apparel, item, action, etc.) |
| `stats` | Frozen stat block at time of snapshot |
| `image` | Image state: `imageId`, `imageUrl`, `imageStatus` (pending/placeholder/generated), `generatedAt` |
| `artPrompt` | The SD prompt used for image generation (preserved for re-generation) |

### Snapshot Lifecycle

```
SOURCE OBJECTS (AM7 backend)          SNAPSHOT (game session)
┌─────────────────────┐              ┌─────────────────────┐
│ olio.charPerson     │──snapshot──▶│ characterSnapshot   │
│ olio.item           │──snapshot──▶│ starterCards[]      │
│ olio.encounter      │──snapshot──▶│ encounterDeck[]     │
│ theme.actionCards    │──snapshot──▶│ actionCards[]       │
└─────────────────────┘              └─────────────────────┘
         │                                     │
     [may change,                        [frozen, game
      be deleted,                         reads ONLY
      rebalanced]                         from here]
                                               │
                                         ┌─────┴─────┐
                                         │ Game      │
                                         │ Session   │
                                         │ ─ ─ ─ ─  │
                                         │ Resolve   │
                                         │ Narrate   │
                                         │ Export    │
                                         │ Save     │
                                         └───────────┘
```

### Re-Snapshot

Individual cards can be **re-snapshotted** to pull in updates without rebuilding the entire deck. Two types of re-snapshot:

#### Image Re-Snapshot
Updates the card's image data without changing stats. Triggered by:
- Manual reimage (player clicks reimage on a card)
- Batch reimage (reimage all or by type)
- Character reimage on equip

```
POST /rest/game/v2/deck/{snapshotId}/resnap/image
Body: { "cardSnapshotId": "csnap-uuid" }

→ Regenerates image using stored artPrompt
→ Updates image fields in snapshot
→ Bumps snapshotVersion
→ Returns updated card snapshot
```

#### Stat Re-Snapshot
Pulls fresh stats from the source AM7 object into the snapshot. Triggered manually when a player wants to incorporate upstream changes (e.g., a card was rebalanced between sessions).

```
POST /rest/game/v2/deck/{snapshotId}/resnap/stats
Body: { "cardSnapshotId": "csnap-uuid" }

→ Reads current state of sourceObjectId from AM7
→ Updates stat fields in snapshot
→ Bumps snapshotVersion
→ Returns updated card snapshot with diff summary
```

**Safeguards:**
- If `sourceObjectId` has been deleted, stat re-snapshot fails gracefully with an error message. The snapshot retains its current stats.
- A diff summary is shown before confirming stat re-snapshot: "STR 10→12, DEF 3→2. Apply changes?"
- Stat re-snapshot is only available **between game sessions** (not mid-game). Image re-snapshot can happen anytime.

#### Full Deck Re-Snapshot
Re-snapshots all cards in the deck from their source objects. Useful when switching to a new version of a theme or after significant backend rebalancing.

```
POST /rest/game/v2/deck/{snapshotId}/resnap/all
Body: { "includeImages": true, "includeStats": true }

→ Iterates all cards, pulls fresh data
→ Cards with deleted sources are flagged but kept (use last snapshot)
→ Returns summary: { updated: 45, skipped: 3, deleted: 2, errors: 1 }
```

### Deck Storage

Snapshots are stored server-side as JSON documents, associated with the user and game session:

```
/data/game/v2/decks/{userId}/{snapshotId}.json
```

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v2/deck/build` | POST | Build deck from theme + character, creates snapshot |
| `GET /rest/game/v2/deck/{snapshotId}` | GET | Load a built deck snapshot |
| `GET /rest/game/v2/decks` | GET | List all user's built decks |
| `DELETE /rest/game/v2/deck/{snapshotId}` | DELETE | Delete a built deck |
| `POST /rest/game/v2/deck/{snapshotId}/resnap/image` | POST | Re-snapshot single card image |
| `POST /rest/game/v2/deck/{snapshotId}/resnap/stats` | POST | Re-snapshot single card stats |
| `POST /rest/game/v2/deck/{snapshotId}/resnap/all` | POST | Re-snapshot entire deck |
| `POST /rest/game/v2/deck/{snapshotId}/clone` | POST | Clone a deck (for variant builds) |

### Build → Play Flow

```
1. DECK BUILDER
   └─ Select theme, (optionally) customize AI configs, configure character,
      draw starter, assemble encounter deck
   └─ [BUILD DECK] → POST /rest/game/v2/deck/build
       Body: {
         themeId: "high-fantasy",
         character: { name, race, alignment, stats },
         aiConfigOverrides: { ... },  // only if user customized any configs
         starterSeed: 12345  // optional, for reproducible draws
       }
       → Snapshots card data (stats, names, art prompts)
       → Stores AI config references by name (NOT snapshotted)
       → Returns: { snapshotId, deck snapshot }

2. IMAGE GENERATION (can happen in background)
   └─ Load SD configs by name from aiConfigRefs
   └─ Queue all cards for image generation via priority system
   └─ Cards with "pending" imageStatus get generated
   └─ Snapshot image fields updated as images complete
   └─ Game can start before all images are ready (placeholders)

3. START GAME
   └─ POST /rest/game/v2/newGame
       Body: { snapshotId: "snap-uuid", mode: "opponent" | "gm" }
       → Server loads snapshot (frozen card stats)
       → Server loads AI configs fresh by name (prompts, chat, SD)
       → All card/stat logic reads from snapshot
       → All LLM/SD generation uses live configs

4. DURING GAME
   └─ All card references use cardSnapshotId, not sourceObjectId
   └─ Stat changes during game (damage, durability loss, etc.) are
      tracked as game-session deltas, not written back to snapshots
   └─ Session state = snapshot + deltas

5. SAVE GAME
   └─ POST /rest/game/v2/save
       Body: { snapshotId, sessionState (deltas), eventLog, round }
       → Snapshot is NOT modified — session deltas saved separately

6. END GAME / CAMPAIGN CONTINUE
   └─ Campaign mode: carry-over cards and stat gains written to
      a new snapshot (clone + apply deltas)
   └─ One-off mode: session state discarded, snapshot remains for replay
```

### Session Deltas vs Snapshot

During gameplay, the snapshot is **read-only**. All mutable state (damage taken, consumables used, durability reduced, cards gained/lost) is tracked as session deltas:

```json
{
  "sessionId": "sess-uuid",
  "snapshotId": "snap-uuid",
  "round": 7,
  "deltas": {
    "player": {
      "needs": { "hp": -35, "energy": -20, "hunger": -30, "morale": -10 },
      "durabilityChanges": {
        "csnap-weapon-uuid": -3,
        "csnap-armor-uuid": -1
      },
      "consumablesUsed": ["csnap-potion-uuid", "csnap-ration-uuid"],
      "cardsGained": [
        { "source": "encounter-loot", "cardSnapshotId": "csnap-new-uuid", "...": "..." }
      ],
      "cardsLost": ["csnap-ration2-uuid"],
      "equipChanges": {
        "handR": "csnap-new-sword-uuid",
        "body": "csnap-new-armor-uuid"
      }
    },
    "encounterDeckPosition": 12,
    "discardPile": ["csnap-enc1-uuid", "csnap-enc2-uuid"]
  }
}
```

**Resolving current state** at any point:
```
currentHP = snapshot.player.characterSnapshot.needs.hp + deltas.player.needs.hp
currentDurability = snapshot.card.stats.durability + deltas.durabilityChanges[cardId]
isConsumed = deltas.consumablesUsed.includes(cardSnapshotId)
```

This means saving is lightweight (small delta object), loading is a merge of snapshot + deltas, and the snapshot itself remains a clean, reusable starting point.

---

## Online Implementation

### Architecture (REST-First, Same as cardGame.js)

The v2 client communicates exclusively through the AM7 REST API and WebSocket endpoints. It extends the existing `cardGame.js` infrastructure.

### New / Extended Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v2/newGame` | POST | Initialize a new card game session (generate starter decks, encounter deck) |
| `POST /rest/game/v2/draw` | POST | Draw card(s) from encounter deck |
| `POST /rest/game/v2/initiative` | POST | Roll initiative for all participants, returns position map |
| `POST /rest/game/v2/ante` | POST | Submit pot ante card(s) for the current round |
| `POST /rest/game/v2/placeStacks` | POST | Submit all action stacks for placement on the action bar |
| `POST /rest/game/v2/resolvePosition` | POST | Resolve a single action bar position (server-side dice + narration) |
| `POST /rest/game/v2/disrupt` | POST | Execute a mid-round disruption (insert, remove, modify) |
| `GET /rest/game/v2/state` | GET | Get full game state (hand, bar, pot, encounter, needs) |
| `POST /rest/game/v2/equip` | POST | Equip/unequip apparel or weapon (costs 1 AP as action stack) |
| `POST /rest/game/v2/swapSkill` | POST | Swap skill card into active slot |
| `POST /rest/game/v2/ai/placement` | POST | Get AI opponent's stack placement for the action bar (Mode 1) |
| `POST /rest/game/v2/ai/disruptResponse` | POST | Get AI response to a mid-turn disruption |
| `POST /rest/game/v2/gm/encounter` | POST | Get GM's encounter selection + stack placement (Mode 2) |
| `POST /rest/game/v2/pot/claim` | POST | Round winner claims all pot cards |
| `GET /rest/game/cards/print/{deckId}` | GET | Generate printable PDF of deck |
| `POST /rest/game/v2/trade` | POST | Execute trade between players |
| `POST /rest/game/v2/talk` | POST | Initiate Talk action (opens chat or resolves outcome) |

### WebSocket Events (Extend GameStreamHandler)

| Event | Direction | Payload |
|-------|-----------|---------|
| `game.v2.round.start` | Server → Client | Round number, encounter card drawn |
| `game.v2.initiative.result` | Server → Client | Initiative rolls, position map, turn order |
| `game.v2.ante.placed` | Server → Client | Player has anted card(s) to pot |
| `game.v2.placement.ready` | Server → Client | Player has placed all stacks on bar (ready to resolve) |
| `game.v2.bar.updated` | Server → Client | Action bar state changed (stacks placed, positions assigned) |
| `game.v2.position.resolving` | Server → Client | Current position being resolved (position number, owner) |
| `game.v2.position.resolved` | Server → Client | Position resolution results (damage, effects, narration text) |
| `game.v2.disruption` | Server → Client | Mid-round disruption occurred (insert/remove/modify + bar update) |
| `game.v2.item.dropped` | Server → Client | Item dropped to pot (critical hit/counter/fumble) |
| `game.v2.action.disabled` | Server → Client | Action stack disabled on bar (critical counter effect) |
| `game.v2.pot.updated` | Server → Client | Pot contents changed (ante, drop, loot added) |
| `game.v2.pot.claimed` | Server → Client | Round winner claimed pot, cards transferred |
| `game.v2.card.gained` | Server → Client | New card added to player's hand |
| `game.v2.card.lost` | Server → Client | Card consumed/destroyed/discarded |
| `game.v2.needs.update` | Server → Client | Need track changes |
| `game.v2.defeat` | Server → Client | Player/encounter defeated |
| `game.v2.asset.ready` | Server → Client | Image generation complete for a card |
| `game.v2.chat.open` | Server → Client | Talk card played, chat interface available |
| `game.v2.ai.ready` | Server → Client | AI opponent has placed stacks |
| `game.v2.pokerFace.update` | Server → Client | Poker Face emotion data update (if enabled) |

### Client Architecture

```
cardGame-v2.js
├── State management (gameState — hand, bar, pot, phase, needs, pokerFace)
├── Card rendering (Mithril.js components)
│   ├── CardFace(card) — renders front of any card type
│   ├── CardBack(cardType) — renders type-specific back
│   ├── CharacterStack(player) — sidebar character + equipment stack
│   ├── ActionBarPosition(position) — single bar slot with core + modifier cards
│   ├── ActionBar(stacks[]) — horizontal action bar with all positions
│   ├── PotView(pot) — center pot card pile with value display
│   ├── HandTray(cards[], filter) — type-filtered scrollable card tray
│   ├── NeedBars(needs) — HP/Energy/Hunger/Morale bars + AP display
│   └── PokerFaceWidget(pokerFace) — emotion emoji + banter level toggle
├── Phase UI
│   ├── DrawPhase — encounter reveal + ante collection
│   ├── InitiativePhase — roll animation, position assignment
│   ├── PlacementPhase — drag-and-drop stack building on action bar
│   ├── ResolutionPhase — per-position resolve with narration + disruption handling
│   └── CleanupPhase — pot claim, discard, durability, hunger tick
├── Drag-and-drop (card arrangement for placement phase)
├── Chat integration (reuses existing chat system)
├── Image pipeline (placeholder → SD generation → update)
├── Print export (trigger server PDF generation)
└── AI integration (Mode 1: opponent, Mode 2: GM)
```

### UI Layout (Online)

```
+------------------------------------------------------------------+
|  HEADER: Round # | Phase | Initiative | AP: 3/3 | Timer | ⚙      |
+------------------------------------------------------------------+
|              |                                    |                |
|  YOUR        |  ┌──────────────────────────────┐ |  OPPONENT /    |
|  CHARACTER   |  │      ROUND POT               │ |  CHARACTER     |
|  STACK       |  │   [💰] [🗡️] [🧪] = 3 cards  │ |  STACK         |
|  ┌────┐      |  └──────────────────────────────┘ |  ┌────┐        |
|  │Char│      |                                    |  │Char│        |
|  │Armor│     |  ACTION BAR (horizontal grid)     |  │Armor│       |
|  │Weapon│    |  ┌───┬───┬───┬───┬───┬───┬───┐   |  │Weapon│      |
|  └────┘      |  │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │   |  └────┘        |
|              |  │You│Opp│You│Opp│You│Opp│You│   |                |
|              |  └───┴───┴───┴───┴───┴───┴───┘   |                |
|  Needs Bars  |        ▲ [ACTIVE MARKER]          |  Opp Needs    |
|  HP  [████]  |  [Narration subtitle overlay]      |  HP  [████]   |
|  NRG [████]  |                                    |  NRG [████]   |
|  HNG [████]  |  ENCOUNTER CARD (if active)        |  HNG [████]   |
|  MRL [████]  |                                    |  MRL [████]   |
|              |  Event Log                         |                |
|  🎭 Poker    |                                    |  🎭 Poker     |
+--------------+------------------------------------+----------------+
|  YOUR HAND — sorted by type, drag to action bar                   |
|  [Actions ▼] [Consumables ▼] [Skills ▼] [Modifiers ▼] [Equip ▼] |
|  [Card] [Card] [Card] [Card] [Card] [Card] [Card]               |
+------------------------------------------------------------------+
```

### State Model

```javascript
// v2 game state
let gameState = {
    mode: 'opponent' | 'gm',
    round: 1,
    phase: 'draw' | 'initiative' | 'placement' | 'resolution' | 'cleanup',

    initiative: {
        rolls: {},                       // { playerId: { natural, modifier, total } }
        order: [],                       // playerIds sorted by initiative (winner first)
        positionMap: {},                 // { position: playerId }
    },

    actionBar: {
        totalPositions: 0,               // sum of all participants' AP + per-round threats
        stacks: [],                      // [{ position, ownerId, coreCards, modifiers, resolved, inserted, disabled }]
        currentPosition: 0,              // which position is being resolved
        marker: {                        // active position marker/token
            position: 0,                 // current marker position (0 = not started, -1 = beginning threat)
            state: 'idle',               // 'idle' | 'active' | 'transitioning' | 'complete'
            isThreat: false,             // true when marker is on a beginning/end threat position
        },
        disruptions: [],                 // pending inserts/removes/modifies
        beginningThreats: [],            // [{ threatCard, loot[], triggeredBy, resolved, outcome }]
        endThreats: [],                  // [{ threatCard, loot[], triggeredBy, hidden, resolved, outcome }]
    },

    pot: {
        cards: [],                       // cards in the pot this round (anted + dropped + loot)
        antedBy: {},                     // { playerId: [cardIds] } — who anted what
        roundWinner: null,               // determined at end of round
    },

    player: {
        character: {},                   // Character card data (from snapshot)
        characterStack: {                // persistent sidebar
            charCard: {},                // charPerson snapshot
            equipped: {                  // currently equipped cards
                head: null, body: null,
                handL: null, handR: null,
                feet: null, ring: null, back: null
            },
            activeSkills: [null, null, null, null],
        },
        hand: [],                        // cards available to play (sorted by type)
        ap: { current: 3, max: 3 },      // Action Points (derived from END)
        needs: { hp: 100, energy: 80, hunger: 100, morale: 75 },
    },

    opponent: {                          // AI character (Mode 1) or null (Mode 2)
        character: {},
        characterStack: { /* same structure */ },
        ap: { current: 4, max: 4 },
        needs: { hp: 100, energy: 80, hunger: 100, morale: 75 },
    },

    pokerFace: {                         // MoodRing adaptation (online only)
        enabled: false,
        banterLevel: 'moderate',         // 'subtle' | 'moderate' | 'aggressive'
        currentEmotion: 'neutral',
        emotionScores: {},
        emotionHistory: [],
        dominantTrend: 'neutral',
        lastTransition: null,
    },

    encounter: null,                     // Current encounter card
    encounterDeck: [],                   // Remaining encounter deck
    discardPile: [],                     // Discarded cards

    eventLog: [],                        // Round-by-round log
    hungerTimer: 0,                      // Rounds since last hunger tick
};
```

---

## Rules Quick Reference

### Printable Reference Card (Include in PDF export)

```
╔══════════════════════════════════════════════╗
║  CARD GAME v2 — QUICK REFERENCE             ║
╠══════════════════════════════════════════════╣
║                                              ║
║  ROUND FLOW:                                 ║
║  1. Draw encounter + Ante to pot             ║
║  2. Roll initiative (1d20 + AGI)             ║
║  3. Place action stacks on bar (open)        ║
║  4. Resolve bar left-to-right (interleaved)  ║
║  5. Cleanup — round winner claims pot        ║
║                                              ║
║  AP = floor(END / 5) + 1  (max stacks/round)║
║  Initiative winner → odd positions (1,3,5..) ║
║  Initiative loser  → even positions (2,4,6..)║
║                                              ║
║  CHARACTER STACK (sidebar, always active):    ║
║  Person + Apparel + Weapon/Magic Focus       ║
║  → base modifiers apply to ALL actions       ║
║                                              ║
║  ACTION STACK (on the bar, 1 per AP):        ║
║  Core card(s) + Modifier card(s)             ║
║  Consumable cores = use-or-lose              ║
║  Modifier cards persist unless stolen        ║
║                                              ║
║  ROLL: 1d20 + char base + action modifiers   ║
║        vs 1d20 + target defense              ║
║                                              ║
║  OUTCOMES (attacker - defender):              ║
║  +10 CRIT HIT: 2× dmg, drop item → pot      ║
║   +5 Solid Hit: full damage                  ║
║   +1 Glancing: half damage                   ║
║    0 Stalemate: nothing                      ║
║   -1 Deflected: weapon -1 dur               ║
║   -5 Countered: half dmg to attacker         ║
║  -10 CRIT COUNTER: full dmg to attacker,     ║
║      attacker drops item + loses next action ║
║  Nat 20 = upgrade 1 tier + Nat 1 = down +   ║
║           drop item                          ║
║                                              ║
║  MID-ROUND DISRUPTION:                       ║
║  INSERT: add stacks after current position   ║
║  REMOVE: strip opponent's next stack         ║
║  MODIFY: alter bonuses on upcoming stack     ║
║                                              ║
║  PER-ROUND THREATS (0–3 per round):          ║
║  Nat 1 initiative → threat at BEGINNING      ║
║    → attacks initiative winner, passive def  ║
║    → win = keep loot, lose = loot to pot     ║
║  Scenario/card → threat at END               ║
║    → round winner faces it, out of AP        ║
║    → 1 bonus stack OR roll to flee           ║
║    → lose/flee = LOSE ENTIRE POT             ║
║                                              ║
║  POT: Ante 1 card/round. Drops/loot → pot.   ║
║  Round winner claims all pot cards.           ║
║                                              ║
║  TALK CARD: Required to communicate.         ║
║  MAGIC: Skill Type + min stat + Energy cost  ║
║  HUNGER: -10 every 3 rounds. 0 = -10 HP.    ║
║                                              ║
╚══════════════════════════════════════════════╝
```

### Victory Conditions

| Mode | Win Condition |
|------|-------------|
| vs AI (Mode 1) | Reduce opponent to 0 HP |
| Story Mode (Mode 2) | Survive 20 rounds OR complete scenario objective |
| Free-for-all (multiplayer) | Last player standing (0 HP = eliminated) |
| Campaign | No single win — survive across sessions, accumulate cards/skills |

### Card Counts Summary

| Card Type | Starter Deck | Encounter Deck | Total (2-player) |
|-----------|-------------|----------------|-----------------|
| Character | 1 per player | 0 | 2 |
| Apparel | 2-3 per player | 3 in encounter | 7-9 |
| Item (Weapon) | 1-2 per player | 4 in encounter | 6-8 |
| Item (Consumable) | 3-5 per player | 5 in encounter | 11-15 |
| Action | 8 per player (1 each type) | 0 | 16 |
| Talk | 1 per player | 0 | 2 |
| Skill | 1-2 per player | 6 in encounter | 8-10 |
| Magic Effect | 0-1 per player | 4 in encounter | 4-6 |
| Encounter | 0 | 33 | 33 |
| **Total** | **~18 per player** | **~55** | **~91** |

---

## Rules Documentation (Help Content)

The game ships with **markdown-formatted rules** that serve as both in-game help (online) and a printable reference document (IRL tabletop play). Rules are generated per-theme during deck build and stored alongside other deck assets.

### Content Structure

```
media/cardGame/rules/{deckName}.rules.md
```

The rules document is organized into player-facing sections:

| Section | Content |
|---------|---------|
| **Quick Start** | 1-page summary: setup, round flow, how to win |
| **Round Flow** | Draw → Initiative → Placement → Resolution → Cleanup with examples |
| **Card Types** | What each card type does, how to read card anatomy |
| **Action Points & Stacks** | AP formula, character stack vs action stacks, how to build stacks |
| **Combat** | Opposed rolls, modifier breakdown, outcome table, critical effects |
| **The Pot** | Ante rules, drops, winner claims |
| **Movement & Actions** | All 8 action types with examples |
| **Magic** | Schools, reagents, Energy costs, spell failure |
| **Needs & Survival** | Health, Energy, Hunger, Morale — what drains them, how to restore |
| **Disruption** | Insert, Remove, Modify — when and how they trigger |
| **Winning** | Opponent mode (reduce HP to 0), GM mode (complete objective) |
| **Card Reference** | Full card list for the theme with stats and rarity |

### Online Help Integration

- **Help button** (?) in header bar opens a slide-out panel rendering the rules markdown
- Uses `marked.parse()` + theme CSS for styled display
- Searchable via text filter at the top of the help panel
- Context-sensitive: during a specific phase, the help panel auto-scrolls to the relevant section
- Section deep-links: `window.am7cardGame.showHelp('combat')` opens help directly to a section

### IRL Print Version

- Same markdown source, rendered to a **print-friendly layout** via `@media print` CSS
- Deck builder includes a "Print Rules" button that opens a print-optimized view
- Formatted as a **booklet**: 2-column layout, themed header/footer, page numbers
- Card Reference section formatted as a compact table (4 cards per row)
- Includes the theme's visual assets (icons, stat symbols) inline where referenced

### Rules Generation

During deck build, the rules document is assembled from:

1. **Base rules template** — `media/cardGame/rules/base.rules.md` contains universal mechanics (round flow, combat, needs). Shared across all themes.
2. **Theme card reference** — auto-generated from the deck's card pool snapshot. Lists every card with name, type, rarity, key stats.
3. **Theme flavor** — theme-specific terminology, setting context, and examples injected via string replacement tokens (`{theme.name}`, `{theme.artStyle.setting}`, etc.)

```
base.rules.md (universal)  +  card pool snapshot  +  theme flavor
                    ↓
        {deckName}.rules.md (complete, theme-specific)
```

### REST Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/rest/game/rules/{deckId}` | GET | Retrieve rendered rules markdown for a deck |
| `/rest/game/rules/{deckId}/print` | GET | Retrieve print-optimized HTML version |

---

## v1 Migration

During implementation, the existing `cardGame.js` (v1) should be archived and replaced by the v2 Card Game.

### Migration Steps

1. **Archive v1 files** — Move all v1 card game files and dependencies into a `./bak` folder:
   ```
   client/view/bak/
   ├── cardGame.js          (v1 game logic)
   ├── cardGame.md          (v1 design doc)
   └── [any v1 dependencies: CSS, templates, helpers]
   ```

2. **Remove from Game menu** — Remove the v1 "Card Game" entry from the top navigation Game menu. The menu item should no longer appear.

3. **Rename v2** — The v2 implementation is simply called **"Card Game"** (not "Card Game v2"). All UI labels, menu entries, route paths, and documentation should use the name "Card Game" without version suffix.

4. **Route update** — The v2 card game takes over the primary card game route:
   ```
   /cardGame → v2 implementation (was /cardGame-v2 during development)
   ```

5. **Cleanup** — Remove any v1-specific server endpoints or service methods that are not reused by v2. Server-side code shared between v1 and v2 (e.g., `GameUtil`, `GameService`) remains in place.

### Implementation Note

The `./bak` folder preserves v1 source for reference during development but should not be deployed to production. Add `client/view/bak/` to `.gitignore` if v1 files should not be tracked after migration.

---

## Open Design Questions

Items to resolve during implementation:

1. **Campaign persistence format**: How to serialize a player's card collection between sessions. JSON save file? Tie to AM7 `olio.store`?
2. **Card rarity distribution**: Exact probability of each rarity tier in encounter deck draws.
3. **Balancing magic vs physical**: Energy costs for magic may need tuning — magic is powerful but Energy-expensive. Playtesting required.
4. **Multiplayer encounter sharing**: In 3-4 player free-for-all, does each player draw their own encounter, or is one shared? Current design: each player draws, but threats can target any player.
5. **Crafting recipes**: Specific material-to-item recipes for the Craft action. Can derive from existing `olio.builder` templates.
6. **Scenario cards (Story Mode)**: Pre-built scenario setups for GM mode. Each scenario defines starting conditions, objective, and encounter deck composition.
7. **Level progression**: How stat increases work between campaign sessions. Current proposal: +1 to any stat per session survived, capped at 20.
