# Card Game v2 — Card-Based RPG

A web-based card RPG playable online (1v1 vs AI) and printable for real-life tabletop play (multiplayer IRL only). Built on the AM7 backend with LLM-driven outcomes online; IRL play is fully standalone — just printed cards, dice, and players, no online services or data required.

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
23. [Phased Implementation Plan](#phased-implementation-plan) — build-test-build incremental delivery
24. [v1 Migration](#v1-migration) — archive cardGame.js v1, rename v2 to "Card Game"
25. [Open Design Questions](#open-design-questions)

**Companion files:**
- [cardGame-v2-themes.md](cardGame-v2-themes.md) — Complete card pool definitions for High Fantasy, Sci-Fi, and Post Apocalyptic themes

---

## Design Philosophy

### Core Principles

1. **Cards are the interface.** Every game element — characters, items, actions, encounters — is a card. No map, no board. The world comes to you through drawn cards.
2. **Stacks are sentences.** A single card is a noun. A stack is a verb phrase: "Character + Weapon + Action = I attack." Stacking is the primary mechanic.
3. **Use it or lose it.** Consumable cards played in a round are spent whether their effect triggered or not. This forces commitment and risk assessment every round.
4. **Do it or don't.** Each round, you build stacks and commit them. No take-backs. Open placement prevents hidden play, but initiative determines position advantage.
5. **Same rules, two surfaces.** Online and IRL play follow identical rules. Online uses LLM for outcome narration and AI opponent behavior; IRL uses dice + printed card modifiers only — no network, no server, no LLM. IRL play is fully self-contained with printed cards and players.
6. **Print-first card design.** Cards are designed to be physically printed and playable. Online rendering mirrors the physical layout exactly.

### Simplifications from RPG.md

| RPG.md Feature | v2 Card Approach |
|---------------|-----------------|
| MGRS spatial grid, cell navigation | Eliminated — no map, encounters drawn from deck |
| 16 base statistics | Reduced to 6 core stats on the character card |
| 2d20 resolution with personality/instinct modifiers | Simplified to 1d20 + stack modifier vs target number or opposed roll (d20 maps directly to 1-20 stat range) |
| Maslow hierarchy (5 levels, 30+ individual needs) | 3 need tracks on the character card (Health, Energy, Morale) |
| Skill proficiency 0-100% with decay formula | Skill cards with flat modifier values; decay = discard oldest skill card between sessions |
| Seal-based magic with reagents and channeling | Three skill types (Imperial, Undead, Psionic) + Magic Effect cards |
| Real-time Overwatch simulation | Turn-based rounds with initiative-ordered placement on shared action bar |
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
| **Action** | Red | What you do this round (Attack, Flee, Rest, etc.) | Played and returned to hand after round |
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
| HP  [████████░░] 16/20               |
| NRG [██████░░░░] 10/14  (=MAG)       |
| MRL [██████████] 20/20               |
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

**Need Tracks (3):**

| Track | Max Value | Depleted By | Restored By |
|-------|-----------|------------|-------------|
| Health (HP) | **20** (flat, all characters) | Combat damage, hazards | Healing items, rest, Life magic, round recovery |
| Energy (NRG) | **MAG stat** (or INT for Undead magic, or equivalent per theme) | Magic casting, spell use | Rest, stimulants |
| Morale (MRL) | **20** (flat) | Failed actions, ally defeat, isolation | Successful Talk, ally victory, rest |

At 0 HP = defeated. At 0 Energy = cannot cast spells (can still attack, talk, etc.). At 0 Morale = cannot play Talk or cooperative actions.

**Hunger is shelved** — removed from card game mechanics for simplicity. May be revisited in future iterations.

### Health & Energy Cards (Damage/Magic Tracking)

Health and Energy are tracked visibly using **denomination cards**. This makes damage and spellcasting tangible — you throw away health cards when hit, spend energy cards when casting, and draw them back when recovering.

#### Health Cards (HP Tracking)

**Setup:** Each character starts with health cards totaling **20 HP** (max HP is always 20). Suggested mix: 1× +10, 1× +5, 3× +2, 1× +1 = 20 HP. Adjust denominations as needed.

**Denomination cards:**
```
┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
│ +1  │  │ +2  │  │ +5  │  │ +10 │
│  ♥  │  │ ♥♥  │  │♥♥♥♥♥│  │ ♥×10│
│  HP │  │  HP │  │  HP │  │  HP │
└─────┘  └─────┘  └─────┘  └─────┘
```

**Taking damage:** Discard health cards equal to the damage dealt. Make change from higher denominations as needed (e.g., 3 damage with only a +5 = discard +5, take +2 back from bank).

**Healing:** Draw health cards from the health bank equal to the amount restored (potions, rest, magic). **Cannot exceed max 20 HP** — if healing would overflow, just take cards up to 20. Use-it-or-lose-it.

**End-of-round recovery:**
- **Round loser:** +2 HP recovery (draw +2 health card)
- **Round winner:** +5 HP recovery (draw +5 health card)
- Recovery cannot exceed max HP. If already at max, skip.

#### Energy Cards (Magic/Spell Tracking)

**Setup:** Each character starts with energy cards totaling their **magic stat** value (MAG for Imperial magic, INT for Undead magic, theme-equivalent for other magic systems). A character with MAG 13 starts with 13 energy worth of cards.

```
┌─────┐  ┌─────┐  ┌─────┐
│ +1  │  │ +2  │  │ +5  │
│  ⚡ │  │ ⚡⚡│  │⚡×5 │
│ NRG │  │ NRG │  │ NRG │
└─────┘  └─────┘  └─────┘
```

**Casting a spell:** Discard energy cards equal to the spell's energy cost. Energy is spent **when the spell is played** (committed at placement), regardless of whether the spell succeeds or fails.

**Recovery:** Rest action restores energy. Some consumables restore energy. Cannot exceed max energy (= magic stat).

**Non-magic characters:** Characters with no magic stat (MAG < threshold for their theme) have **0 energy cards** and cannot cast spells. They still participate normally with physical attacks, items, and non-magic actions.

#### Card Bank (Shared)

**RL play:** Health and energy cards sit in piles next to the character stack. Discarded cards go to a shared **card bank** in the center of the table. When healing/recovering, draw from the bank. The pile sizes are your HP and Energy — instantly visible to both players.

**Online play:** The card piles are visualized as stacks next to the character portrait, with numeric totals displayed. Taking damage shows cards flipping off the stack (animated). Healing shows cards returning. The numeric bars still show exact values — the denomination cards are a visual layer on top.

**Print:** The deck includes a sheet of perforated health/energy cards per player:
- Health: 2× +10, 2× +5, 4× +2, 4× +1 = 42 HP worth (plenty of change-making room for 20 HP max)
- Energy: 2× +5, 4× +2, 4× +1 = 22 NRG worth (covers up to MAG 20)

**Overheal/overcharge:** If healing or energy recovery would exceed the max, the player simply stops at max. No extra cards drawn — the cup doesn't overflow.

**Equip Slots:**

| Slot | Accepts |
|------|---------|
| Head | Helmet, hat, circlet |
| Body | Armor, robe, vest |
| Hand L | Weapon, shield, tool, off-hand item |
| Hand R | Weapon, tool, primary-hand item |
| Feet | Boots, sandals |
| Ring | Ring items (stat bonuses) |
| Back | Cloak, pack, wings |

**Hand slot rules:** A character has exactly 2 hand slots (L and R). These are the **only slots that hold items** actively used in combat (weapons, shields, tools, foci). All other slots hold passive equipment (apparel).

- **One-handed weapon:** Occupies 1 hand slot. The other hand can hold a shield, a second one-handed weapon (dual wield), or nothing.
- **Two-handed weapon:** Occupies BOTH hand slots (handL + handR). No shield or off-hand item while using a two-handed weapon.
- **Dual wield:** Two one-handed weapons, one per hand. Playing **1 Attack action card** with two weapons equipped yields **2 separate attack rolls** — one per weapon, each at full ATK. This means dual wielding effectively doubles your attacks per action stack, but you lose the shield's parry bonus on defense.
- **Weapon + Shield:** One-handed weapon in one hand, shield in the other. Shield adds its DEF bonus to passive armor defense AND to parry rolls.
- **Max 2 items in hands** — you cannot equip more than 2 hand items. Equipping a two-handed weapon auto-unequips whatever was in both hands.

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
| Slot: Hand (1H) Rarity: ★★  |
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
- **Slot**: `Hand (1H)` for one-handed, `Hand (2H)` for two-handed. Two-handed weapons occupy both hand slots.
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
| **Attack** | Offensive | Character + Weapon (req) + Skill (opt) | 1d20 + STR + ATK bonus vs target passive DEF (+ weapon parry if weapon has parry property) | 0 |
| **Flee** | Movement | Character only | 1d20 + AGI vs encounter difficulty | 0 |
| **Investigate** | Discovery | Character + Skill (opt) | 1d20 + INT vs encounter hidden threshold | 0 |
| **Trade** | Social | Character + Item(s) to offer | CHA determines price modifier | 0 |
| **Rest** | Recovery | Character only (no other actions) | Auto-success: restore +2 HP, +3 Energy | 0 |
| **Use Item** | Utility | Character + Consumable item | Auto-success: apply item effect | 0 |
| **Craft** | Creation | Character + Material items + Skill | 1d20 + INT vs recipe difficulty | 2 |

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
| **NPC** | Non-hostile character from deck's character pool. Can Talk, Trade, or ignore. May offer quests (scenario mode). Has real `olio.charPerson` stats, portrait, personality, and interaction history. |

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
| DEFENSE | Parry bonus, armor effectiveness |
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
| Action Cards | 1 of each type | Full set: Attack, Flee, Investigate, Trade, Rest, Use Item, Craft, Watch |
| Talk Card | 1 | Always included |
| Starting Skills | 1-2 | Derived from character's highest proficiency `data.trait` records |
| Magic Effects | 0-1 | Only if character has MAG ≥ 10 or INT ≥ 12 or relevant skill type |

**Total starter deck**: ~15-20 cards

### Shared Encounter Deck

A communal deck drawn from **randomly** during the Draw Phase. Contains common-to-rare content — the everyday encounters, supplies, and dangers of the world. All draws are random (shuffled deck, top card).

| Subtype | 2P | 3P | 4P | Rarity Range | Notes |
|---------|----|----|-----|-------------|-------|
| Threat encounters (animals/creatures) | 8 | 12 | 16 | ★–★★★ | Regular threats only (difficulty 4–10). Bosses are in the Vault. |
| Hostile NPC encounters | 4 | 6 | 8 | ★–★★★ | Deck characters drawn as hostile. Can be fought OR subdued via Talk. |
| Event encounters | 10 | 14 | 18 | ★–★★ | Weather, terrain effects, time-of-day |
| Discovery encounters | 10 | 13 | 16 | ★–★★ | Loot finds, resource caches |
| Friendly NPC encounters | 8 | 10 | 12 | ★–★★★ | Deck characters — trader, ally, quest-giver, etc. |
| Item cards (loot) | 10 | 14 | 18 | ★–★★★ | Weapons, apparel, consumables — Common to Rare only |
| Skill cards (learnable) | 5 | 7 | 9 | ★★–★★★ | Found through encounters |
| Magic Effect cards | 2 | 3 | 4 | ★★★ | Rare, found through Discovery or NPC |

**Total encounter deck**: 57 cards (2P) → 72 cards (3P) → 87 cards (4P). Formula: 57 + 15 per additional player.

**Hostile NPC encounters:** Some NPCs are dealt into the threat portion of the encounter deck as hostile characters. When drawn, they behave like threats — they get AP, fill action bar positions, and attack. However, unlike animal threats, hostile NPCs can be **subdued through non-violence** using the Talk card:

- **Fight:** Treat as a normal threat. Defeat them through combat. They have `olio.charPerson` stats and equipment.
- **Talk to subdue:** Play a Talk card targeting the hostile NPC. Opens a chat/negotiation. Success (opposed CHA roll, or LLM-evaluated conversation online) de-escalates the NPC from hostile to neutral. The NPC is removed from the threat bar and added to the player's hand as a friendly NPC card (can be traded with, asked for quests, etc.).
- **Failed Talk:** The hostile NPC becomes more aggressive (+2 ATK for the rest of the encounter). The Talk card is consumed but the NPC remains hostile.
- **Hostile NPC loot:** If defeated in combat, hostile NPCs drop equipment from their `olio.charPerson` inventory (weapon, apparel). If subdued via Talk, they offer a trade or quest reward instead.

**Draw is random** — the deck is shuffled at game start. Each Draw Phase, the active player draws the top card. No selection, no peeking. The desperation draw rule (draw 2 if Energy < 30) also draws randomly from the top.

### Treasure Vault (Unique Deck)

A separate deck containing **unique/hard encounters AND high-rarity items** shuffled together. Boss creatures, legendary weapons, epic armor, and rare magic all share the same vault. When you earn a vault draw, you might pull a legendary sword — or you might unleash a dragon. Risk and reward in the same deck.

The vault contains two categories of cards, shuffled together:

**Boss Encounters (unique/hard animals & creatures):**

| Subtype | 2P | 3P | 4P | Rarity | Notes |
|---------|----|----|-----|--------|-------|
| Boss threats | 4 | 5 | 6 | ★★★★–★★★★★ | Difficulty 12+, unique abilities, multi-round mechanics |

These are the hardest creatures in the theme — dragons, liches, behemoths, AI core defenders. They have higher stats, unique behaviors (phasing, regeneration, multi-attack), and better loot attached. When drawn from the vault, they become **immediate encounter threats** — placed at the end of the current round's action bar just like a scenario-triggered threat. You opened the vault and something came out.

**High-Rarity Items & Skills:**

| Subtype | 2P | 3P | 4P | Rarity | Notes |
|---------|----|----|-----|--------|-------|
| Legendary weapons | 2 | 2 | 3 | ★★★★★ | Named artifacts with unique abilities |
| Epic weapons | 3 | 4 | 5 | ★★★★ | Superior versions of common weapon types |
| Epic apparel | 3 | 4 | 5 | ★★★★ | Set pieces, enchanted armor |
| Epic consumables | 2 | 3 | 4 | ★★★★ | Elixirs, powerful one-use items |
| Rare/Epic skills | 3 | 4 | 4 | ★★★–★★★★ | Advanced combat/magic techniques |
| Legendary magic effects | 2 | 2 | 3 | ★★★★★ | Game-changing spells |

**Total treasure vault**: 18 cards (2P) → 23 cards (3P) → 28 cards (4P). Formula: 18 + 5 per additional player.

#### How to Draw from the Treasure Vault

The vault is sealed — you need a **key action** to draw from it:

| Trigger | Draw Count | Condition |
|---------|-----------|-----------|
| **Discovery encounter (★★★)** | Draw 1 | Only Rare discoveries unlock the vault — "You find a hidden chamber..." |
| **Investigate action (critical success)** | Draw 1 | Natural 20 on Investigate roll opens the vault |
| **NPC quest reward** | Draw 1 | Completing an NPC encounter's quest objective grants a vault draw |
| **Round Pot jackpot** | Draw 1 | If the pot contains 5+ cards when claimed, winner also draws from vault |

**Vault draw is random** — the vault deck is shuffled, and you draw from the top. You cannot browse or choose. The excitement is in *earning the right to draw*, not in selecting what you get — and the tension is in not knowing whether you'll get a treasure or a boss fight.

**Drawing a boss from the vault:**
- The boss encounter is placed at the **end of the current action bar** as an immediate threat (same rules as end threats — see [Per-Round Threats](#per-round-threats))
- The boss comes with attached loot cards (drawn from the vault alongside it, not from the encounter deck)
- Defeating a vault boss grants **1 additional vault draw** as a reward — creating a chain where beating a boss might yield another boss or a legendary item
- If no one defeats the vault boss this round, it **persists** into the next round (stays on the bar) with full remaining HP

**Vault exhaustion:** Once all vault cards are drawn, the vault is empty for the rest of the session. In campaign mode, the vault refills between sessions with new high-rarity cards and boss encounters scaled to player level.

**RL equivalent:** The vault is a separate, physically smaller deck placed to the side of the main encounter deck. It should have a distinct card back (gold/foil pattern) or be placed in a container (box lid, cloth pouch) to signal its special status. Players draw face-down. If you flip a boss, it goes on the bar immediately.

### Character Generation

**All characters in the game — player, opponent, and NPCs — are real `olio.charPerson` records** generated from balanced templates and stored in the deck's Characters folder. Each character has stats, personality traits, alignment, and a theme-appropriate trade/class.

**How characters are generated during deck build:**

| Role | Generation Method | Storage |
|------|-----------------|--------|
| **Player character** | Generated from template via "Generate Characters" button | `~/CardGame/{deckName}/Characters/` |
| **AI opponent** (Mode 1) | Generated from template, randomly selected from deck characters | `~/CardGame/{deckName}/Characters/` |
| **NPC encounters** | Generated from templates during deck build | `~/CardGame/{deckName}/Characters/` |

**Character generation process:**

The deck builder uses balanced templates from `character-templates.json` which define 12 character archetypes (warrior, mage, rogue, cleric, ranger, bard, tank, berserker, assassin, battlemage, scholar, noble). During deck build:

1. The server's `/olio/roll` endpoint generates a base character with random name, gender, and apparel
2. Template modifications are applied (alignment, personality traits, class/trade)
3. Theme variants adapt the trade to the theme (e.g., "Warrior" → "Knight" in high-fantasy, "Marine" in sci-fi)
4. Character is saved to the deck's Characters folder as a real `olio.charPerson` record
5. Each deck supports up to 8 unique characters

**Balance rules from templates:**
- Target total stats: 60 points distributed across STR, AGI, END, INT, MAG, CHA
- Minimum stat: 6, Maximum stat: 18
- Each template defines a unique stat distribution suitable for its role

**Theme variants:**

Templates include theme-specific trade names:

| Template | High-Fantasy | Dark-Medieval | Sci-Fi | Post-Apocalypse |
|----------|-------------|---------------|--------|-----------------|
| warrior-balanced | Knight | Man-at-Arms | Marine | Enforcer |
| mage-glass-cannon | Wizard | Alchemist | Psionic | Mutant |
| rogue-agile | Thief | Cutpurse | Hacker | Scavenger |
| cleric-support | Priest | Monk | Medic | Healer |

**Apparel sourcing:**

Character apparel is loaded from player-relative paths:
- `~/Apparel` — outfit sets
- `~/Wearables` — individual wearable items

**Interaction history carries over:** Because characters are real `olio.charPerson` records, any `olio.interaction` records between the player and NPCs persist across game sessions. If you chatted with a merchant last session and built rapport, the LLM receives that interaction history when you encounter them again.

### Deck Building (Between Sessions / Campaign Mode)

Between game sessions in campaign play:
- **Only the Character card persists** — the character's stats, level gains, and identity carry forward
- All items, apparel, weapons, skills, and consumables are **NOT carried between games** — they can be lost, damaged, dropped, or stolen during play, so the deck rebuilds fresh each session
- Character stat changes from leveling persist on the character card
- NPC encounters are re-drawn from the population (you may encounter different characters each session, but interaction history with previously met NPCs persists)
- Each new session draws a fresh starter hand and encounter/vault decks from the theme's card pool

### Card Pool Shortage Fallback

If a theme pool doesn't have enough cards to fill a required category, the deck builder applies these fallback rules in order:

| Shortage | Fallback |
|----------|----------|
| **Encounter deck short on threats** | Generate random threats with scaled stats (ATK/DEF/HP derived from difficulty target). Art prompt auto-composed from theme art style. |
| **Not enough items/weapons/apparel** | Use the theme's base item templates with randomized stats within the rarity range. Named generically: "Common Sword," "Sturdy Boots," etc. Flagged as auto-generated in the snapshot for later replacement. |
| **Skill or Magic Effect cards short** | Reduce count to available pool size. Minimum 1 skill card (always: the character's highest proficiency trait). Magic Effects can be 0 if no theme magic cards exist. |
| **NPC encounter slots unfilled** | Generate additional characters from templates via `/olio/roll` endpoint. Each gets a portrait generated from `sdConfig.charPerson`. |
| **Treasure Vault too small** | Vault can be as small as 5 cards. Below that, the deck builder warns "Vault is thin — consider adding items via Add Item (Step 4)." Game still plays, vault just empties faster. |
| **Encounter deck exhausted mid-game** | Reshuffle the discard pile into a new encounter deck. Vault is NOT reshuffled — once empty, it stays empty until campaign session refill. |

The deck builder shows a **pool health indicator** during Step 6 (Review & Build): a per-category bar showing filled/required counts with yellow (near-shortage) and red (using fallback) warnings. This lets the player add custom cards via Step 4 before building.

### Card Hoarding Prevention (Lethargy & Exhausted)

Two rules prevent players from hoarding duplicate action cards and force active, decisive play:

#### Lethargy

**Trigger:** At the **end of a round** (Cleanup Phase), if you hold **more than 1 copy** of the same action type and you **did not play any of that type** this round.

**Effect:** You keep 1 copy. All extras of that action type are returned to the encounter deck draw pile (shuffled back in).

**Reasoning:** If you're sitting on 3 Attack cards and not using them, you're hoarding. The world doesn't wait — unused tools drift away. You must use it or lose the extras.

**Example:**
```
Start of round: Hand contains Attack ×3, Flee ×1, Investigate ×1
Player places: Investigate (position 1), Flee (position 3)
                ↑ no Attack played this round

Cleanup — Lethargy check:
  Attack: 3 copies, 0 played → LETHARGY. Keep 1 Attack, return 2 to encounter deck.
  Flee: 1 copy → no duplicate, skip.
  Investigate: 1 copy → no duplicate, skip.

Hand after cleanup: Attack ×1, Flee ×1, Investigate ×1
```

#### Exhausted

**Trigger:** During **resolution**, if you play the **same action type more than once** in the same round, hold **2 or more** of that type in hand, and the **last played action of that type fails**.

**Effect:** All extra copies of that action type (beyond 1) are returned from your hand to the encounter deck draw pile.

**Reasoning:** Overcommitting to one strategy and failing at it means you've exhausted that approach. Your spare copies slip away — the enemy adapts, your weapons dull, your tricks stop working.

**Example:**
```
Hand contains: Attack ×3, Rest ×1, Investigate ×1
Player places: Attack (pos 1), Attack (pos 3), Rest (pos 5)

Resolution:
  Position 1: Attack → Success (roll 14, opponent roll 8) ✓
  Position 3: Attack → Fail (roll 5, opponent roll 17) ✗
              ↑ This is the LAST Attack played this round, and it FAILED
              ↑ Player still has 1 Attack in hand (3 total - 2 played = 1)

Exhausted check (during resolution, immediately):
  Attack: played 2 this round, last one failed, 1 remaining in hand
  But 1 remaining = no extras beyond 1 → Exhausted does NOT trigger.

DIFFERENT SCENARIO — 4 Attack cards:
  Hand: Attack ×4. Places Attack (pos 1), Attack (pos 3). Holds Attack ×2 in hand.
  Position 3 Attack fails → Exhausted triggers.
  Keep 1 Attack in hand, return 1 extra to encounter deck.
  Hand after: Attack ×1.
```

**Key distinctions:**

| | Lethargy | Exhausted |
|---|---------|-----------|
| **When** | Cleanup Phase (end of round) | During Resolution (mid-round, immediate) |
| **Trigger** | Have 2+ of a type, played 0 of that type | Played 2+ of a type, last one failed, hold 2+ extras |
| **Cards affected** | Unplayed duplicates in hand | Unplayed duplicates in hand |
| **Where cards go** | Back to encounter deck (shuffled in) | Back to encounter deck (shuffled in) |
| **Net result** | Always end with exactly 1 of that type | Always end with exactly 1 of that type |

**Both rules apply only to Action-type cards** (Attack, Flee, Rest, Use Item, Investigate, Craft, Watch) and Talk cards. They do NOT apply to equipment, consumables, skills, or magic effects — you can stockpile potions and gear freely.

**Cards returned to the encounter deck** are shuffled back into the draw pile, meaning they can be drawn again by anyone. This keeps the card economy flowing instead of letting duplicates pile up in one player's hand.

**RL enforcement:** In tabletop play, both players are responsible for policing hoarding. At cleanup, declare your hand's action card counts openly. If disputed, count and return per the rules. For Exhausted, the trigger is immediate on the failed roll — return extras before the next position resolves.

---

## Round Structure

### Round Flow

Each game round follows this sequence:

```
1. INITIATIVE PHASE
   └─ All players roll 1d20 + AGI
   └─ Highest total wins initiative (ties: re-roll until broken)
   └─ Initiative winner fills ODD action bar positions (1, 3, 5, 7...)
   └─ Other player(s) fill EVEN positions (2, 4, 6...)
   └─ Encounter threats (if any) also receive AP and fill positions
   └─ CRITICAL INITIATIVE: Nat 1 on initiative roll triggers a Per-Round Threat
      at the BEGINNING of the action bar (see Per-Round Threats)

2. EQUIP PHASE (between rounds only)
   └─ Before drawing, players may equip/unequip apparel, weapons, or skills
   └─ Equipping is FREE (no AP or Energy cost) — it happens BETWEEN rounds
   └─ If a player loses a weapon during resolution, they cannot re-equip mid-round
      — they must defend the remaining positions and hope to draw/equip next round
   └─ Character Stack (charPerson + equipped gear) is always active on the sidebar

3. DRAW & PLACEMENT PHASE (turn-based, in initiative order)
   └─ Players take turns in initiative order (winner goes first)
   └─ On each turn, the active player:
      a. DRAWS 1 card from the encounter deck (mandatory, free)
      b. Then CHOOSES ONE:
         • PLACE an action stack (costs 1 AP): lay down action card + adjacent
           skill modifier cards onto the Action Bar. Stacks are OPEN (face-up).
           Consumables committed here are LOCKED IN (use-or-lose).
         • DRAW AGAIN: draw 1 additional card from the encounter deck (no AP cost)
           — a second draw instead of placing
         • SKIP: pass this turn without placing or drawing extra
   └─ Continue rotating turns until all players have either:
      - Used all their AP, or
      - Skipped consecutively (both skip = phase ends)
   └─ Must place at least 1 action stack total before the phase ends
   └─ If Threat drawn: must be addressed this round (fight, flee, or talk)
   └─ If Event drawn: effect applies immediately
   └─ If Discovery (★★★): optional interaction + grants 1 TREASURE VAULT draw
   └─ If NPC drawn: optional interaction (quest reward may grant vault draw)
   └─ [Pause] button pauses the round timer (both players must agree in multiplayer;
      vs AI: immediate pause). Paused state persists until [Resume] is clicked.
   └─ NOTE: Epic/Legendary items are in the Treasure Vault, not the encounter deck.
      See Treasure Vault for how to earn vault draws.

4. RESOLUTION PHASE (left to right, interleaved)
   └─ Resolve action bar position by position: 1, 2, 3, 4...
   └─ Each position resolves fully before the next begins
   └─ Players alternate — initiative winner's positions first, then other's
   └─ DAMAGE IS REAL-TIME: HP changes apply immediately per position.
      If a player's HP hits 0 mid-round, the game ends IMMEDIATELY.
      No waiting for cleanup — the surviving player wins on the spot.
      Remaining unresolved positions on the action bar are skipped.
   └─ Mid-round disruptions may INSERT new stacks or REMOVE upcoming stacks
   └─ EXHAUSTED CHECK (immediate, per position): If this is the last played
      action of a type this round AND it failed, AND you hold 2+ extras of
      that type → keep 1, return extras to encounter deck before next position
   └─ Online: each position narrated by LLM, dice rolled server-side
   └─ RL: dice rolls + card modifier math per position

5. CLEANUP PHASE (only reached if all players survive the round)
   └─ Discard consumed items (core cards of action stacks that were consumable)
   └─ Return non-consumed Action cards and modifier cards to hand
   └─ Reduce durability on used equipment
   └─ LETHARGY CHECK: For each action type where you hold 2+ copies
      but played 0 this round → keep 1, return extras to encounter deck
   └─ Round recovery: LOSER gets +2 HP, WINNER gets +5 HP (cannot exceed max)
   └─ Unresolved threats carry to next round (no escalation — same stats)
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

**AP vs Energy:** AP is the hard cap on stack count. Energy is the resource cost per action. A player with 4 AP but only 15 Energy remaining may only be able to afford 2 actions (if each costs 10 Energy).

**AP usage rules:**
- **Must use at least 1 AP per round** — a player cannot voluntarily pass an entire round without acting (that triggers the Lazy Bones default, see Round Timing)
- **Don't have to use all AP** — a player with 4 AP can choose to place only 2 action stacks if they want to conserve Energy or have limited cards
- **Use it or lose it** — unused AP does NOT carry to the next round. Each round starts fresh with max AP from the current END stat

**Encounter AP:** Encounter threats also get AP based on their difficulty tier:

| Difficulty | Encounter AP |
|-----------|-------------|
| 1–4 (Easy) | 1 |
| 5–8 (Medium) | 2 |
| 9–12 (Hard) | 3 |
| 13+ (Boss) | 4 |

Encounter stacks are placed by the system (online: server/AI decides; IRL: follow the encounter card's printed behavior text, or the human GM decides).

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

**Multiplayer (IRL only):**

Online play is always **player vs system** (AI opponent or GM). Multiplayer is IRL tabletop only — additional human players join the initiative roll and take turns on the shared action bar.

- **Initiative:** All players roll 1d20 + AGI. Turn order = highest to lowest.
- **Position fill:** Round-robin by initiative rank. 1st fills positions 1, 4, 7...; 2nd fills 2, 5, 8...; 3rd fills 3, 6, 9... and so on.
- **Directional combat:** Attacks target the **next player** in initiative order (look ahead on the bar). Defense applies against the **previous player** (look behind on the bar). The last player in initiative order attacks the first (wrap-around).
  - Example: Initiative order is Player A, Player B, Player C.
    - Player A attacks → targets Player B
    - Player B attacks → targets Player C
    - Player C attacks → targets Player A (wrap-around)
    - Player A defends against → Player C's attacks
    - Player B defends against → Player A's attacks
    - Player C defends against → Player B's attacks
  - A player can choose to play only an Attack action and rely on passive defense (armor + weapon parry), or add a shield/defensive item to bolster their defense against the player behind them.
- **Encounter threats:** In GM mode with multiplayer, encounter threats target the player with the lowest HP (or random if tied).

### Round Timing & Lazy Bones (Sloth Penalty)

Each phase has a configurable timer. When the timer expires without the player acting, the **Lazy Bones** penalty applies:

**Timer defaults:**
- **Placement phase:** 60 seconds (online), 2-minute sand timer recommended (RL)
- **Resolution phase (per position):** 15 seconds to confirm/respond

**Lazy Bones penalty (timer expiry):**
1. **First expiry** — Player loses initiative advantage (forced to even positions for the rest of the round, regardless of initiative roll) AND loses 1 AP for this round (one fewer action stack allowed). Timer restarts.
2. **Second expiry** — Player loses an additional AP. Timer restarts again. If AP reaches 0, the player has no actions this round.
3. **Never acts (timer expires with AP remaining and the player can act)** — Player **defaults the round**. All their action bar positions are emptied. They are treated as having taken no actions. The opponent resolves their stacks unopposed. The defaulting player still takes passive damage from any threats. The round continues to cleanup.

**Lazy Bones state:**
```javascript
lazyBones: {
    expiries: 0,              // Number of timer expiries this round
    apPenalty: 0,             // AP lost to Lazy Bones this round
    initiativeLost: false,    // Whether initiative was forfeit
    defaulted: false          // Whether player defaulted the round entirely
}
```

**Pause button:** The [Pause] button in the header stops the round timer. Against AI, pause is immediate. In multiplayer, both players must agree (one player requests, the other confirms). While paused, no timer penalties accumulate. The game shows a "PAUSED" overlay. [Resume] restarts the timer from where it stopped.

**RL enforcement:** In tabletop play, Lazy Bones is optional. The group can agree to use a sand timer with the same escalating penalties, or play untimed.

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

**Round winner determination (point system):**
The "round winner" is determined by **round points** accumulated during resolution:
- **Landed hits** — each point of HP damage dealt to the opponent counts as 1 round point
- **Successful non-combat actions** — each successful action that isn't a direct attack (Talk, Investigate, Trade, Craft, etc.) earns **5 round points**
- Higher total wins the round
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

Regular threats (difficulty 4–10) are drawn from the **encounter deck**. Boss threats (difficulty 12+, unique creatures) are in the **Treasure Vault** and only appear when someone earns a vault draw and pulls one.

A threat comes with:
- A threat card (creature/hazard with ATK, DEF, HP, difficulty)
- 1–2 random loot cards attached (regular threats: loot from encounter deck, ★–★★★; vault bosses: loot from vault, ★★★★–★★★★★)
- The threat card shows its loot face-up, so players can see what's at stake
- Defeating a vault boss grants **1 additional vault draw** (risk → reward chain)

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
- The threat occupies position 1 (or positions 1 and 2 if two threats). All player action stacks shift right accordingly.
- The threat **attacks the player who rolled Nat 1** — your fumble brought this upon yourself. The other player watches.
- The fumbling player has no warning and no choice — the threat acts against them before any player actions resolve.
- The fumbling player's Character Stack passively defends (`1d20 + END + Apparel DEF`, plus parry if applicable at position 2).
- **If the fumbling player beats the threat:** They keep the threat's loot cards immediately (added to hand, not pot). Surviving your own mistake is rewarded.
- **If the fumbling player loses to the threat:** They take damage. If this drops them to 0 HP, **they lose the game immediately** — killed by their own initiative fumble before the round even begins. The threat's loot goes into the pot (or is discarded if the game ends).

If BOTH players roll Nat 1: two threats at the beginning (positions 1 and 2), resolved in sequence. T1 attacks the player who rolled lower (worst fumble goes first). T2 attacks the other fumbler. Player action stacks start at position 3.

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
- The end threat **applies to the round winner** (the player winning the round based on net HP advantage). This is a "final boss" surprise — you won the round, but can you survive one more fight?
- The round winner is **out of Action Points** at this point — they've already used all their stacks.
- The round winner gets **1 bonus stack** to fight the threat — a single action stack of **any type** (attack, defense, spell, item, talk), built from cards currently in hand, at **no AP cost**. The stack can include any number of modifier/skill cards adjacent to the core action card. This is their only shot — one stack, any composition, last chance.
- **Repel = at least 1 successful attack** against the threat. Success is determined by the normal opposed roll — the bonus stack must deal damage (any non-negative outcome: Glancing Blow or better). If the bonus stack is a Talk action (subduing a hostile NPC), a successful Talk roll also counts as repelling.
  - **If repelled AND threat killed (HP → 0):** Round winner keeps the threat's loot AND claims the pot. Clean victory.
  - **If repelled but threat survives (HP > 0):** Round winner still claims the pot, but the **threat carries to the beginning of the next round** — it becomes a beginning threat at position 1 next round with its remaining HP. The threat escalates (+2 ATK, same as unresolved threats). The round winner still gets the pot this round.
- **If NOT repelled (bonus stack fails — Stalemate or worse):** The ending player **loses the round**. They forfeit the entire pot to the opponent. The threat's loot is discarded. The threat still carries to next round as a beginning threat.
- **Flee option:** Instead of fighting, the round winner can **roll to flee** (`1d20 + AGI` vs threat difficulty). Success = escape, no damage, but the pot goes to the opponent and the threat carries to next round. Failure = take damage AND lose pot.
- **If the round winner doesn't know** about the end threat in advance (e.g., trap card triggered mid-resolution), this creates a surprise twist. Online: the narrator reveals the threat dramatically. RL: the threat card is flipped at the end.

**Hidden end threat reveal timing:**
End threats come from **scenario card draws** in the encounter deck. The drawn scenario card determines whether the threat is hidden or visible:
- **Visible threats:** The scenario card's threat is revealed immediately during the draw phase. Players see the threat card at the end of the bar during placement and can plan around it.
- **Hidden threats:** The scenario card has a hidden threat flag — the card itself is drawn and placed face-down at the end of the bar. Players know *something* is there but not what. The threat is revealed only when resolution reaches that position. Online: the narrator builds suspense ("something stirs in the shadows...") then reveals it dramatically. RL: the threat card is flipped face-up at resolve time.
- Which type (hidden vs visible) is a property of the scenario card itself, set during deck/encounter composition.

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
T1 (wolf) attacks A (the fumbler who rolled Nat 1) → A passively defends
  → If A is killed, game over. If A survives, continue:
1: B's action → 2: A's action → 3: B's action → 4: A's action
5: B's action → 6: A's action
T2 (bandit) → round winner gets 1 bonus stack to repel
  → If repelled + killed: winner keeps loot + pot
  → If repelled + bandit survives: winner keeps pot, bandit → beginning next round
  → If failed: winner loses pot to opponent, bandit → beginning next round
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
CHARACTER STACK = CharPerson + [Apparel (head, body, feet, back, ring)] + [Hand items (handL, handR — max 2)] + [Magic Focus]
  Hand items: 1-handed weapon ×2 (dual wield), OR 2-handed weapon (fills both), OR weapon + shield
```
- Always in play — never placed on the action bar
- Its modifiers (DEF from armor, ATK from weapon, stat bonuses from gear) apply to ALL action stacks automatically
- Equipment changes happen **between rounds only** during the Equip Phase (free, no AP or Energy cost). You cannot change equipment mid-round
- Visual: a vertical card stack on the left (your character) or right (opponent) sidebar

**Action Stack** — placed on the Action Bar, one per AP spent:
```
ACTION STACK = CoreCard(s) + [ModifierCards...]
```
- **Core cards:** The action itself, potentially consumable. Examples:
  - Attack card (returned to hand after round)
  - Spell card (reusable, always returned) + Reagent (consumed)
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
Action Stack on bar: [Fireball spell (reusable)] + [Fire Crystal reagent (consumed)] + [Imperial Mastery skill +2]
Roll = 1d20 + MAG(16) + 3(staff) + 2(skill) = 1d20 + 21
Energy cost: 4 (Fireball) — on hit: 3 fire damage, ignores 2 DEF
```

**Use Item — Heal (Position 2):**
```
Character Stack (sidebar): Cleric + Chainmail (DEF +3) + Mace (ATK +2, parry +1)
Action Stack on bar: [Use Item card] + [Health Potion (consumed, +5 HP)]
Passive defense still active: 1d20 + END(12) + 3(armor) + 1(parry) = 1d20 + 16
Also restore 5 HP (potion consumed whether needed or not). Overheal caps at 20.
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
On critical success (Nat 20): also draw 1 card from the Treasure Vault
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

Inserted stacks are built from cards in hand and placed immediately (IRL: no planning time, just play the cards; online: quick-place UI with 10-second timer).

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
Position 2 (Player B): Use Item action → apply consumable effect (heal, buff, etc.)
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
Target:         1d20 + Passive Armor DEF + Parry bonus (if applicable)

Difference = Active player total - Target total
```

**Defense model — passive armor + weapon parry:**

Defense is always **passive** through equipped armor and weapon. There is no "Defend action stack" that takes up a bar position. Defense is calculated automatically whenever a player is targeted by an attack:

1. **Passive armor defense (always active):** `1d20 + END + equipped Apparel DEF`. This applies automatically whenever a player is targeted. No action required — it comes from the Character Stack.

2. **Weapon parry bonus (if weapon has parry property):** Some weapons have a **parry** property (swords, shields, staffs, etc.). If the defender has a parry-capable weapon equipped, its parry bonus is added to the defense roll automatically. This is a weapon stat, not a separate card or action.
   - Example: Short Sword has `parry: +2`. When the wielder is attacked, their defense roll includes the +2 parry bonus.
   - Shields have high parry values (Shield: `parry: +4`). This is their primary function — they boost passive defense.
   - A skill modifier card with a combat skill that includes a parry bonus can be placed adjacent to the weapon in the Character Stack to further increase the parry value.
   - Weapons without a parry property (bows, wands, two-handed hammers) provide no parry bonus.

3. **Dual wield parry:** If dual wielding, only the off-hand weapon's parry value applies (you can't parry with the weapon you're attacking with). A common loadout: sword (ATK) in primary hand + shield (parry) in off-hand.

**Defense roll summary:**
| Scenario | Defense Roll |
|----------|-------------|
| No weapon or weapon has no parry | `1d20 + END + Apparel DEF` (passive only) |
| Weapon with parry equipped | `1d20 + END + Apparel DEF + Weapon parry bonus` |
| Weapon + combat skill modifier | `1d20 + END + Apparel DEF + Weapon parry + Skill bonus` |
| Shield in off-hand | `1d20 + END + Apparel DEF + Shield parry` |

### Outcome Table

| Difference | Outcome | Effect |
|-----------|---------|--------|
| +10 or more | **Critical Hit** | Double damage. Defender's armor loses 2 durability. **Defender drops 1 item** (not apparel) from character stack → goes to pot. |
| +5 to +9 | Solid Hit | Full damage. Defender's armor loses 1 durability. |
| +1 to +4 | Glancing Blow | Half damage (round down). No durability loss. |
| 0 | Stalemate | No damage to either side. |
| -1 to -4 | Deflected | No damage. Attacker's weapon loses 1 durability. |
| -5 to -9 | Countered | Defender deals half their weapon damage to attacker. Attacker's armor applies normally (can reduce to 0). |
| -10 or less | **Critical Counter** | Defender deals full weapon damage to attacker. Attacker's armor applies normally. **Attacker drops 1 item** (not apparel) → goes to pot. **Attacker loses next action** — their next unresolved action stack on the bar is disabled (removed, cards return to hand). |

**Natural 20 rule (auto-success):** A raw d20 roll of 20 **always succeeds**, regardless of modifiers. The action achieves at least a Solid Hit (or the equivalent success tier for non-combat actions). Additionally, the outcome is upgraded by one tier (e.g., Solid Hit becomes Critical Hit). Applies to both attacker and defender. A Natural 20 cannot be negated by any modifier math.

**Natural 1 rule (auto-fail):** A raw d20 roll of 1 **always fails**, regardless of modifiers. The action achieves at worst a Deflected result (or the equivalent failure tier for non-combat actions). Additionally, the outcome is downgraded by one tier (e.g., Deflected becomes Countered). A Natural 1 by the attacker also causes them to drop 1 item (not apparel) into the pot. Fumbles hurt. A Natural 1 cannot be rescued by any modifier math. **Critical fail armor bypass:** When counter damage is triggered by a Natural 1, the attacker's armor does NOT reduce the incoming counter damage — the hit goes straight through.

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
Armor reduction = Defender's total DEF (from Character Stack Apparel + parry bonus if applicable)

For NON-CRITICAL outcomes:
  Net damage = max(0, base damage - armor reduction)
  → Armor CAN fully absorb damage (reduce to 0). High DEF exceeding damage = no damage taken.

For CRITICAL outcomes (Critical Hit):
  Net damage = max(1, base damage - armor reduction) × 2
  → Criticals always deal at least 1 damage, doubled. Armor cannot fully block a critical.

Apply outcome multiplier:
  Critical Hit: net damage × 2 (min 2 damage — criticals always hurt)
  Solid Hit: net damage × 1 (can be 0 if armor absorbs all)
  Glancing Blow: floor(net damage × 0.5) (can be 0)
```

**Armor reduction cap:** Defense can exceed incoming damage on non-critical hits — the attack is simply absorbed by armor. This makes heavy armor builds viable as damage sponges. However, **critical hits always penetrate** — at least 1 base damage gets through before the ×2 multiplier, ensuring even the tankiest character takes a minimum of 2 damage from a critical.

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
- Players defend with passive armor DEF (+ parry look-ahead if applicable)
- Reduce encounter HP by damage dealt. At 0 HP, encounter is defeated → collect loot
- All damage is applied **immediately** — if a player reaches 0 HP during any position's resolution, the game ends right there (mid-round defeat)
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
   - NPC personality, alignment, and backstory from the real `olio.charPerson` record (drawn from population)
   - Reputation score between player and NPC (from `olio.interaction` history — persists across sessions)
   - Current game context (encounter state, needs, nearby threats)
   - The NPC's actual stats influence dialogue (high-CHA NPCs are more eloquent; low-INT NPCs speak simply)
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
5. **Reusable**: All spell cards are reusable — they return to hand after resolution. Energy is spent at placement regardless of success or failure (use-it-or-lose-it for Energy, not for the card itself)

### Casting Requirements

To play a Magic Effect card, your stack must contain:

```
[Character] + [Action] + [Skill card matching the required Skill Type] + [Magic Effect card]
```

**All three requirements must be met:**
1. A Skill card of the correct type (Imperial, Undead, or Psionic) is in the stack
2. The Character's relevant stat meets or exceeds the Magic Effect's minimum
3. The Character has enough Energy to pay the cost

If any requirement is not met, the Magic Effect card fizzles — Energy is still spent, but the spell card returns to hand (all spells are reusable). This is use-it-or-lose-it for Energy, not for the card.

### Multi-Type Magic Effects

Some powerful Magic Effect cards require **two** skill types (e.g., "Imperial + Psionic"). The caster needs:
- A Skill card for each required type (uses 2 of their 4 skill slots)
- Stats meeting BOTH minimums
- Enough Energy for the (usually higher) cost

### Sample Magic Effect Cards

| Name | Skill Type | Min Stat | Energy | Effect | Reusable |
|------|-----------|----------|--------|--------|----------|
| Fireball | Imperial | MAG 12 | 4 | 3 fire dmg, ignores 2 DEF | Yes |
| Ice Wall | Imperial | MAG 10 | 3 | +5 DEF this round, negates Flee attempts against you | Yes |
| Raise Thrall | Undead | INT 14 | 5 | Defeated encounter becomes your ally for 3 rounds (fights with its original stats) | Yes |
| Entropy Touch | Undead | INT 12 | 3 | Target's equipped weapon loses 3 durability | Yes |
| Mind Read | Psionic | INT 12 | 2 | See one target player's hand (or reveal encounter's loot before fighting) | Yes |
| Telekinetic Slam | Psionic | INT 14 | 4 | 3 force dmg + push target (they cannot attack you next round) | Yes |
| Soul Drain | Undead + Psionic | INT 16 | 6 | Deal 3 dmg and restore 3 HP to self | Yes |
| Arcane Storm | Imperial + Psionic | MAG 14, INT 14 | 7 | 5 dmg to ALL enemies in current encounter | Yes |

### Magic in RL Play

Magic resolution uses the same dice system with magic-specific modifiers:

```
Magic attack: 1d20 + MAG mod (Imperial) or INT mod (Undead/Psionic) + Skill mod
vs target: 1d20 + END mod (resist physical magic) or INT mod (resist mental magic)
```

On hit: apply the Magic Effect's stated damage/effect. On miss: effect fizzles but Energy is still spent. The spell card returns to hand regardless of outcome (all spells are reusable).

---

## Needs & Survival

### Need Track Mechanics

The three need tracks create ongoing pressure independent of encounters:

| Track | Max Value | Drain | At Zero |
|-------|----------|-------|---------|
| HP | **20** (flat, all characters) | Combat damage, hazards | **Defeated immediately.** Game ends mid-round if this happens during resolution. No waiting for cleanup. |
| Energy | **MAG stat** (Imperial) or **INT** (Undead/Psionic) | Spell costs, special action costs | Cannot cast spells or use Energy-costing actions. Can still Attack (weapon), Talk, Flee, Investigate, Rest. |
| Morale | **20** (flat, all characters) | -2 on failed actions, -3 on ally defeat | Cannot Talk or cooperate. -2 to all rolls (despair). |

### Restoring Needs

| Track | Restoration Methods |
|-------|-------------------|
| HP | Health Potion (+5), Bandage (+2), Rest action (+2), Life magic (varies), round recovery (winner +5, loser +2). **Overheal caps at 20** — use-it-or-lose-it. |
| Energy | Rest action (+3), Mana Potion (+5), sleep event (+full). **Overheal caps at max (MAG or INT stat).** |
| Morale | Successful Talk (+3), Ally victory in combat (+2), Rest action (+1), finding loot (+1) |

---

## AI Modes

### Mode 1: AI as Character Opponent

The AI controls a full character with its own deck, following the same rules as the player — including initiative, AP, and action bar placement.

**Online implementation:**
- AI opponent is picked or randomly drawn from the deck's character pool (real `olio.charPerson` records with stats, portraits, personality, and narrative data)
- If a specific opponent hasn't been chosen in the deck builder, one is randomly selected from the deck's characters, excluding the player's character
- Opponent deck built from the same starter deck rules, using the opponent's actual stats and equipment
- Each round, the AI:
  1. Rolls initiative (server-side 1d20 + AGI)
  2. Receives a condensed game state and its available cards
  3. LLM selects and orders action stacks based on AP, hand, and opponent's visible character stack
  4. Stacks placed on the action bar at the AI's assigned positions
  5. During resolution, the AI responds to mid-round disruptions (see AI Mid-Turn Response below)
- AI personality profile influences strategy (aggressive AI front-loads attacks; cautious AI reserves defense stacks for later positions)

**IRL equivalent:** In tabletop play, another player controls the opponent. For solo IRL play, a **printed decision table** on a reference card determines the opponent's behavior — no online service or LLM involved:

```
Solo Opponent Priority (printed reference card, check in order):
1. HP ≤ 4 (20%)  → Flee + Use heal item if available
2. Enemy HP ≤ 6 (30%) → Attack (best weapon)
3. Has magic + Energy ≥ spell cost → Magic Attack
4. Default → Attack with best available weapon
Assign stacks to positions in priority order.
Roll dice for the opponent's actions using the same rules.
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

**IRL equivalent:** In tabletop play, one player acts as GM. They draw encounter cards, place encounter stacks on the bar, and roleplay NPCs. The GM does not have their own character or deck. No online services are needed — the human GM makes all decisions that the LLM handles online.

### Mode Switching

**Online**, the player selects mode at game start:
- **"Play vs AI"** → Mode 1 (AI as opponent)
- **"Story Mode"** → Mode 2 (AI as GM)
- Can switch between modes between sessions (campaign mode) but not mid-session

**IRL**: Mode is determined by how many players are at the table:
- **2 players, no GM** → Mode 1 equivalent (player vs player, using printed opponent decision table for encounter behavior)
- **2+ players + 1 GM** → Mode 2 equivalent (GM controls encounters and narrates)
- No AI, LLM, or online services involved — all decisions are made by human players

### AI Stack Selection — Condensed LLM Protocol

The AI opponent (Mode 1) and encounter behavior (Mode 2) both use a compact, structured prompt for stack selection and mid-turn response. These prompts are kept small to minimize latency and token usage.

**Round Decision Prompt (sent at the start of each Placement Phase):**

```json
{
  "type": "placement",
  "round": 7,
  "initiative": { "winner": "ai", "aiRoll": 18, "playerRoll": 12 },
  "ai": {
    "ap": 4, "energy": 10, "hp": 16, "morale": 18,
    "charStack": "Goblin Warlord | Spiked Plate (DEF+5) | War Axe (ATK+6) | Battle Rage skill",
    "hand": [
      { "id": "a1", "type": "action", "name": "Attack", "energyCost": 10 },
      { "id": "a2", "type": "action", "name": "Flee", "energyCost": 5 },
      { "id": "s1", "type": "skill", "name": "Intimidate", "mod": "+3 CHA" },
      { "id": "c1", "type": "consumable", "name": "Rage Potion", "effect": "+3 ATK 1 round" },
      { "id": "m1", "type": "magic", "name": "Fear Aura", "cost": 15, "insert": 1 }
    ]
  },
  "player": {
    "charStack": "Elf Ranger | Leather Armor (DEF+2) | Longbow (ATK+5, ranged)",
    "needs": { "hp": 14, "energy": 12, "morale": 16 }
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
    { "id": "a2", "type": "action", "name": "Flee", "energyCost": 5 }
  ],
  "aiEnergy": 10
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
│  │Atk  │ │Flee │ │Rest │ │Heal │ │Sword│ │+1ATK│ │+2Par│       │
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
- **Auto-arrange:** Button in the header (online only) — calls the AI to suggest optimal stack placement for all AP. Player can accept, modify, or dismiss. IRL: players arrange cards themselves.
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

### IRL Physical Layout — Character Wheel + Action Bar

The tabletop layout is a **circle + rectangle**: a rotating **Character Wheel** on the left, a horizontal **Action Bar** extending from it to the right, with resource piles, hand areas, and a discard pit around the edges.

```
            ACTIONS (hand)             SCENARIOS              PIT / DEBRIS FIELD
            ┌─────┐ ┌─────┐           ┌───────┐             ┌─────┐ ┌─────┐
            │ Atk │ │Skill│           │Encntr │             │ Pot │ │Disc.│
            │     │ │     │           │ Deck  │             │cards│ │pile │
            └─────┘ └─────┘           └───────┘             └─────┘ └─────┘
                                                              ITEMS (hand)
       ╭──────────────╮                                      ┌─────┐ ┌─────┐
      ╱    CHARACTER    ╲    ACTION BAR (radiating stepped)   │Sword│ │Potn │
     │      WHEEL        │   ┌───────┬───────┬───────┬─────┐ │     │ │     │
     │  ┌────┐  ┌────┐   │   │ Pos 1 │ Pos 2 │ Pos 3 │ ... │ └─────┘ └─────┘
     │  │ P1 │  │ P2 │   │──▸│Attack │ Magic │ Skill │     │
     │  │Armr│  │Armr│   │   │  +3   │       │  +1   │     │
     │  │Weap│  │Weap│   │   └───────┴───────┴───────┴─────┘
     │  └────┘  └────┘   │       ▲ resolution marker
      ╲   ◎ (pivot)     ╱
       ╰──────────────╯         THREATS
                                ┌─────┐ ┌─────┐ ┌─────┐
       RESOURCES                │Threat│ │Threat│ │Threat│
       ● ● ● ● (HP coins)      └─────┘ └─────┘ └─────┘
       ◆ ◆ ◆ (Energy coins)
```

**Character Wheel:**
- A circular cardboard wheel (printed as part of the deck) or any lazy-susan-style rotating surface
- Each player's character stack is arranged radially — charPerson card on top, apparel and weapon cards fanned around it
- The wheel **rotates to mark the active player** during initiative order. Whoever is at the "12 o'clock" position (or aligned with the action bar) is the active player
- In multiplayer (3+ players), all character stacks sit on the wheel. Rotate after each turn in the Draw & Placement Phase
- The wheel's center pivot can hold a round counter token

**Action Bar:**
- Extends horizontally from the wheel to the right
- Stepped slots (marked on a play mat, or just table space) for each action bar position
- Core card face-up on top of each slot, modifier cards tucked partially underneath
- A physical resolution marker (token/coin/d20 on side) advances left to right during resolution
- Beginning threats placed to the left of position 1; end threats placed to the right of the last position

**Resource Area (below wheel):**
- Health denomination cards (+1, +2, +5) in a pile — pile size = current HP
- Energy denomination cards (+1, +2, +5) in a separate pile — pile size = current Energy
- Morale tracked via a separate small pile or a dial/counter

**Pit / Debris Field (right side):**
- The pot: cards anted or dropped during the round collect here
- Discard pile: consumed items, resolved encounters
- Does not need to be a specific shape — any designated table area works

**Hand Areas (top left / top right):**
- Action and skill cards fanned in one area (top left, near the wheel)
- Item, equipment, and consumable cards spread in another area (top right, near the pit)
- Players organize their hand however they prefer — no enforced layout

**Scenario / Encounter Deck (top center):**
- Shared encounter deck face-down
- Scenario cards (if GM mode) placed face-up nearby
- Drawn cards go onto the action bar or into the threat row

**Threat Row (below action bar):**
- Active threat cards laid out in a row below the bar
- Visible to all players — threat source, difficulty, and behavior text readable

### Touch Screen & Mobile UX

All gameplay mechanics must work on touch screens (tablets and phones) with finger-sized tap targets and touch-native drag. No interaction should require hover, right-click, or keyboard shortcuts to be functional.

**Touch target sizing:**

| Element | Minimum Size | Notes |
|---------|-------------|-------|
| Card in hand tray | 64×96px (min), 80×120px recommended | Finger-selectable, scrollable tray |
| Action bar position | 80×120px (min) | Drop target for drag |
| Buttons (Ready, Reimage, Open in AM7) | 44×44px (min tap area) | Per Apple/Google HIG |
| Needs bars, AP counter | 44px height (min) | Tappable to expand detail |
| D-pad / movement controls | 56×56px per direction button | Prominent, easily tappable |
| Card type filter tabs | 44px height, full-width | Large touch targets in hand tray |

**Touch drag-and-drop:**
- **Long-press (150ms) to pick up** a card — short tap selects/previews, long-press initiates drag
- **Drag ghost:** Semi-transparent card image follows finger, with a subtle shadow beneath
- **Drop zones glow** when a dragged card enters them (same green/red highlighting as mouse)
- **Snap-to-target:** Releasing within 20px of a valid drop zone snaps the card into place
- **Cancel drag:** Drag off-screen or back to original position to cancel
- **Scroll vs drag conflict:** Hand tray scrolls on horizontal swipe. Long-press on a card suppresses scroll and initiates drag. Visual feedback (card lifts/scales up slightly) confirms drag mode.

**Touch alternatives for hover/right-click actions:**

| Desktop Action | Touch Equivalent |
|---------------|-----------------|
| Hover to see card detail | Tap to select, detail shown in side panel or modal |
| Right-click "Suggest Modifiers" | Long-press on action bar slot → context menu |
| Keyboard shortcuts (1-9, A, M) | Visible on-screen buttons during placement phase |
| Mouse drag card between stacks | Same: long-press + drag |
| Hover ↻ reimage icon on card | Tap card → detail view shows [↻ Reimage] button |
| Scroll hand tray with mousewheel | Horizontal swipe gesture |

**Responsive layout adjustments:**

| Screen Width | Layout Change |
|-------------|--------------|
| ≥1200px (desktop) | Full three-column layout as documented |
| 768–1199px (tablet landscape) | Character stack and opponent stack collapse to narrow sidebars. Action bar and hand tray take full width. |
| <768px (phone/tablet portrait) | Single-column stack layout: Header → Character/Opponent toggle → Action Bar (horizontally scrollable) → Hand Tray. Character stack accessible via slide-out drawer. |

**Specific touch-friendly patterns:**
- **Placement phase:** Hand tray cards are large enough to drag. Action bar slots show "+" drop zones when empty. Pre-built "Quick Arrange" button auto-places cards with one tap.
- **Resolution phase:** "Next" button advances to next position (no auto-advance that could be missed on small screens). Each position's outcome panel has large Dismiss button.
- **Chat/Talk:** Standard mobile chat UI — full-screen modal with bottom input bar, send button ≥44px.
- **Initiative roll:** Tap-to-roll with large dice animation (swipe/shake to roll as optional flair).
- **Deck browser / image review:** Grid of cards, scrollable. Tap to select, action bar at bottom with [↻ Reimage] [Open in AM7] buttons at full-width 44px height.

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

This system is online-only — IRL tabletop play has no narration system. Players narrate their own actions, describe outcomes, and roleplay encounters themselves.

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

**Voice config fallback:**
- If no voice profile is configured for the narrator → show a one-time warning: "Voice narration not configured. Configure a voice profile in deck settings to enable spoken narration."
- If the user never specifies a voice profile (i.e., no voice config exists at all) → **skip voice synthesis entirely**. The narration system still generates text via LLM and displays subtitles, but no audio is produced and no voice endpoints are called. This is the default for new decks — voice is opt-in.
- If a voice profile IS configured but the synthesis call fails → show error inline ("Voice synthesis failed"), fall back to text-only subtitles for that narration point, continue gameplay without blocking.

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
| ✅ Critical Hit — 5 damage      |
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

### Theme-Mandatory Regeneration

**All entity images are regenerated fresh for every deck build.** Characters, apparel, items, animals, and wearables all receive new images regardless of whether they had images before. This is required because:

1. **Theme consistency** — a character portrait generated with generic prompts needs to be re-rendered in the theme's art style (`{theme.artStyle.promptSuffix}`).
2. **Visual anchor reference** — the `gameBackground` establishes the color palette and atmosphere. Entity images generated with the background as an img2img reference at low weight (0.1–0.15) inherit the theme's hue and mood.
3. **Prompt enrichment** — card game prompts are richer (they include equipped gear, theme setting context, card composition cues like "RPG card art, centered, no text").

| Entity Type | Always Regenerated Per Deck? | Why |
|-------------|---------------------------|-----|
| `charPerson` (player, opponent, NPCs) | Yes | Theme art style + equipped theme apparel + background reference |
| `apparel` (mannequin images) | Yes | Theme-specific clothing must match art style |
| `wearable` (individual icons) | Yes | Must match theme palette; flat-lay style varies by theme |
| `item` (weapons, consumables) | Yes | Items may be shared across themes but need style-specific rendering |
| `animal` (creatures, threats) | Yes | Creature art must match theme atmosphere (fantasy vs sci-fi vs post-apoc) |
| `deckAssets` (backgrounds, icons) | Yes | Theme-defining assets, always regenerated |
| `cardBack`, `cardStyle` | Yes | Per-theme by definition |
| `afterAction` scenes | No — generated on demand during gameplay | Each is unique to the situation |

**Existing images are NOT reused.** Even if a character already has a portrait, the deck build generates a new one in the theme's style. The original image is preserved — the card game image goes to `~/CardGame/{deckName}/images/{type}/{name}/`.

**This means a full deck build queues 60–90+ images.** The queue is designed for this volume — see [Image Generation Queue Manager](#image-generation-queue-manager) for the review workflow that lets players spot-check results and re-queue any they don't like.

#### Card Image Source Map

| Card Type | Image Content | Generation Source | Endpoint |
|-----------|--------------|-------------------|----------|
| **Character** | Portrait of the character wearing their equipped gear | `sdConfig.charPerson` (Config 3) | `POST /rest/olio/charPerson/{id}/reimage` |
| **Apparel** | Clothing/armor displayed on a mannequin form | **Existing mannequin pipeline** — `NarrativeUtil.getMannequinPrompt()` → `SDUtil.generateMannequinImages()` | `POST /rest/olio/apparel/{id}/reimage` |
| **Wearable (icon)** | Single garment/piece isolated on flat background | **New wearable icon pipeline** — `NarrativeUtil.getWearableIconPrompt()` → `SDUtil.generateWearableIcon()` | `POST /rest/olio/wearable/{id}/reimage` |
| **Item (Weapon)** | Isolated weapon on dark background | `sdConfig.item` subtype `weapon` (Config 5) | `POST /rest/game/asset/generate` |
| **Item (Consumable)** | Potion, food, material on dark background | `sdConfig.item` subtype `consumable` (Config 5) | `POST /rest/game/asset/generate` |
| **Action** | Themed action-type illustration (attack = clashing swords, defend = raised shield, etc.) | `sdConfig.item` subtype `action` (Config 5) | `POST /rest/game/asset/generate` |
| **Talk** | Themed social/diplomatic illustration | `sdConfig.item` subtype `action` (Talk variant) (Config 5) | `POST /rest/game/asset/generate` |
| **Encounter (Threat)** | Creature or hostile NPC in aggressive pose | `sdConfig.animal` (Config 4) for beasts; `sdConfig.charPerson` (Config 3) for humanoid NPCs | `/rest/olio/animal/{id}/reimage` or `/rest/olio/charPerson/{id}/reimage` |
| **Encounter (Event/Discovery)** | Scene depicting the event or location | `sdConfig.item` subtype `encounter` (Config 5) | `POST /rest/game/asset/generate` |
| **Skill** | Symbolic emblem or rune representing the skill | `sdConfig.item` subtype `skill` (Config 5) | `POST /rest/game/asset/generate` |
| **Magic Effect** | Spell energy, particle effect, ethereal visual | `sdConfig.item` subtype `magicEffect` (Config 5) | `POST /rest/game/asset/generate` |

**Apparel uses the mannequin pipeline** — not the generic item config. The existing `NarrativeUtil.getMannequinPrompt()` generates prompts showing clothing on a "full body retail mannequin" at cumulative wear levels (base layer → under → suit → outer). This produces clothing images that clearly show the garment's shape, material, and coverage without human features. The mannequin negative prompt excludes "human face, realistic skin, hair, eyes, hands" to keep focus on the apparel itself. Generated images are stored in `~/CardGame/{deckName}/images/apparel/{apparelName}/` with wear level metadata.

**Wearable icons** use a new single-item pipeline modeled on the mannequin approach — see [Wearable Icon Generation](#wearable-icon-generation) below. Each individual `olio.wearable` (a single boot, a helmet, a glove) gets its own icon image for use as equipment slot icons, character stack detail, and card modifier overlays.

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
- Stored at `~/CardGame/{deckName}/images/cardBacks/{cardType}.png`

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
   └─ All stored under ~/CardGame/{deckName}/images/cardStyle/

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
      "note": "Generated once per theme per action type (7 action + 1 talk = 8 images)"
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
| Watch | action | "Watch, vigilant sentinel scanning the horizon, keen eyes, watchtower silhouette, dark fantasy, oil painting style, card illustration, atmospheric composition" |
| Talk | action | "Talk, diplomatic meeting between two figures, candlelit table, scrolls and seals, dark fantasy, oil painting style, card illustration, dynamic composition" |
| Sandstorm | encounter | "Sandstorm, swirling desert storm engulfing ruins, environment scene, arid wasteland, dark fantasy, oil painting style, dramatic lighting" |
| Swordsmanship | skill | "Swordsmanship, crossed swords emblem, combat mastery, item icon, isolated on dark background, skill emblem, glowing rune, dark fantasy, oil painting style" |
| Fireball | magicEffect | "Fireball, sphere of flames, explosive fire magic, item icon, isolated on dark background, spell effect, magical energy, ethereal, dark fantasy, oil painting style" |

**Apparel card images** use the existing mannequin pipeline:
```
POST /rest/olio/apparel/{objectId}/reimage
  └─ NarrativeUtil.getMannequinPrompt(apparel, wearLevel)
     → "8k highly detailed ((professional fashion photography)) of a ((full body retail mannequin)) displaying: {cumulative clothing description}"
  └─ SDUtil.generateMannequinImages(user, imagePath, apparel, ...)
     → Generates at 1024×1024 + hires, stores in ~/CardGame/{deckName}/images/apparel/{name}/
  └─ Negative prompt: "human face, realistic skin, hair, eyes, hands, fingers, feet, toes, skin texture"
```

Uses `POST /rest/game/asset/generate` for non-apparel items.

#### Wearable Icon Generation

Individual `olio.wearable` items need their own icon images for use in the character stack equipment slots, card modifier overlays, and the equip/unequip UI. The apparel mannequin pipeline shows the **whole outfit** — the wearable icon pipeline shows a **single garment piece** in isolation.

**Pattern:** Follows the same architecture as the mannequin pipeline — a `NarrativeUtil` method builds the prompt from the wearable record's name, fabric, color, and location fields, and `SDUtil` generates and stores the image.

```
POST /rest/olio/wearable/{objectId}/reimage
  └─ NarrativeUtil.getWearableIconPrompt(wearable, sdConfig)
     → "8k highly detailed ((product photography)) of a single ((isolated {wearableName})),
         {color} {fabric}, {theme.artStyle.promptSuffix}, flat lay on dark background,
         centered, clean edges, no model, no mannequin, single item only"
  └─ SDUtil.generateWearableIcon(user, imagePath, wearable, ...)
     → Generates at 512×512, stores in ~/CardGame/{deckName}/images/wearables/{name}/
  └─ Negative prompt: "human, person, mannequin, model, multiple items,
     text, watermark, blurry, low quality, body parts"
```

**Key differences from the mannequin pipeline:**

| | Mannequin (Apparel) | Wearable Icon |
|---|-------------------|---------------|
| **Shows** | Full outfit (cumulative wearables at/below a wear level) on a mannequin form | Single garment piece, isolated, no mannequin |
| **Dimensions** | 1024×1024 + hires (CSS crops to card aspect, shows full body mannequin) | 512×512 (square — icon format for slots and overlays) |
| **Prompt style** | "professional fashion photography of a full body retail mannequin displaying..." | "product photography of a single isolated {item}, flat lay on dark background..." |
| **Source method** | `NarrativeUtil.getMannequinPrompt(apparel, wearLevel, sdConfig)` | `NarrativeUtil.getWearableIconPrompt(wearable, sdConfig)` |
| **SD method** | `SDUtil.generateMannequinImages()` | `SDUtil.generateWearableIcon()` |
| **Storage** | `~/CardGame/{deckName}/images/apparel/{apparelName}/` | `~/CardGame/{deckName}/images/wearables/{wearableName}/` |

**Prompt construction** (mirrors the mannequin `describeOutfitForMannequin` pattern but for a single item):

```java
// NarrativeUtil.getWearableIconPrompt(wearable, sdConfig)
//
// Reads from the wearable record:
//   - name:     "leather boots"
//   - fabric:   "leather"
//   - color:    { name: "brown" }
//   - location: ["foot", "ankle"]
//
// Builds prompt:
//   "8k highly detailed ((product photography)) of a single
//    ((isolated brown leather boots)), foot and ankle wear,
//    {theme.artStyle.promptSuffix}, flat lay on dark background,
//    centered, clean edges, no model, no mannequin, single item only"
```

**When wearable icons are generated:**

| Trigger | Notes |
|---------|-------|
| Theme apparel swap (Step 3b) | Each new wearable created during apparel swap gets an icon queued |
| Custom item creation (Step 4b) | New wearable items get icons alongside their mannequin/apparel images |
| Equip/unequip during game | If a wearable has no icon yet, generate on equip |
| Manual reimage | Player clicks reimage on the equipment slot |

**Generation priority:** Wearable icons are queued at the same priority as apparel (after card backs and deck assets). They are 512×512 icons and fast to generate. For a typical starter outfit of 6–8 wearable pieces, the full set generates quickly.

**Fallback:** Before wearable icons generate, equipment slots display a **generic silhouette icon** for the wearable's location (head → helmet outline, foot → boot outline, torso → shirt outline, etc.). These SVG silhouettes are built into the client and tinted per card type color. Once the wearable icon generates, it replaces the silhouette with a smooth crossfade.

**Usage in the card game UI:**

| Context | How the wearable icon appears |
|---------|------------------------------|
| **Character Stack sidebar** | Equipment slots show wearable icons in a paper-doll layout (head, body, hands, feet, ring, back) |
| **Equip/unequip dialog** | Grid of owned wearables with icons, drag to equip slots |
| **Card modifier overlay** | When a wearable provides stat bonuses (e.g., +2 DEF from boots), the icon appears as a small badge on the action stack |
| **Loot display** | When a wearable drops as loot (from threat defeat or discovery), the icon appears in the loot preview |
| **Print** | Wearable icons printed at 32×32 in equipment slot diagrams on the character reference card |

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
      "needs": ["HP", "Energy", "Morale", "AP"],
      "usage": "Need bar icons on sidebar panels"
    },
    "actionTypeIcons": {
      "dimensions": { "width": 64, "height": 64 },
      "promptTemplate": "{actionName} action icon, {theme.artStyle.promptSuffix}, symbolic emblem, bold, 64px",
      "negativePrompt": "text, person, realistic",
      "perAction": true,
      "actions": ["Attack", "Flee", "Rest", "Use Item", "Investigate", "Craft", "Watch", "Talk"],
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
  "generationOrder": "gameBackground FIRST → cardBack SECOND → all other assets",
  "referenceStrategy": {
    "anchor": "gameBackground",
    "defaultReferenceWeight": 0.25,
    "note": "The gameBackground is the single visual origin for the entire theme. It generates first and establishes palette, texture, and atmosphere. Every surface-level asset (card backs, help cover, pot, action bar, banners) uses it as an img2img reference at low weight (0.2–0.3). This ensures the game table, card backs, help booklet cover, and all decorative panels feel like a unified visual family without being copies of each other."
  },
  "regenerateOn": "theme-change",
  "storage": "~/CardGame/{deckName}/images/deckAssets/"
}
```

**Generation triggers:**
- New theme selected → generate all deck assets in batch
- Never regenerated during gameplay (stable UI elements)
- Can be regenerated manually via Batch Reimage → "Reimage Deck Assets"

**Generation order (reference chain):**

The `gameBackground` is the **single visual origin** for every theme. It generates first and sets the palette, texture, and atmosphere. Every large surface asset then references it via img2img, creating a coherent visual family:

```
gameBackground (generates first, 3 variants — the visual anchor)
    │
    ├─── cardBack ×8 types  (Config 1, ref weight 0.25)
    │       Card backs are the most-seen surface after the board.
    │       Generating them second ensures they harmonize with the
    │       background while still having their own distinct design.
    │
    ├─── potBackground       (ref weight 0.25)
    ├─── actionBarBackground (ref weight 0.25)
    ├─── titleBanner         (ref weight 0.2)
    ├─── phaseHeaders ×5     (ref weight 0.2)
    └─── rulesCover          (ref weight 0.3)
            Help content cover page — highest reference weight
            because the cover should feel closest to the game
            board aesthetic (same dimensions as a card back,
            used as the help panel header and print booklet cover).
```

**Why background first?** Generating background → card backs → covers in that order means each downstream asset inherits the same color palette and texture vocabulary. Without a shared visual anchor, independently generated assets often drift into incompatible palettes — a warm parchment background paired with a cold steel card back looks disjointed. The low reference weight (0.2–0.3) is enough to align hue/saturation without making every asset look like a filtered copy of the background.

Small icons (d20, stat, need, action, outcome, card type corners) do **not** use the background reference — they are too small for img2img to help and need crisp isolated shapes instead.

**Asset count per theme:**
| Asset Category | Count | Total Images |
|---------------|-------|-------------|
| Backgrounds (game, pot, action bar) | 3 types | 5 (3 game bg variants + pot + bar) |
| Title banner | 1 | 1 |
| d20 icon | 1 | 1 |
| Stat icons | 6 (STR, AGI, END, INT, MAG, CHA) | 6 |
| Need icons | 4 (HP, Energy, Morale, AP) | 4 |
| Action type icons | 8 | 8 |
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

During deck building (between Steps 1 and 6), the player can optionally customize any AI config for the selected theme. Each config type has a different editing approach based on whether AM7 has a native editor for it:

**Prompt Configs (`olio.llm.promptConfig`)** — opened via **object link**. The prompt config has a native AM7 form (`formDef.js`), so clicking the config name opens it in the AM7 object editor (same as the [Open in AM7] links on card types). The player edits the prompt template, system instructions, and evaluation criteria directly.

**Chat Configs (`olio.llm.chatConfig`)** — also opened via **object link**. Native AM7 form for model selection, temperature, token limits, etc. New chat configs are created using the **"Open Chat" chatConfig as a connection settings template** — the same pattern used by `cardGame.js` v1. The "Open Chat" template provides base connection fields (serverUrl, model, serviceType, apiKey, chatOptions values). The deck builder copies only value fields from this template (avoiding stale identity/organization refs from nested objects), then assigns deck-specific character references and purpose-specific settings (temperature, maxTokens, system prompt refs).

**SD Configs (`olio.sd.config`)** — opened in a **modal dialog** within the deck builder. SD configs have no direct AM7 editor form, so the deck builder provides a purpose-built modal:

```
┌────────────────────────────────────────────────────┐
│  SD CONFIG: high-fantasy.charPerson      [✕ Close] │
├────────────────────────────────────────────────────┤
│                                                     │
│  Prompt Template:                                   │
│  ┌─────────────────────────────────────────────┐   │
│  │ {race} {gender}, {physicalDescription},     │   │
│  │ {apparelDescription}, portrait,             │   │
│  │ {theme.artStyle.promptSuffix}, card art     │   │
│  └─────────────────────────────────────────────┘   │
│  Negative Prompt:                                   │
│  ┌─────────────────────────────────────────────┐   │
│  │ deformed, blurry, text, watermark ...       │   │
│  └─────────────────────────────────────────────┘   │
│  Dimensions: [512] × [768]                         │
│  CFG Scale: [7.5]  Steps: [30]                     │
│  Denoising: [0.75]  Seed: [_____]                  │
│  Sampler: [Euler a ▼]  Model: [sdXL_v10 ▼]        │
│  img2img Reference: [✓ Use gameBackground]         │
│  Reference Weight: [0.25]                           │
│                                                     │
│  [Preview] [Reset to Default] [Save]               │
└────────────────────────────────────────────────────┘
```

**SD Config storage options:** Since sdConfig is passed directly to the server and is stateless (no session context), it can be stored in two ways:
1. **As an AM7 object** (`olio.sd.config`) — standard storage, loaded by name each session. Shared across games using the same theme.
2. **Embedded in the deck snapshot** — the full sdConfig JSON is serialized into the deck's `data.data` object alongside card snapshots. This freezes the exact generation parameters with the deck, so replaying a saved game reproduces the same image style even if the global config has since changed.

The deck builder defaults to option 1 (reference by name). If the player enables "Lock configs to deck" in settings, all SD configs are embedded in the snapshot (option 2).

**Config Editor UI:**

```
┌────────────────────────────────────────────────────┐
│  AI CONFIGS for "High Fantasy"                      │
├────────────────────────────────────────────────────┤
│                                                     │
│  SD Configs (7):                                    │
│  ┌──────────────────────────────────────────────┐  │
│  │ charPerson     [Edit Modal] [Preview]        │  │
│  │ animal         [Edit Modal] [Preview]        │  │
│  │ item           [Edit Modal] [Preview]        │  │
│  │ cardBack       [Edit Modal] [Preview]        │  │
│  │ cardStyle      [Edit Modal] [Preview]        │  │
│  │ deckAssets     [Edit Modal] [Preview]        │  │
│  │ afterAction    [Edit Modal] [Preview]        │  │
│  └──────────────────────────────────────────────┘  │
│                                                     │
│  Prompt Configs (6):                                │
│  ┌──────────────────────────────────────────────┐  │
│  │ narrator          [Open in AM7] [Preview]    │  │
│  │ cardStyleComposer [Open in AM7] [Preview]    │  │
│  │ combatEval        [Open in AM7] [Preview]    │  │
│  │ interactionEval   [Open in AM7] [Preview]    │  │
│  │ aiOpponent        [Open in AM7] [Preview]    │  │
│  │ gmEncounter       [Open in AM7] [Preview]    │  │
│  └──────────────────────────────────────────────┘  │
│                                                     │
│  Chat / Model Configs (3):                          │
│  ┌──────────────────────────────────────────────┐  │
│  │ narrator           [Open in AM7]             │  │
│  │ playerChat         [Open in AM7]             │  │
│  │ cardStyleComposer  [Open in AM7]             │  │
│  └──────────────────────────────────────────────┘  │
│                                                     │
│  [✓ Lock configs to deck] (embed in snapshot)      │
│  [Use Defaults]              [Save All & Continue]  │
└────────────────────────────────────────────────────┘
```

**Customization flow:**
1. Player clicks "Customize AI Configs" in the deck builder
2. Three groups listed: SD Configs, Prompt Configs, Chat/Model Configs
3. **SD Configs** → click "Edit Modal" → opens modal dialog with all SD parameters. Edits are saved as a user-owned copy (`{deckName}.{purpose}.custom`) or embedded in the deck snapshot.
4. **Prompt / Chat Configs** → click "Open in AM7" → opens the AM7 object editor in a new tab/panel. Player edits using the native AM7 form. On return, the deck builder detects if the object was modified.
5. **Preview** button generates a sample output (SD: generates a test image; Prompt: shows rendered prompt with sample data)
6. **Save All & Continue** returns to the deck builder with all config choices locked in

**Custom config naming:** User copies use `{deckName}.{purpose}.custom`:
```
high-fantasy.charPerson.custom   → user's customized SD config for character portraits
high-fantasy.narrator.custom     → user's customized narrator prompt
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

When "Lock configs to deck" is **off** (default), configs are loaded fresh by name — prompt tuning, model upgrades, or SD parameter changes take effect on the next game session without any re-snapshot. When "Lock configs to deck" is **on**, SD configs are read from the embedded snapshot copy, ignoring any global changes.

### Generation Priority Queue

When multiple images need generation, the queue is ordered by config priority:

```
Priority 1a: deckAssets.gameBackground — generates FIRST, visual anchor for all theme art
Priority 1b: cardBack ×8 types — generates SECOND (ref gameBackground, weight 0.25)
Priority 1c: deckAssets (remaining) — rulesCover, pot, action bar, banners, icons (ref gameBackground)
Priority 2:  cardStyle frame elements (needed before any card can render properly)
Priority 3:  charPerson — player character portrait (most visible card)
Priority 4:  charPerson — opponent/NPC portraits
Priority 5:  item — equipped weapons, apparel (mannequin)
Priority 5b: wearable — individual wearable icons (512×512, same priority as apparel)
Priority 6:  encounter — as drawn from deck
Priority 7:  item — consumables, skills, magic effects in hand
Priority 8:  animal — creature encounter images
Priority 9:  afterAction — generated on demand after resolution
```

**Background → Card Backs → Covers:** The `gameBackground` at Priority 1a establishes palette and texture. Card backs (1b) generate next because they are the most-seen surface after the board itself — players see card backs constantly during draws, opponents' hands, and deck piles. The rules/help cover and other deck assets (1c) follow, all referencing the same background anchor. This three-stage cascade ensures every large surface in the game shares a unified visual identity.

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
   └─ Wearable:   POST /rest/olio/wearable/{id}/reimage (single-item icon, 512×512)
   └─ Animal:     POST /rest/olio/animal/{id}/reimage
   └─ All others: POST /rest/game/asset/generate (weapons, consumables, actions, skills, magic, encounters)

4. IMAGE STORAGE
   └─ All card game images stored under ~/CardGame/{deckName}/images/
       Characters:   ~/CardGame/{deckName}/images/characters/{characterName}/
       Apparel:      ~/CardGame/{deckName}/images/apparel/{apparelName}/
       Wearables:    ~/CardGame/{deckName}/images/wearables/{wearableName}/
       Animals:      ~/CardGame/{deckName}/images/animals/{creatureName}/
       Weapons:      ~/CardGame/{deckName}/images/items/{itemName}/
       Consumables:  ~/CardGame/{deckName}/images/items/{itemName}/
       Actions:      ~/CardGame/{deckName}/images/actions/{actionType}.png
       Encounters:   ~/CardGame/{deckName}/images/encounters/{encounterName}/
       Skills:       ~/CardGame/{deckName}/images/skills/{skillName}/
       Magic:        ~/CardGame/{deckName}/images/magicEffects/{spellName}/
       Card Backs:   ~/CardGame/{deckName}/images/cardBacks/
       Card Style:   ~/CardGame/{deckName}/images/cardStyle/
       Deck Assets:  ~/CardGame/{deckName}/images/deckAssets/

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

Cards without generated images use **Material Icons** (Google Material Symbols) or **emoji fallbacks** as placeholders. No custom gradient backgrounds — just the icon/emoji centered on the card's type-colored background with a subtle pulsing border to indicate pending generation.

| Card Type | Material Icon | Emoji Fallback | Background Color |
|-----------|--------------|----------------|-----------------|
| Character | `person` | 🧑 | Gold |
| Apparel | `checkroom` | 🛡️ | Silver |
| Item (Weapon) | `swords` | ⚔️ | Green |
| Item (Consumable) | `local_pharmacy` | 🧪 | Green |
| Action (Attack) | `flash_on` | ⚡ | Red |
| Action (Parry) | `shield` | 🛡️ | Red |
| Action (Flee) | `directions_run` | 🏃 | Red |
| Action (Other) | `play_arrow` | ▶️ | Red |
| Talk | `chat` | 💬 | Blue |
| Encounter (Creature) | `pest_control` | 🐺 | Purple |
| Encounter (NPC) | `person_alert` | ⚠️ | Purple |
| Encounter (Event) | `explore` | 🔍 | Purple |
| Skill | `stars` | ⭐ | Orange |
| Magic Effect | `auto_fix_high` | ✨ | Teal |
| Card Back | `style` | 🃏 | Theme color |
| Deck Asset | `image` | 🖼️ | Theme color |

**Icon sizing:** 48px (hand view), 96px (detail view), 24px (thumbnail/stack). Material Icon font loaded via `<link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined" rel="stylesheet">`. Emoji fallback used if Material Symbols fails to load.

**During generation:** The pulsing border remains. The icon/emoji is replaced by a small spinner overlay. Once the image arrives, it fades in over the placeholder.

All card types use pulsing borders during generation. Action and Talk cards generate themed illustrations during deck build (see Config 5 `action` subtype), so they also start as placeholders before their images are ready.

### Image Sizing

**Generation sizes** — all images are generated at one of two fixed sizes. CSS handles all display sizing via `object-fit: cover` and `object-position: center` to fit card dimensions/locations:

| Image Type | Generation Size | Notes |
|------------|----------------|-------|
| Card images (characters, apparel, animals, encounters, actions, skills, magic, card backs, card style, backgrounds, after-action, deck assets) | **1024×1024 + hires fix** | All card art and scene images generated at 1024×1024 with hires upscale. CSS crops/centers to card aspect ratio. |
| Icons (wearable icons, equipment slot icons) | **512×512** | Square icons for equipment slots, modifier overlays, etc. |

**Display sizes** — CSS scales the generated image to fit:

| Context | Display Size | CSS Fit |
|---------|-------------|---------|
| Card face (in-hand view) | 256×384 CSS | `object-fit: cover; object-position: center;` on 1024×1024 source |
| Card face (zoomed/detail) | 512×768 CSS | Same fit, larger display |
| Card thumbnail (stack view) | 64×96 CSS | Same fit, thumbnail scale |
| Equipment slot icon | 48×48 CSS | `object-fit: contain;` on 512×512 source |
| Print resolution | 744×1039 CSS | 2:3 at 300 DPI for 2.5"×3.5" — uses full 1024×1024 source for print quality |

**No pre-resizing** — the server generates at 1024×1024 (or 512×512 for icons) and the client handles all display scaling via CSS. This means one source image works for hand view, detail view, print, and any future layout changes.

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

Players can regenerate images for multiple cards at once — either the entire deck or filtered by card type. Batch reimage uses the same client-side queue as initial generation (see Image Generation Queue below).

**UI options:**
- **Reimage All** — Queues every card in the current deck for regeneration. Existing images stay visible until replaced.
- **Reimage by Type** — Select one or more card types and regenerate only those.
- **Reimage Card Backs** — Regenerate all 8 type-specific card back images.

All batch reimage items are added to the generation queue as `regen: true` entries, bypassing the image skip-check.

### Image Generation Queue

The image generation queue is a **simple client-side array** processed one item at a time. No server-side batch tracking — the client drives everything by calling individual reimage endpoints sequentially.

**Image storage:** All generated images are stored under the deck's group — no gallery involvement:
```
~/CardGame/{deckName}/images/
  ├── deckAssets/
  ├── cardBacks/
  ├── cardStyle/
  ├── characters/
  ├── apparel/
  ├── wearables/
  ├── items/
  ├── animals/
  ├── encounters/
  ├── actions/
  ├── skills/
  └── magicEffects/
```

**Skip-check on queue:** Before generating each image, the queue checks the deck image path for an existing image for that entity. If the image exists and the queue entry is NOT a regen request, the item is **skipped** — no generation call made. This is the key mechanism for:
- **Crash / refresh resilience** — if the browser closes mid-queue, restarting the queue just re-checks the image path, skips everything already generated, and continues from where it left off
- **Incremental builds** — adding new cards to a deck only generates images for the new entries
- **Manual reimage** — clicking ↻ on a card adds it as `regen: true`, which forces generation regardless of existing image

**Queue data structure:**

```javascript
let imageQueue = [];      // Array of queue entries, processed in order
let errorQueue = [];      // Failed items, available for retry
let completedQueue = [];  // Finished items with results, browsable

// Each queue entry:
{
    id: "queue-uuid",
    entityType: "charPerson",     // AM7 object type
    entityId: "am7-uuid",        // Source object ID
    entityName: "Aelindra",      // Display label
    imagePath: "characters/",    // Subfolder in ~/CardGame/{deckName}/images/
    regen: false,                // true = force regenerate even if image exists
    sdConfigKey: "charPerson",   // Which SD config to use
    status: "pending",           // pending | generating | completed | failed | skipped
    resultUrl: null,             // Thumbnail URL on completion
    error: null                  // Error message if failed
}
```

**Processing loop (client-side):**

```javascript
async function processQueue() {
    while (imageQueue.length > 0) {
        let item = imageQueue[0];

        // Skip-check: does the deck image path already have this image?
        if (!item.regen) {
            let exists = await checkDeckImage(item.entityId, item.imagePath);
            if (exists) {
                item.status = "skipped";
                item.resultUrl = exists.thumbnailUrl;
                completedQueue.push(imageQueue.shift());
                updateProgress();
                continue;
            }
        }

        // Generate
        item.status = "generating";
        updateProgress();
        try {
            let result = await reimageEntity(item.entityType, item.entityId, item.sdConfigKey);
            item.status = "completed";
            item.resultUrl = result.thumbnailUrl;
            completedQueue.push(imageQueue.shift());
        } catch (err) {
            item.status = "failed";
            item.error = err.message;
            errorQueue.push(imageQueue.shift());
        }
        updateProgress();
    }
}
```

**Queue Manager UI:**

```
+------------------------------------------------------------------+
|  IMAGE GENERATION                        [Pause] [Stop] [Resume] |
+------------------------------------------------------------------+
|  Progress: 23 / 87    ████████░░░░░░░░░░  26%                    |
|  Generating: "Iron Sword" (weapon)                [Spinner]       |
|  Skipped: 5    Failed: 1                                          |
+------------------------------------------------------------------+
|  COMPLETED (browsable)                                            |
|  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐             |
|  │ bg │ │back│ │back│ │char│ │arm │ │swrd│ │pot │             |
|  │ ✅ │ │ ✅ │ │ ✅ │ │ ✅ │ │ ✅ │ │ ✅ │ │ ✅ │             |
|  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘             |
|  [Click any to preview full-size / ↻ to re-queue]               |
+------------------------------------------------------------------+
|  ERRORS (1)                                                       |
|  ❌ Wolf Pack (animal) — "SD model timeout"  [Retry] [Skip]      |
+------------------------------------------------------------------+
|  [↻ Retry All Failed]  [Skip Remaining]                          |
+------------------------------------------------------------------+
```

**Controls:**

| Control | Action |
|---------|--------|
| **Pause** | Stop processing after current item finishes. Queue position preserved. |
| **Stop** | Abort queue. All pending items remain in the array for later restart. |
| **Resume** | Continue from current queue position. Re-checks image path for each remaining item. |
| **Retry** (per error) | Move item from `errorQueue` back to end of `imageQueue` with same params. |
| **Retry All Failed** | Move all `errorQueue` items back to `imageQueue`. |
| **Skip Remaining** | Clear remaining `imageQueue`. Cards use placeholders. Game can start immediately. |
| **↻ Redo** (per completed) | Re-add to end of `imageQueue` with `regen: true`. Old image stays until replaced. |

**Image check:** Uses standard AM7 path lookup — no dedicated gallery endpoint needed:
```
GET /rest/resource/data/path?path=~/CardGame/{deckName}/images/{subfolder}/{entityName}
→ If object exists at that path, image is already generated → skip
→ If 404, image needs generating
```

**Reimage endpoints (called one at a time by the queue):**

| Entity Type | Endpoint |
|-------------|----------|
| charPerson | `GET /rest/olio/charPerson/{id}/reimage/false` |
| animal | `GET /rest/olio/animal/{id}/reimage` |
| apparel (mannequin) | `GET /rest/olio/apparel/{id}/reimage` |
| wearable (icon) | `POST /rest/olio/wearable/{id}/reimage` |
| item | `GET /rest/olio/item/{id}/reimage` |
| deckAssets | `POST /rest/game/v2/deckAssets/generate` |
| cardBack | `POST /rest/game/v2/cardBack/generate/{cardType}` |
| action/skill/magic | `POST /rest/game/v2/cardImage/generate` |

### Review & Re-Queue Workflow

Since every deck build generates 60–90+ images, the queue is designed as a **generate → review → re-queue** loop. Players don't need to approve every image upfront — images generate in the background and the completed results are browsable as they arrive.

**Per-image reimage button:** Every completed image shows a **reimage button** (↻) inline — on the card itself wherever it appears (hand, character stack, detail view, deck browser) and in the completed results strip of the queue manager. Clicking it adds a `regen: true` entry to the end of the queue. The old image stays visible until the new one replaces it.

**Review workflow:**

```
DECK BUILD
    │
    ├─ 1. BUILD QUEUE
    │     Client builds imageQueue[] from all cards in deck
    │     Ordered by priority (background → card backs → characters → etc.)
    │
    ├─ 2. PROCESS (one at a time)
    │     For each item: check image path → skip if exists, else generate
    │     Progress bar + label updates per item
    │     Completed items appear in browsable results strip
    │     Player can browse/play while generation continues
    │
    ├─ 3. SPOT REVIEW (during and after)
    │     Browse completed results strip, click any for full preview
    │     Unsatisfactory: click ↻ → new entry appended to queue (regen: true)
    │     Satisfied: no action needed
    │
    ├─ 4. ERRORS
    │     Failed items moved to errorQueue, shown separately
    │     [Retry] moves back to end of imageQueue
    │     [Retry All Failed] moves all at once
    │
    └─ 5. REPEAT until satisfied
          Re-queued items generate with same prompt + new random seed
          New result replaces old in deck images
          Queue auto-completes when imageQueue is empty
```

**Image history per card:** Every generation attempt is cached (up to 5 per card in the deck images subfolder). The player can browse previous versions via the card detail view and select any previous version as the active image.

**Inline reimage button placement:**

| Location | Button Style | Behavior |
|----------|-------------|----------|
| Queue completed strip | Click thumbnail | Opens preview with [↻ Redo] option |
| Card face (hand view, hover) | Small ↻ icon overlay, top-right corner | Adds `regen: true` entry to queue |
| Card detail dialog | `[↻ Reimage]` button below the image | Adds to queue, image history accessible |
| Character stack sidebar (equipment slots) | Small ↻ icon on hover over wearable icon | Re-queues the wearable icon |
| Deck browser (all cards grid) | ↻ icon overlay on hover per card | Bulk review: scan grid, click ↻ on bad ones |

**Bulk review mode:** The deck browser includes a **"Review Images"** mode — all cards in a grid (128×192), sorted by generation time. Click ↻ on any bad ones, then "Re-Queue All Flagged" adds them all to `imageQueue` with `regen: true`.

**Queue during gameplay:** If the player starts a game before the queue is empty, processing continues in the background. The ↻ reimage button remains available on every card during gameplay. New images swap in with a fade transition when ready.

**Crash / refresh resilience:** The queue is just a client-side array, and all generated images are stored under `~/CardGame/{deckName}/images/` on the server. If the browser closes, crashes, or is refreshed mid-queue:
1. On restart, the client rebuilds `imageQueue[]` from the full deck card list (same as initial build)
2. The processing loop runs the **image path skip-check** on every item
3. Items whose images already exist at the expected path are skipped instantly (status: `skipped`)
4. Items not yet generated proceed normally
5. Net effect: the queue picks up where it left off without re-generating anything

No server-side queue state is needed. The deck image folder IS the persistence layer.

### Config Startup Check

After loading a deck's config (theme, SD, LLM, chat, and voice configs), the client runs a quick **connectivity and asset sanity check** before proceeding to gameplay or full deck build:

1. **Background image exists?** — `GET /rest/resource/data/path?path=~/CardGame/{deckName}/images/deckAssets/gameBackground` → if 404, the image gen server may be unreachable or the deck was never built.
2. **At least one character image exists?** — Check the first character's portrait path. If missing, image gen may have failed during build.
3. **LLM reachable?** — Send a minimal ping prompt to the configured LLM endpoint (e.g., "respond with OK"). If it fails, narration and AI opponent won't work.

**On failure:** Show a page toast with the specific failure (e.g., "Image generation server unreachable — check SD config" or "LLM endpoint not responding"). Log to `console.error`. The player can still proceed (the check is non-blocking), but they're warned about degraded functionality.

**On success:** No visible feedback — proceed silently.

### Setup Test & Preview

On **initial configuration** — before the full deck build generates 60–90+ images — the system runs an automated **test pass** that generates **one sample of every image type** and tests every unique LLM call, voice synthesis, and playback path. This catches config errors, missing API keys, SD model problems, and connectivity issues before committing to a large batch.

**Test pass items:**

| Test # | Category | What It Tests | Expected Output |
|--------|----------|--------------|-----------------|
| T1 | SD: deckAssets | Generate 1 gameBackground sample | 1920×1080 background image |
| T2 | SD: cardBack | Generate 1 card back (Character type) | 744×1039 card back image |
| T3 | SD: cardStyle | LLM compose style → generate frame overlay | JSON style def + frame image |
| T4 | SD: charPerson | Generate 1 character portrait | 1024×1024 + hires portrait image |
| T5 | SD: apparel (mannequin) | Generate 1 mannequin apparel image | 1024×1024 + hires mannequin image |
| T5b | SD: wearable (icon) | Generate 1 wearable icon | 512×512 item icon |
| T6 | SD: animal | Generate 1 creature image | 512×512 creature image |
| T7 | SD: item (weapon) | Generate 1 weapon image | 512×512 item image |
| T8 | SD: item (consumable) | Generate 1 consumable image | 512×512 item image |
| T9 | SD: item (action) | Generate 1 action card illustration | 1024×1024 + hires action image |
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

**All cards are the same physical size** — standard poker card: **2.5" × 3.5"** (63.5mm × 88.9mm). No variation by card type. Character cards, action cards, item cards, encounter cards — all identical dimensions.

At 300 DPI: **750 × 1050 pixels** (with 744×1039 safe area inside bleed)

### Print Layout

**All cards organized on 8.5" × 11" (US Letter) sheets** at high DPI, suitable for home printing or sending directly to a print shop.

**PDF generation** via server endpoint:

```
GET /rest/game/cards/print/{deckId}?format=pdf
→ Returns PDF with all cards laid out 3×3 per page on 8.5" × 11" sheets
   at 300 DPI, with crop marks and bleed area
   Ready to send to a print shop as-is
```

**Per page layout (US Letter 8.5" × 11"):**
```
+------+------+------+
|      |      |      |
| 2.5" | 2.5" | 2.5" |
| ×3.5"| ×3.5"| ×3.5"|
|      |      |      |
+------+------+------+
|      |      |      |
| 2.5" | 2.5" | 2.5" |
| ×3.5"| ×3.5"| ×3.5"|
|      |      |      |
+------+------+------+
|      |      |      |
| 2.5" | 2.5" | 2.5" |
| ×3.5"| ×3.5"| ×3.5"|
|      |      |      |
+------+------+------+
= 9 cards per page
  0.25" margins between cards and page edges
  Crop marks at card corners for precision cutting
  Total printable area: 7.5" × 10.5" (fits 3×3 with margins)
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
    "threats": { "count": 12, "difficultyRange": [4, 10], "maxRarity": "RARE", "note": "Regular threats only — bosses are in the vault" },
    "events": { "count": 10, "maxRarity": "UNCOMMON" },
    "discoveries": { "count": 10, "maxRarity": "RARE" },
    "npcs": { "count": 8, "maxRarity": "RARE", "source": "olio.charPerson.population", "roleAssignment": "random" },
    "lootItems": { "count": 10, "rarityWeights": { "COMMON": 50, "UNCOMMON": 35, "RARE": 15 } },
    "skillCards": { "count": 5, "minRarity": "UNCOMMON", "maxRarity": "RARE" },
    "magicEffects": { "count": 2, "rarity": "RARE" }
  },

  "treasureVault": {
    "bossThreats": { "count": 3, "difficultyRange": [12, 16], "minRarity": "EPIC", "note": "Unique/hard creatures shuffled in with items" },
    "legendaryWeapons": { "count": 2, "rarity": "LEGENDARY" },
    "epicWeapons": { "count": 3, "rarity": "EPIC" },
    "epicApparel": { "count": 3, "rarity": "EPIC" },
    "epicConsumables": { "count": 2, "rarity": "EPIC" },
    "epicSkills": { "count": 3, "minRarity": "RARE", "maxRarity": "EPIC" },
    "legendaryMagic": { "count": 2, "rarity": "LEGENDARY" },
    "drawTriggers": ["discovery_rare", "investigate_crit", "npc_quest_reward", "pot_jackpot_5plus"],
    "bossDefeatGrantsExtraDraw": true,
    "refillsOnCampaignSession": true
  },

  "balance": {
    "hpMax": 20,
    "moraleMax": 20,
    "energyFormula": "MAG or INT (by skill type)",
    "apFormula": "floor(END / 5) + 1",
    "apRange": [1, 5],
    "initiativeFormula": "1d20 + AGI",
    "potAnteRequired": true,
    "roundTimerSeconds": 60,
    "positionTimerSeconds": 15,
    "lazyBonesEnabled": true,
    "lethargyEnabled": true,
    "exhaustedEnabled": true,
    "hoardingReturnTarget": "encounterDeck"
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

Before a game session begins, the player goes through a **deck building process** that selects a theme, configures a character, draws starter cards, and assembles the encounter deck. Once a deck is "built," all card data is **snapshotted** — a detached copy of each card's game-relevant fields is extracted from the source AM7 object and stored as the authoritative game record. The game session operates entirely from snapshot data, not from live AM7 objects.

**Snapshots are stored as `data.data` objects** — the standard AM7 pattern for custom JSON. Each deck snapshot is a single `data.data` record with `contentType: "application/json"` and the full deck JSON serialized to `dataBytesStore`. This is the same storage pattern used by the existing v1 game save system (see `GameUtil.saveGame()` / `GameUtil.loadGame()`).

```java
// Server-side: creating a deck snapshot
BaseRecord snapshot = IOSystem.getActiveContext().getFactory().newInstance("data.data");
snapshot.set(FieldNames.FIELD_NAME, deckName);
snapshot.set(FieldNames.FIELD_GROUP_ID, deckDir.get(FieldNames.FIELD_ID));
snapshot.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
snapshot.set("dataBytesStore", deckJsonString.getBytes("UTF-8"));
IOSystem.getActiveContext().getRecordUtil().createRecord(snapshot);
```

**Storage path:** `~/CardGame/{deckName}/` — the deck's entire world lives under one AM7 group. All AM7 objects (characters, apparel, wearables, animals, items), all generated images, the deck snapshot, and game saves are stored here:

```
~/CardGame/{deckName}/
  ├── characters/       ← charPerson objects for this deck
  ├── apparel/          ← apparel objects
  ├── wearables/        ← wearable objects
  ├── animals/          ← animal objects
  ├── items/            ← item objects (weapons, consumables)
  ├── images/           ← ALL generated images (NOT in gallery)
  │   ├── characters/
  │   ├── apparel/
  │   ├── wearables/
  │   ├── animals/
  │   ├── items/
  │   ├── actions/
  │   ├── encounters/
  │   ├── skills/
  │   ├── magicEffects/
  │   ├── cardBacks/
  │   ├── cardStyle/
  │   └── deckAssets/
  ├── deck.json         ← deck snapshot (data.data)
  ├── save              ← auto-save state (data.data, written each round)
  └── {saveObjectId}/   ← mid-game generated assets (images, voice synth)
```

The deck snapshot (`deck.json`) is a single `data.data` record containing the full snapshot JSON. The auto-save (`save`) is also a `data.data` record storing session deltas. Mid-game generated assets (after-action images, LLM-suggested images, voice synthesis) are stored under a subfolder keyed to the save object ID — see [Mid-Game Asset Storage](#mid-game-asset-storage).

This decoupling means:
- **Self-contained:** Everything for a deck lives in one group — delete the group, delete the deck
- **Deletions** of source AM7 objects (characters, items, etc.) don't break in-progress or saved games
- **Edits** to source objects (stat rebalancing, description changes) don't retroactively alter ongoing games
- **Image regeneration** can update a card's visual without affecting stats (and vice versa)
- **Export/print** uses the snapshot, producing consistent output regardless of backend state
- **No gallery involvement** — images are stored directly under the deck group, not in ~/Gallery/

**What IS snapshotted (detached, reduced to game-relevant fields only):**
- Character stats, needs, equipment slots
- Card stats (ATK, DEF, durability, effects, requirements)
- Card names, descriptions, art prompts
- Image references (URL/ID at time of snapshot)

**Derived stat normalization at snapshot time:** Some stats on AM7 `charPerson` and `animal` records are derived and may not be initially accurate (e.g., health may be partially depleted from prior activity). When snapshotting characters and animals for the card game, the snapshot process **normalizes derived stats to their maximum values:**
- `hp` → set to 20 (flat max for all characters)
- `energy` → set to MAG stat value (Imperial) or INT (Undead/Psionic)
- `morale` → set to 20 (flat max for all characters)
- `durability` on equipment → set to `maxDurability`

This ensures every game starts from a clean, predictable state regardless of what the source character was doing before being snapshotted.

**What is NOT snapshotted (referenced by name, loaded fresh):**
- SD configs (`olio.sd.config`) — loaded from `Ux7/media/cardGame/sd/` by name
- Prompt configs (`olio.llm.promptConfig`) — loaded from `Ux7/media/cardGame/prompts/` by name
- Chat configs (`olio.llm.chatConfig`) — loaded from `Ux7/media/cardGame/chat/` by name
- Theme config itself — the snapshot stores `themeId` as a reference

**No version tracking on snapshots.** A snapshot is just the detached game assets for that deck — reduced to only the fields the game needs. There is no `snapshotVersion` counter. Re-snapshotting means replacing or adding an entry because the underlying AM7 object was updated (e.g., a card was rebalanced, a character leveled up). The snapshot is overwritten in place.

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
│  ○ Pick from existing deck characters (charPerson       │
│    picker — browse characters, see portrait, stats,     │
│    current apparel at a glance)                         │
│  ○ Generate new random character (uses existing         │
│    CharacterUtil.randomPerson() — gender, race, name,   │
│    stats, alignment all randomized. Stored in           │
│    stored in ~/CardGame/{deckName}/Characters)          │
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
│  current apparel deactivated (inuse=false) and a        │
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
│  • 7 Action cards + 1 Talk card (always included)       │
│  [Re-draw] button to randomize again (before snapshot)  │
│                                                         │
│  Step 4b: ADD CUSTOM CONTENT (optional)                 │
│  [Add Item] → create weapons, consumables, skills      │
│  [Add Custom Action] → define new action types beyond  │
│    the 7 standard actions (e.g., Disarm, Taunt, Sneak) │
│  [Browse Apparel] → pick existing apparel from ~/Apparel│
│    to override theme auto-generation                    │
│  [Create Apparel] → build a new outfit layer by layer  │
│  All custom content stored as real AM7 objects and      │
│  persists for future decks.                             │
│                                                         │
│  Step 5: BUILD ENCOUNTER DECK + TREASURE VAULT          │
│  Auto-assembled from theme encounterDeck config:        │
│  • Encounter Deck: 12 Threats, 10 Events, 10 Discoveries│
│    8 NPCs, 10 loot items, 5 skills, 2 magic (★–★★★)    │
│  • Treasure Vault: ~18 boss + Epic/Legendary (★★★★+)    │
│  • NPCs selected from deck characters and                │
│    assigned roles (trader, ally, quest-giver, etc.)      │
│  • Both decks shuffled independently                    │
│                                                         │
│  Step 6: REVIEW & BUILD                                 │
│  Preview all cards — each card shows:                   │
│  • [🔗 Open in AM7] link to edit source object          │
│  • [✏ Edit] for inline quick-edit of stats/name         │
│  • [↻ Reimage] to re-queue the card's image             │
│  Adjust if desired, then:                               │
│  [BUILD DECK] → snapshots all cards → game ready        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Character Picker (Step 2)

The character picker provides a visual browser for selecting or generating player characters. Characters are stored in the deck's `~/CardGame/{deckName}/Characters/` folder.

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
- **Existing deck characters** — queried from the deck's Characters folder (`~/CardGame/{deckName}/Characters/`)
- **Generate from templates** — calls server's `/olio/roll` endpoint to get base character with random name, gender, apparel, then applies template stats/alignment/personality. Stored in deck's Characters folder.
- **Import from saved deck** — loads a character snapshot from a previous game save

**Endpoint:**
```
GET /rest/olio/roll
GET /rest/olio/roll/{gender}
→ Returns: rolled charPerson with random name, gender, stats, apparel
```

### Theme Apparel Swap (Step 3b)

When characters are added to a deck, their **existing apparel is deactivated** and a new **theme-appropriate apparel set** is created from `~/Apparel` and `~/Wearables`. This is critical because:
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

3. REGISTER items in apparel/wearables paths
   └─ New apparel/wearable records created as real AM7 objects
      under ~/Apparel and ~/Wearables paths
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
→ Character reverts to their pre-game outfit
```

### Custom Content Creation (Step 4b)

During deck configuration, players can create custom items, actions, and apparel — or pick existing apparel from `~/Apparel`. All custom objects are stored as **real AM7 objects**, not snapshot-only cards. They persist across games and can be reused in future decks.

#### Custom Items

```
[Add Item] button
  ├── Select type: Weapon / Consumable / Skill / Magic Effect
  ├── Fill in card fields (name, stats, effects, requirements)
  │   └── Uses existing formDef.js definitions for olio.item, olio.wearable, etc.
  ├── Item created as AM7 object under deck's item/store paths
  ├── Card snapshot generated from the new item
  └── Added to current deck's card pool
```

**Storage:**
- Weapons/consumables: `{world}/Items/{itemName}`
- Skills: linked to existing `olio.skill` records or new custom skills

#### Custom Actions

Players can define new action types beyond the 7 standard actions (Attack, Flee, Rest, Use Item, Investigate, Craft, Watch) and Talk.

```
[Add Custom Action] button
  ├── Name: [__________]  (e.g., "Disarm", "Taunt", "Sneak")
  ├── Type: Offensive / Defensive / Movement / Social / Discovery / Special
  ├── Energy Cost: [##]
  ├── Roll Formula: [Primary stat ▼] + [Secondary stat ▼] (optional)
  │   e.g., AGI + INT for "Sneak"
  ├── Opposing Roll: [Opposing stat ▼] or [Fixed DC: ##]
  ├── Success Effect: [text description or structured effect]
  │   e.g., "Target drops equipped weapon into pot"
  ├── Failure Effect: [text description]
  ├── Requires: [Skill card ▼] (optional — must have matching skill to use)
  ├── Card Art Prompt: [auto-generated or custom text for SD image gen]
  └── [Create] → stored as a custom action card in the deck
```

Custom actions are stored as `data.data` objects (custom JSON) under `{user}/Game/v2/customActions/{deckName}/`. They appear alongside the standard action cards in the hand tray. Each custom action gets an image generated via the `action` SD config subtype.

**Example custom actions:**
- **Disarm** (Offensive, 10E) — Roll AGI + STR vs target AGI + DEF. Success: target drops weapon into pot.
- **Taunt** (Social, 5E) — Roll CHA vs target CHA. Success: target must Attack you next position (overrides planned action).
- **Sneak** (Movement, 8E) — Roll AGI + INT vs encounter difficulty. Success: bypass encounter without fighting.
- **Heal** (Special, 12E) — No roll. Restore 15 HP. Requires: Medicine skill card.

#### Custom Apparel (Pick or Create)

Players can either **pick existing apparel** from `~/Apparel` or **create new themed apparel**.

**Pick existing apparel:**
```
[Browse Apparel] button
  ├── Opens apparel browser — shows all apparel sets in ~/Apparel
  ├── Each set shows: name, mannequin preview, wearable layers, stats (DEF, MDEF)
  ├── [Select] picks the apparel set for the active character
  ├── Selected apparel replaces the auto-generated theme apparel (Step 3b)
  └── Character portrait re-queued with the selected outfit
```

This lets players hand-pick outfits for their character instead of relying on the theme auto-generation. Useful when a character already has a distinctive look that should carry into the card game.

**Create new apparel:**
```
[Create Apparel] button
  ├── Select gender template: Male / Female / Unisex
  ├── Layer editor — fill each wear level:
  │   ├── BASE (4): [undergarment name] [fabric ▼] [color ▼]
  │   ├── SUIT (6): [garment name] [fabric ▼] [color ▼]
  │   ├── OVER (9): [garment name] [fabric ▼] [color ▼]
  │   ├── OUTER (10): [garment name] [fabric ▼] [color ▼]
  │   └── (any layer can be left empty)
  ├── Stat overrides: DEF [##] MDEF [##] Durability [##]
  ├── [Create] → AM7 apparel + wearable objects created
  ├── Mannequin image queued for each wearable layer
  └── Character portrait re-queued with new outfit
```

**Endpoints:**
```
POST /rest/game/v2/item/create
Body: { "worldPath": "...", "type": "weapon", "name": "Flame Tongue", "stats": {...} }
→ Returns: created AM7 item record

POST /rest/game/v2/apparel/create
Body: { "worldPath": "...", "gender": "male", "wearables": [...], "name": "Dragon Scale Armor" }
→ Returns: created AM7 apparel record with full wearable sublayers

POST /rest/game/v2/action/create
Body: { "deckName": "high-fantasy", "name": "Disarm", "type": "offensive", "energyCost": 10,
        "rollFormula": "AGI + STR", "opposingRoll": "AGI + DEF", "successEffect": "...", ... }
→ Returns: created custom action card (data.data object)

GET /rest/game/v2/apparel/browse
Query: { "worldPath": "..." }
→ Returns: list of all apparel sets in the world with preview data
```

### Object Links in Config (All Steps)

Every card displayed during deck configuration — in the character picker, starter deck draw, encounter deck preview, treasure vault preview, and the Step 6 review grid — includes a clickable **[Open in AM7]** link that opens the underlying AM7 object in the AM7 object editor. This lets the player inspect and edit source objects directly during configuration without leaving the deck builder.

**Link behavior:**

| Card Type | AM7 Object Type | Link Target |
|-----------|----------------|-------------|
| Character | `olio.charPerson` | `/view/charPerson/{objectId}` — full character editor (stats, profile, gallery) |
| Apparel | `olio.apparel` | `/view/apparel/{objectId}` — apparel editor with wearable sublayers |
| Wearable | `olio.wearable` | `/view/wearable/{objectId}` — individual wearable item editor |
| Weapon / Consumable | `olio.item` | `/view/item/{objectId}` — item property editor |
| Animal / Creature | `olio.animal` | `/view/animal/{objectId}` — animal editor (stats, traits) |
| Encounter | `olio.encounter` | `/view/encounter/{objectId}` — encounter editor (difficulty, behavior, loot) |
| Skill | `olio.skill` | `/view/skill/{objectId}` — skill definition editor |
| Magic Effect | `olio.magicEffect` | `/view/magicEffect/{objectId}` — magic effect editor |
| NPC | `olio.charPerson` | `/view/charPerson/{objectId}` — same as character |
| Action / Talk | *(no AM7 source)* | No link — these are template cards defined by the theme config |

**Link placement:**

```
┌──────────────────────────────────────────┐
│  [Card Preview]                           │
│  ┌────────────┐                           │
│  │  Card Image │   Elven Silk Robes       │
│  │             │   Apparel (Body)         │
│  │             │   DEF: 2  MDEF: 4        │
│  └────────────┘   Rarity: ★ Common        │
│                                           │
│  [↻ Reimage] [🔗 Open in AM7] [✏ Edit]   │
└──────────────────────────────────────────┘
```

- **[Open in AM7]** opens the object in a new browser tab/panel using the AM7 router (`page.nav.go(path)`)
- **[Edit]** opens an inline edit dialog within the deck builder for quick stat/name changes (writes back to the AM7 object, then the card preview updates in-place)
- Links use `sourceObjectId` from the card data — action/talk cards (no source object) show no link
- If the source object has been deleted, the link shows as disabled with a tooltip: "Source object no longer exists"

**Post-edit behavior:** After editing an AM7 object (either via [Open in AM7] or [Edit]), the deck builder detects the change and offers to re-snapshot that card: "Source object was modified. [Re-snapshot] to update card data."

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
      "name": "Aelindra",
      "race": "ELF",
      "alignment": "NEUTRAL_GOOD",
      "level": 1,
      "stats": { "STR": 10, "AGI": 14, "END": 12, "INT": 16, "MAG": 13, "CHA": 7 },
      "needs": { "hp": 20, "maxHp": 20, "energy": 13, "maxEnergy": 13, "morale": 20, "maxMorale": 20 },
      "_note": "hp normalized to 20 (flat), energy normalized to MAG stat, morale normalized to 20 (flat)"
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
      "cardType": "encounter",
      "subtype": "threat",
      "name": "Dire Wolf Pack",
      "difficulty": 8,
      "rarity": "UNCOMMON",
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

  "treasureVault": [
    {
      "cardSnapshotId": "csnap-vault-uuid",
      "sourceObjectId": "am7-item-uuid",
      "cardType": "item",
      "subtype": "weapon",
      "name": "Sunforged Greatsword",
      "rarity": "LEGENDARY",
      "stats": { "atk": 12, "durability": 20, "damageType": "Radiant" },
      "requires": { "STR": 16 },
      "artPrompt": "legendary golden greatsword, radiant glow, ornate hilt",
      "image": {
        "imageId": null,
        "imageUrl": null,
        "imageStatus": "pending",
        "generatedAt": null
      }
    }
  ],

  "actionCards": [
    { "cardType": "action", "actionType": "ATTACK", "name": "Attack" },
    { "cardType": "action", "actionType": "FLEE", "name": "Flee" },
    { "cardType": "action", "actionType": "REST", "name": "Rest" },
    { "cardType": "action", "actionType": "USE_ITEM", "name": "Use Item" },
    { "cardType": "action", "actionType": "INVESTIGATE", "name": "Investigate" },
    { "cardType": "action", "actionType": "CRAFT", "name": "Craft" },
    { "cardType": "action", "actionType": "WATCH", "name": "Watch" },
    { "cardType": "talk", "name": "Talk" }
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

Each card snapshot is a reduced/detached copy of the source AM7 object, containing only the fields the game needs:

| Field | Purpose |
|-------|---------|
| `cardSnapshotId` | Unique ID for this card entry within the deck snapshot |
| `sourceObjectId` | Reference to the original AM7 object (nullable — action/talk cards have no AM7 source) |
| `cardType` | Card type identifier (character, apparel, item, action, etc.) |
| `stats` | Detached stat block (only game-relevant fields from the source object) |
| `image` | Image state: `imageId`, `imageUrl`, `imageStatus` (pending/placeholder/generated), `generatedAt` |
| `artPrompt` | The SD prompt used for image generation (preserved for re-generation) |

### Snapshot Lifecycle

```
SOURCE OBJECTS (AM7 backend)          SNAPSHOT (game session)
┌─────────────────────┐              ┌─────────────────────┐
│ olio.charPerson     │──snapshot──▶│ characterSnapshot   │
│ olio.item           │──snapshot──▶│ starterCards[]      │
│ olio.encounter      │──snapshot──▶│ encounterDeck[]     │ (★–★★★)
│ olio.item (rare)    │──snapshot──▶│ treasureVault[]     │ (★★★★–★★★★★)
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
→ Updates image fields in snapshot (overwritten in place)
→ Returns updated card snapshot
```

#### Stat Re-Snapshot
Pulls fresh stats from the source AM7 object into the snapshot. Triggered manually when a player wants to incorporate upstream changes (e.g., a card was rebalanced between sessions).

```
POST /rest/game/v2/deck/{snapshotId}/resnap/stats
Body: { "cardSnapshotId": "csnap-uuid" }

→ Reads current state of sourceObjectId from AM7
→ Updates stat fields in snapshot (overwritten in place)
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

5. AUTO-SAVE (every round, automatic)
   └─ After each round's cleanup phase, the client writes session state
      to a `data.data` object via standard AM7 CRUD:
        PUT /rest/resource/data.data
        Path: ~/CardGame/{deckName}/save
        Body: {
          contentType: "application/json",
          dataBytesStore: JSON.stringify({
            snapshotId,
            sessionState (deltas),
            eventLog,
            round,
            phase: "cleanup",
            timestamp: ISO-8601
          })
        }
   └─ Snapshot is NOT modified — session deltas saved separately
   └─ No manual save button — saving is invisible and automatic
   └─ Uses existing AM7 data.data endpoints, no new REST routes needed
   └─ Mid-game generated assets (images, voice) stored under
      ~/CardGame/{deckName}/{saveObjectId}/ (see below)

6. GAME START SCREEN (per deck)
   └─ On selecting a built deck, the client checks for an existing save:
        GET /rest/resource/data.data/path?path=~/CardGame/{deckName}/save
   └─ If save exists:
        [Resume Game]  — loads snapshot + merges deltas, resumes at saved round
        [New Game]     — deletes save data.data object, starts fresh session
   └─ If no save:
        [New Game]     — only option
   └─ No manual load/save UI — it's resume or new, that's it

7. END GAME / CAMPAIGN CONTINUE
   └─ Campaign mode: only the character card persists — stat gains
      written back to the charPerson record. Save data.data deleted.
      Items, apparel, weapons, skills do NOT carry over.
      Next session rebuilds a fresh deck from the theme pool.
   └─ One-off mode: save data.data deleted, snapshot remains for replay
   └─ Mid-game artifacts (after-action images, voice audio) under
      ~/CardGame/{deckName}/{saveObjectId}/ are LEFT in place.
      Not cleaned up — they serve as a history/gallery of past sessions.
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
      "needs": { "hp": -7, "energy": -4, "morale": -2 },
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
    "treasureVaultPosition": 3,
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

### Mid-Game Asset Storage

During gameplay, the system generates assets that didn't exist at deck-build time — after-action images, LLM-suggested scene images, voice synthesis audio. These assets are stored under the save's object ID to keep them associated with the specific game session:

```
~/CardGame/{deckName}/{saveObjectId}/
  ├── images/           ← After-action images, LLM-suggested scene images
  │   ├── round-3-resolution.png
  │   ├── round-5-encounter.png
  │   └── ...
  └── voice/            ← Voice synthesis audio files
      ├── round-3-opening.wav
      ├── round-3-resolution.wav
      └── ...
```

**Why keyed to save object ID:**
- Starting a new game creates a new save → new asset folder → clean slate
- Resuming a game reuses the same save → same asset folder → existing audio/images still cached
- Deleting a save (on game end or new game start) can clean up the subfolder too
- Multiple deck games don't collide — each save has its own ID

**What goes here (not in the main deck images folder):**
- After-action images (unique per round, per game session)
- Voice synthesis audio (narration audio for each trigger point)
- Any LLM-suggested images generated mid-game (e.g., "generate an image of this scene")
- Character re-narration audio (when apparel changes trigger narrative updates)

**What stays in the main deck images folder** (`~/CardGame/{deckName}/images/`):
- Card art (characters, apparel, items, etc.) — these are deck-level, not session-level
- Card backs, card style, deck assets — shared across all games with this deck

---

## Online Implementation

### Architecture (REST-First, Same as cardGame.js)

The v2 client communicates exclusively through the AM7 REST API and WebSocket endpoints. It extends the existing `cardGame.js` infrastructure.

**Streaming chat via WebSocket is already implemented** — the existing `chat.js` / `cardGame.js` WebSocket chat system (`page.wss.send("chat", ...)` with `onchatstart`/`onchatupdate`/`onchatcomplete` handlers) is reused as-is for Talk card conversations. No new WebSocket chat implementation needed. New chat configs are created from the "Open Chat" template (see [Chat System](#chat-system)).

### New / Extended Endpoints

**Game Session (17 new):**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v2/newGame` | POST | Initialize a new card game session (generate starter decks, encounter deck) |
| `POST /rest/game/v2/draw` | POST | Draw card(s) from encounter deck or treasure vault |
| `POST /rest/game/v2/initiative` | POST | Roll initiative for all participants, returns position map |
| `POST /rest/game/v2/ante` | POST | Submit pot ante card(s) for the current round |
| `POST /rest/game/v2/placeStacks` | POST | Submit all action stacks for placement on the action bar |
| `POST /rest/game/v2/resolvePosition` | POST | Resolve a single action bar position (server-side dice + narration + after-action image) |
| `POST /rest/game/v2/disrupt` | POST | Execute a mid-round disruption (insert, remove, modify) |
| `GET /rest/game/v2/state` | GET | Get full game state (hand, bar, pot, encounter, needs) |
| `POST /rest/game/v2/equip` | POST | Equip/unequip apparel or weapon (costs 1 AP as action stack) |
| `POST /rest/game/v2/swapSkill` | POST | Swap skill card into active slot |
| `POST /rest/game/v2/ai/placement` | POST | Get AI opponent's stack placement for the action bar (Mode 1) |
| `POST /rest/game/v2/ai/disruptResponse` | POST | Get AI response to a mid-turn disruption |
| `POST /rest/game/v2/gm/encounter` | POST | Get GM's encounter selection + stack placement (Mode 2) |
| `POST /rest/game/v2/gm/objective` | POST | Generate or evaluate scenario objective (Mode 2 story mode) |
| `POST /rest/game/v2/pot/claim` | POST | Round winner claims all pot cards |
| `POST /rest/game/v2/trade` | POST | Execute trade between players |
| `POST /rest/game/v2/talk` | POST | Initiate Talk action (opens chat or resolves outcome) |

**Deck Building (8 new):**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v2/deck/build` | POST | Build deck from theme + character, creates snapshot |
| `GET /rest/game/v2/deck/{snapshotId}` | GET | Load a built deck snapshot |
| `GET /rest/game/v2/decks` | GET | List all user's built decks |
| `DELETE /rest/game/v2/deck/{snapshotId}` | DELETE | Delete a built deck |
| `POST /rest/game/v2/deck/{snapshotId}/resnap/image` | POST | Re-snapshot single card image (overwritten in place) |
| `POST /rest/game/v2/deck/{snapshotId}/resnap/stats` | POST | Re-snapshot single card stats (overwritten in place) |
| `POST /rest/game/v2/deck/{snapshotId}/resnap/all` | POST | Re-snapshot entire deck |
| `POST /rest/game/v2/deck/{snapshotId}/clone` | POST | Clone a deck (for variant builds) |

**Narration & Content Generation (4 new):**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v2/narrate` | POST | Generate LLM narration for a game event (returns text + image prompt) |
| `POST /rest/game/v2/generateCardStyle` | POST | LLM-compose card style definition from theme |
| `POST /rest/game/asset/generate` | POST | Generate SD image for non-entity cards (actions, skills, magic, encounters, deck assets) |
| `GET /rest/game/cards/print/{deckId}` | GET | Generate printable PDF of deck |

**Custom Content Creation (4 new):**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rest/game/v2/item/create` | POST | Create a custom item (weapon, consumable) for the deck |
| `POST /rest/game/v2/action/create` | POST | Create a custom action type for the deck |
| `POST /rest/game/v2/apparel/create` | POST | Create custom apparel for the deck |
| `GET /rest/game/v2/apparel/browse` | GET | Browse existing apparel from ~/Apparel for deck inclusion |

**Existing AM7 Endpoints (reused, no new implementation):**

| Endpoint | Method | Reused For |
|----------|--------|------------|
| `PUT /rest/resource/data.data` | PUT | Auto-save game state (writes session deltas as data.data JSON) |
| `GET /rest/resource/data.data/path` | GET | Load saved game / check for existing save |
| `DELETE /rest/resource/data.data/{id}` | DELETE | Delete save on game end or new game start |
| `GET /rest/resource/data/path` | GET | Image skip-check (does image exist at deck path?) |
| `GET /rest/olio/charPerson/{id}/reimage/false` | GET | Character portrait regeneration |
| `GET /rest/olio/animal/{id}/reimage` | GET | Animal/creature image regeneration |
| `POST /rest/olio/apparel/{id}/reimage` | POST | Apparel mannequin image regeneration |
| `POST /rest/olio/wearable/{id}/reimage` | POST | Wearable icon regeneration |
| `GET /rest/olio/item/{id}/reimage` | GET | Item image regeneration |
| `POST /rest/face/analyze` | POST | Poker Face emotion capture (from MoodRing) |
| `POST /rest/voice/{hash}` | POST | Voice synthesis for narration (from Magic8) |
| `POST /rest/game/concludeChat` | POST | Conclude Talk card conversation, LLM evaluates outcome |
| **WebSocket chat** | WS | Streaming chat for Talk card — reuses `chat.js`/`cardGame.js` handlers |

### LLM Call Fallback Behavior

All LLM calls (narration, AI opponent decisions, GM encounter generation, scenario objectives, chat conclude, card style composition) follow the same retry/fallback pattern:

```
1. First attempt → call LLM endpoint
2. If failure (timeout, 5xx, network error) → RETRY once (same parameters)
3. If second failure → STOP. Show page toast + log to browser console.
```

**Page toast:** A non-blocking notification bar at the top of the game UI (auto-dismiss after 8 seconds, or click to dismiss):
```
⚠ LLM call failed: {endpoint} — {error message}. Game continues without AI response.
```

**Console log:** Full error details logged via `console.error()` for debugging:
```javascript
console.error('[CardGame] LLM call failed after retry', {
    endpoint: '/rest/game/v2/narrate',
    attempt: 2,
    error: err.message,
    context: { round, trigger, phase }
});
```

**Per-feature graceful degradation on LLM failure:**

| Feature | Behavior on LLM Failure |
|---------|------------------------|
| Narration | Skip narration for this trigger point. Subtitle shows "[narration unavailable]". Game continues. |
| AI Opponent | AI plays a default stack (highest ATK card, no modifiers). Toast warns "AI is playing blind." |
| GM Encounter | Draw top encounter card with no GM curation. Toast warns "GM unavailable, random encounter." |
| Scenario Objective | Objective evaluation skipped for this round. Progress unchanged. |
| Chat Conclude | Chat ends without interaction record. Toast warns "Conversation not evaluated." |
| Card Style Composition | Fall back to hardcoded default card style (clean white background, simple borders). |
| After-Action Image | Skip image generation for this round. Event log entry without image. |

No retries beyond the second attempt. No exponential backoff. No queuing for later. Fail fast, inform the player, keep the game moving.

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

The card game is implemented as a modular IIFE-based system using the `window.CardGame` namespace, following the same pattern as `magic8/`. Each module is a self-contained IIFE that exposes its public API on the shared namespace. Modules communicate through a shared context object (`CardGame.ctx`) — no direct cross-module imports.

```
client/view/cardGame/                      # 29 modules, ~12K lines total
├── index.js                               # Script loader manifest + version
├── CardGameApp.js                         # Main orchestrator — screen routing, shared ctx, page.views registration
├── constants/
│   └── gameConstants.js                   # CARD_TYPES, GAME_PHASES, STATUS_EFFECTS, COMBAT_OUTCOMES, etc.
├── state/
│   ├── storage.js                         # deckStorage, gameStorage, campaignStorage, encode/decode helpers
│   └── gameState.js                       # createGameState(), phase transitions, LLM init, narration
├── engine/
│   ├── effects.js                         # parseEffect(), applyParsedEffects(), status effect management
│   ├── combat.js                          # DiceUtils, rollAttack/Defense, resolveCombat, damage calc
│   ├── encounters.js                      # THREAT_CREATURES, SCENARIO_CARDS, threat generation
│   └── actions.js                         # advanceResolution(), pot, card placement, lethargy
├── ai/
│   ├── llmBase.js                         # CardGameLLM base class
│   ├── director.js                        # CardGameDirector (AI opponent) + aiPlaceCards
│   ├── narrator.js                        # CardGameNarrator + narrator profiles
│   ├── chatManager.js                     # CardGameChatManager (Talk card chat)
│   └── voice.js                           # CardGameVoice + emotion detection + TTS
├── rendering/
│   ├── cardComponents.js                  # NeedBar, StatBlock, D20Dice, cornerIcon, rarityStars
│   ├── cardFace.js                        # CardFace, CardBack, renderCardBody, renderCharacterBody
│   └── overlays.js                        # ImagePreviewOverlay, CardPreviewOverlay, GalleryPicker
├── ui/
│   ├── deckList.js                        # DeckList component + deck CRUD
│   ├── builder.js                         # BuilderThemeStep, BuilderCharacterStep, BuilderReviewStep
│   ├── deckView.js                        # DeckView + art generation queue + SD config panel
│   ├── gameView.js                        # GameView, CharacterSidebar, HandTray, ActionBar
│   ├── phaseUI.js                         # InitiativePhaseUI, EquipPhaseUI, ResolutionPhaseUI, CleanupPhaseUI
│   ├── threatUI.js                        # ThreatResponseUI
│   ├── chatUI.js                          # TalkChatUI + openTalkChat
│   └── gameOverUI.js                      # GameOverUI, LevelUpUI
├── services/
│   ├── artPipeline.js                     # Art queue, generateCardArt, buildSdEntity, template art
│   ├── characters.js                      # Character loading, generation, persistence, stat mapping
│   └── themes.js                          # Theme loading, outfit application, ThemeEditorUI
└── test/
    └── testMode.js                        # Test mode screen + test controls + automated test suite
```

**Namespace structure:**
```
window.CardGame
├── ctx                    # Shared mutable state (screen, viewingDeck, gameState, activeTheme, etc.)
├── Constants              # All enums, config objects, balance data
├── Storage                # deckStorage, gameStorage, campaignStorage
├── Rendering              # CardFace, CardBack, NeedBar, StatBlock, D20Dice, overlays
├── Characters             # Character loading, generation, stat mapping
├── Themes                 # Theme loading, outfit application, editor
├── ArtPipeline            # SD art generation queue
├── Engine
│   ├── Effects            # Status effects, parseEffect()
│   ├── Combat             # Dice, attack/defense, damage
│   ├── Encounters         # Threats, scenario cards
│   └── Actions            # Resolution, pot, placement
├── GameState              # Game state management, phase transitions, LLM init
├── AI
│   ├── CardGameLLM        # Base LLM class
│   ├── CardGameDirector   # AI opponent
│   ├── CardGameNarrator   # Game narration
│   ├── CardGameChatManager # Talk card chat
│   └── CardGameVoice      # TTS + emotion
├── UI                     # All Mithril UI components
├── TestMode               # Test suite
└── getScriptPaths()       # Script loading order
```

**External content (JSON under `media/cardGame/`):**
```
media/cardGame/
├── character-templates.json               # 12 balanced character templates
├── high-fantasy.json                      # Theme: High Fantasy card pool
├── dark-medieval.json                     # Theme: Dark Medieval card pool
├── sci-fi.json                            # Theme: Sci-Fi card pool
├── post-apocalypse.json                   # Theme: Post Apocalypse card pool
├── game-balance.json                      # DCs, recovery values, XP formula, combat multipliers
├── test-cards.json                        # Test deck card definitions
└── prompts/
    ├── art-prompts.json                   # ACTION_PROMPTS, TALK_PROMPTS for card art
    ├── director-system.json               # AI director system prompt template
    ├── narrator-system.json               # Narrator profiles + system prompt
    └── chat-system.json                   # Chat manager system prompt
```

### UI Layout (Online)

```
+------------------------------------------------------------------+
|  HEADER: Round # | Phase | Initiative | AP: 3/3 | Timer | [⏸] | ⚙ |
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
|  MRL [████]  |  ENCOUNTER CARD (if active)        |  MRL [████]   |
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
    phase: 'initiative' | 'draw' | 'placement' | 'resolution' | 'cleanup',
    paused: false,                       // Game is paused (timer stopped)

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
        needs: { hp: 20, energy: 14, morale: 20 },
    },

    opponent: {                          // AI character (Mode 1) or null (Mode 2)
        character: {},
        characterStack: { /* same structure */ },
        ap: { current: 4, max: 4 },
        needs: { hp: 20, energy: 14, morale: 20 },
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

    lazyBones: {                         // Timer expiry penalty tracking (per round)
        expiries: 0,
        apPenalty: 0,
        initiativeLost: false,
        defaulted: false,
    },

    encounter: null,                     // Current encounter card
    encounterDeck: [],                   // Remaining encounter deck (★–★★★)
    treasureVault: [],                   // Separate deck for Epic/Legendary (★★★★–★★★★★)
    discardPile: [],                     // Discarded cards

    eventLog: [],                        // Round-by-round log
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
║  1. Roll initiative (1d20 + AGI)             ║
║  2. Draw from encounter deck (winner first)  ║
║  3. Place action stacks on bar (open) + Ante ║
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
║  DEFENSE: Passive armor (always) + Parry     ║
║  (if Parry card at NEXT position on bar).    ║
║  Armor CAN fully block non-critical damage.  ║
║  Criticals always deal min 2 dmg.            ║
║                                              ║
║  ROLL: 1d20 + char base + action modifiers   ║
║    vs 1d20 + END + Armor DEF (+ Parry)       ║
║                                              ║
║  OUTCOMES (attacker - defender):              ║
║  +10 CRIT HIT: 2× dmg (min 2), drop → pot   ║
║   +5 Solid Hit: full damage (0 if absorbed)  ║
║   +1 Glancing: half damage (0 if absorbed)   ║
║    0 Stalemate: nothing                      ║
║   -1 Deflected: weapon -1 dur               ║
║   -5 Countered: half dmg to attacker         ║
║      (armor applies except on crit fail)     ║
║  -10 CRIT COUNTER: full dmg to attacker,     ║
║      attacker drops item + loses next action ║
║  Nat 20 = ALWAYS SUCCEEDS + upgrade 1 tier  ║
║  Nat 1  = ALWAYS FAILS + downgrade + drop   ║
║                                              ║
║  MID-ROUND DISRUPTION:                       ║
║  INSERT: add stacks after current position   ║
║  REMOVE: strip opponent's next stack         ║
║  MODIFY: alter bonuses on upcoming stack     ║
║                                              ║
║  PER-ROUND THREATS (0–3 per round):          ║
║  Nat 1 initiative → threat at BEGINNING      ║
║    → attacks the fumbler (who rolled Nat 1)  ║
║    → win = keep loot, lose = loot to pot     ║
║    → killed by threat = lose game instantly  ║
║  Scenario/card → threat at END               ║
║    → round winner faces it, 1 bonus stack    ║
║    → repel (deal dmg) = keep pot             ║
║    → fail/flee = LOSE POT to opponent        ║
║    → surviving threat → beginning next round ║
║                                              ║
║  POT: Ante 1 card/round. Drops/loot → pot.   ║
║  Round winner claims all pot cards.           ║
║  Pot jackpot (5+ cards) → vault draw bonus.  ║
║                                              ║
║  TREASURE VAULT (~18 cards, ★★★★–★★★★★):     ║
║  Boss encounters + epic/legendary items,     ║
║  shuffled together. Draw triggers:           ║
║  • ★★★ Discovery encounter                  ║
║  • Investigate critical success (Nat 20)     ║
║  • NPC quest reward                          ║
║  • Pot jackpot (5+ cards)                    ║
║  Draw a boss? It's an immediate threat!      ║
║  Beat a vault boss → 1 extra vault draw.     ║
║  Vault draws are ALSO random (top card).     ║
║                                              ║
║  ANTI-HOARDING (action/talk cards only):      ║
║  LETHARGY (cleanup): Hold 2+ of same action  ║
║    type, played 0 → keep 1, return extras    ║
║    to encounter deck.                        ║
║  EXHAUSTED (mid-resolution): Played same     ║
║    action 2+ times, last one FAILED, hold    ║
║    2+ extras → keep 1, return extras.        ║
║                                              ║
║  AP: Must use at least 1 AP per round.       ║
║  Unused AP is lost (no carry to next round). ║
║                                              ║
║  LAZY BONES (timer expiry):                  ║
║  1st: lose initiative + lose 1 AP, restart   ║
║  2nd: lose another AP, restart               ║
║  Never act: DEFAULT round — 0 actions,       ║
║    opponent resolves unopposed               ║
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
| Campaign | No single win — survive across sessions, level up character (only character card persists between games) |

**No simultaneous defeat:** Because resolution is strictly position-by-position (left to right) and damage applies immediately in real-time, it is impossible for both players to reach 0 HP simultaneously. One player will always hit 0 HP first at a specific bar position, ending the game on the spot. The surviving player wins regardless of what would have happened at later positions.

### Scenario Objectives

Scenario objectives for Story Mode (Mode 2) are deliberately simple. The LLM builds objectives from three inputs already in the game state — no special scenario card type or pre-built encounter composition needed:

**Inputs:**
1. **Theme context** — the theme's setting, art style, world description, and narrative tone
2. **Character narratives** — each character's backstory, personality traits, alignment, and current state. Narratives are **re-generated after apparel changes** (equipping new gear changes who the character appears to be, so the narrative updates to match)
3. **Action stack sequences and outcomes** — the history of what happened each round (which actions were played, in what order, and what the outcomes were)

**How objectives work:**
- At game start, the GM LLM generates a scenario objective from the theme + character narratives: "Defend the village from three waves of attacks" or "Retrieve the artifact from the goblin cave before nightfall"
- The objective is a simple text description — survive N rounds, defeat a specific encounter type, accumulate a resource threshold, reach a location, or protect an NPC
- Each round, the GM evaluates progress toward the objective based on action stack sequences and outcomes
- The GM narrator weaves objective progress into the round narration: "Two waves down, one to go — but the goblin chieftain approaches..."
- Objective completion (or failure) is determined by the GM LLM based on the accumulated game history

**Objective types (LLM-generated, not hardcoded):**

| Type | Example | Evaluation Basis |
|------|---------|-----------------|
| Survival | "Survive 15 rounds in the wasteland" | Round count reached |
| Elimination | "Defeat the dragon threatening the kingdom" | Specific encounter threat killed |
| Collection | "Gather 3 magical components" | Specific item cards gained |
| Protection | "Keep the merchant alive through the mountain pass" | NPC HP stays above 0 |
| Exploration | "Investigate 5 different locations" | Investigate actions completed |

**Character re-narration:** When a character equips or unequips apparel/weapons, their narrative is re-generated to reflect the new loadout. This keeps the story coherent — a character who started as a "ragged wanderer" and now wears plate armor becomes a "battle-hardened knight." The GM LLM uses the updated narrative for future objective evaluation and narration.

### Card Counts Summary

| Card Type | Starter Deck | Encounter Deck (★–★★★) | Treasure Vault (★★★★–★★★★★) | 2P Total | 3P Total | 4P Total |
|-----------|-------------|----------------------|---------------------------|----------|----------|----------|
| Character | 1 per player | 0 | 0 | 2 | 3 | 4 |
| Apparel | 2-3 per player | 2/3/4 in encounter | 3/4/5 epic in vault | 9-11 | 13-15 | 17-20 |
| Item (Weapon) | 1-2 per player | 4/6/8 in encounter | 5/6/8 in vault | 11-13 | 15-18 | 20-24 |
| Item (Consumable) | 3-5 per player | 16/24/32 in encounter | 2/3/4 epic in vault | 24-29 | 33-41 | 44-54 |
| Action | 8 per player (1 each type) | 0 | 0 | 16 | 24 | 32 |
| Talk | 1 per player | 0 | 0 | 2 | 3 | 4 |
| Skill | 1-2 per player | 5/7/9 in encounter | 3/4/4 rare/epic in vault | 10-12 | 14-17 | 18-21 |
| Magic Effect | 0-1 per player | 2/3/4 in encounter | 2/2/3 legendary in vault | 4-6 | 6-9 | 9-12 |
| Encounter (regular) | 0 | 30/42/54 | 0 | 30 | 42 | 54 |
| Discovery | 0 | 10/13/16 | 0 | 10 | 13 | 16 |
| **Boss Encounters** | **0** | **0** | **4/5/6 (difficulty 12+)** | **4** | **5** | **6** |
| **Total** | **~18 per player** | **57/72/87** | **18/23/28** | **~111** | **~149** | **~187** |

### Deck Balancing by Player Count

The game scales from 2 to 4 players. Card counts must increase to prevent deck exhaustion and maintain strategic depth. The following tables provide **optimal card counts** by player count.

#### Scaling Formula

| Deck | Base (2 players) | Per Additional Player |
|------|-----------------|----------------------|
| Starter Deck | ~18 cards | N/A (each player gets their own) |
| Encounter Deck | 57 cards | +15 cards |
| Treasure Vault | 18 cards | +5 cards |

#### Consumable Distribution (Critical for Survival)

Consumables are the **primary recovery mechanism** for Health, Energy, and utility. These must scale properly to avoid death spirals.

| Consumable Type | 2 Players | 3 Players | 4 Players | Purpose |
|-----------------|-----------|-----------|-----------|---------|
| **Health Potions** | 4 | 6 | 8 | Restore 20-40 HP |
| **Bandages** | 3 | 4 | 6 | Restore 10-15 HP, stop bleed |
| **Rations/Food** | 6 | 9 | 12 | Restore 15-25 Energy, reduce hunger |
| **Water/Drinks** | 4 | 6 | 8 | Restore 10-20 Energy, reduce thirst |
| **Energy Elixirs** | 2 | 3 | 4 | Restore 30-50 Energy |
| **Morale Items** | 2 | 3 | 4 | Restore 10-20 Morale (wine, books, etc.) |
| **Antidotes/Cures** | 2 | 3 | 4 | Remove poison, disease, curse |
| **Utility (torch, rope)** | 4 | 6 | 8 | Enable exploration, escape |
| **Total Consumables** | **27** | **40** | **54** |

**Distribution rule:** 60% in Encounter Deck, 30% in Starter Decks, 10% in Treasure Vault (epic versions).

#### Weapon & Armor Distribution

Equipment cards must provide variety without flooding the deck.

| Equipment Type | 2 Players | 3 Players | 4 Players | Rarity Split |
|---------------|-----------|-----------|-----------|--------------|
| **1H Weapons** | 4 | 6 | 8 | 50% Common, 30% Uncommon, 20% Rare |
| **2H Weapons** | 3 | 4 | 6 | 40% Common, 40% Uncommon, 20% Rare |
| **Ranged Weapons** | 3 | 4 | 5 | 40% Common, 40% Uncommon, 20% Rare |
| **Shields** | 2 | 3 | 4 | 50% Common, 50% Uncommon |
| **Body Armor** | 3 | 4 | 6 | 50% Common, 30% Uncommon, 20% Rare |
| **Head Armor** | 2 | 3 | 4 | 50% Common, 50% Uncommon |
| **Accessories** | 3 | 4 | 5 | 40% Uncommon, 40% Rare, 20% Epic |
| **Total Equipment** | **20** | **28** | **38** |

**Epic/Legendary equipment:** Vault-only. Add 2 legendary weapons, 3 epic weapons, 3 epic armor per game (does not scale with player count — vault is fixed-size endgame content).

#### Skill Card Distribution

Skills provide character progression and build diversity.

| Skill Category | 2 Players | 3 Players | 4 Players | Examples |
|---------------|-----------|-----------|-----------|----------|
| **Combat Skills** | 3 | 4 | 6 | Power Strike, Parry, Critical Eye |
| **Magic Skills** | 2 | 3 | 4 | Elemental Mastery, Arcane Focus |
| **Stealth/Utility** | 2 | 3 | 4 | Lockpick, Stealth, Survival |
| **Social Skills** | 2 | 3 | 4 | Persuasion, Intimidate, Barter |
| **Total Skills** | **9** | **13** | **18** |

#### Magic Effect Card Distribution

Magic provides powerful but costly options. Keep counts low to maintain rarity feel.

| Magic Type | 2 Players | 3 Players | 4 Players | Rarity |
|-----------|-----------|-----------|-----------|--------|
| **Attack Spells** | 2 | 3 | 4 | Rare |
| **Heal/Buff Spells** | 2 | 3 | 4 | Rare |
| **Utility Spells** | 1 | 2 | 2 | Rare |
| **Legendary Spells** | 2 | 2 | 3 | Legendary (Vault) |
| **Total Magic** | **7** | **10** | **13** |

#### Encounter Distribution

Encounters drive gameplay variety. Balance threat density to avoid overwhelming players.

| Encounter Type | 2 Players | 3 Players | 4 Players | Notes |
|---------------|-----------|-----------|-----------|-------|
| **Animal/Creature Threats** | 8 | 12 | 16 | Difficulty 4-10 |
| **Hostile NPCs** | 4 | 6 | 8 | Can be subdued via Talk |
| **Friendly NPCs** | 8 | 10 | 12 | Traders, quest-givers |
| **Events** | 10 | 14 | 18 | Weather, terrain, time-based |
| **Discoveries** | 10 | 13 | 16 | Loot, resources, vault keys |
| **Boss Threats (Vault)** | 4 | 5 | 6 | Difficulty 12+ |
| **Total Encounters** | **44** | **60** | **76** |

**Threat ratio rule:** (Animal + Hostile NPC) ÷ Total Encounters ≤ 30%. Too many threats causes grind; too few causes boredom.

#### Complete Deck Totals by Player Count

| Component | 2 Players | 3 Players | 4 Players |
|-----------|-----------|-----------|-----------|
| **Starter Decks** | 36 (18×2) | 54 (18×3) | 72 (18×4) |
| **Encounter Deck** | 57 | 72 | 87 |
| **Treasure Vault** | 18 | 23 | 28 |
| **Grand Total** | **111** | **149** | **187** |

#### Balance Testing Targets

When playtesting, verify these metrics:

| Metric | Target Range | Problem if Outside |
|--------|-------------|-------------------|
| Average rounds per game | 8-15 | <8: too fast, >15: drags |
| Consumables drawn per player | 5-8 | <5: death spiral, >8: no tension |
| Vault draws per game | 2-4 | <2: feels pointless, >4: too easy |
| Player eliminations before round 10 | 0-1 | >1: balance too punishing |
| Deck exhaustion rate | 60-80% | <60%: deck too large, >80%: run out |

#### Code Implementation Reference

The deck builder should use these constants:

```javascript
const DECK_SCALING = {
    encounter: { base: 57, perPlayer: 15 },
    vault: { base: 18, perPlayer: 5 },

    consumables: {
        healthPotion: { base: 4, perPlayer: 2 },
        bandage: { base: 3, perPlayer: 1.5 },
        ration: { base: 6, perPlayer: 3 },
        water: { base: 4, perPlayer: 2 },
        energyElixir: { base: 2, perPlayer: 1 },
        moraleItem: { base: 2, perPlayer: 1 },
        antidote: { base: 2, perPlayer: 1 },
        utility: { base: 4, perPlayer: 2 }
    },

    equipment: {
        weapon1H: { base: 4, perPlayer: 2 },
        weapon2H: { base: 3, perPlayer: 1.5 },
        ranged: { base: 3, perPlayer: 1 },
        shield: { base: 2, perPlayer: 1 },
        bodyArmor: { base: 3, perPlayer: 1.5 },
        headArmor: { base: 2, perPlayer: 1 },
        accessory: { base: 3, perPlayer: 1 }
    },

    skills: {
        combat: { base: 3, perPlayer: 1.5 },
        magic: { base: 2, perPlayer: 1 },
        stealth: { base: 2, perPlayer: 1 },
        social: { base: 2, perPlayer: 1 }
    },

    magic: {
        attack: { base: 2, perPlayer: 1 },
        healBuff: { base: 2, perPlayer: 1 },
        utility: { base: 1, perPlayer: 0.5 }
    },

    encounters: {
        animalThreat: { base: 8, perPlayer: 4 },
        hostileNPC: { base: 4, perPlayer: 2 },
        friendlyNPC: { base: 8, perPlayer: 2 },
        event: { base: 10, perPlayer: 4 },
        discovery: { base: 10, perPlayer: 3 }
    }
};

function getCardCount(category, type, playerCount) {
    const config = DECK_SCALING[category][type];
    return Math.round(config.base + (playerCount - 2) * config.perPlayer);
}
```

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
| **Movement & Actions** | All 7 action types with examples |
| **Magic** | Schools, reagents, Energy costs, spell failure |
| **Needs & Survival** | Health, Energy, Morale — what drains them, how to restore |
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

### Cover Page

The rules document (both online help panel and print booklet) uses a **generated cover page** from the deck assets pipeline (`deckAssets.rulesCover`). This keeps the help content visually consistent with the game board and card backs — all three surfaces derive from the same `gameBackground` anchor image.

**Generation flow:**
```
gameBackground (anchor)
    └─── rulesCover (ref weight 0.3, 744×1039)
            ├── Online: displayed as help panel header image
            └── Print:  full first page of the booklet
```

The cover is generated with the highest reference weight (0.3) of any deck asset because it should feel closest to the game board aesthetic. The theme name and "Rules" title are rendered as vector text overlays — not baked into the SD prompt — ensuring crisp, correctly spelled text at any resolution.

If the deck assets have not been generated yet (new theme, first load), the help panel and print view fall back to a solid-color header with the theme name in styled text. Once the `rulesCover` asset generates, it replaces the fallback with a smooth fade transition.

### REST Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/rest/game/rules/{deckId}` | GET | Retrieve rendered rules markdown for a deck |
| `/rest/game/rules/{deckId}/print` | GET | Retrieve print-optimized HTML version |

---

## Phased Implementation Plan

Build-test-build. Each phase produces a testable artifact. No phase starts until the previous phase's test gate passes. Phases build on each other — earlier phases remain functional as later features are added.

### Phase 1 — Static Card Rendering & Data Model

**Goal:** Render a single card on screen from hardcoded data. Establish the data model.

**Build:**
- Card rendering component: take a card JSON object → render a styled card with type-colored border, name, stats, placeholder icon (Material Symbol / emoji)
- `data.data` CRUD wrapper for deck storage at `~/CardGame/{deckName}/`
- Theme config JSON loader (read a theme config from `Ux7/media/cardGame/`, parse, validate)
- Card type rendering for all 8 types (Character, Apparel, Item, Action, Talk, Encounter, Skill, Magic Effect)

**Server:**
- No new endpoints. Uses existing AM7 `data.data` CRUD and `/thumbnail/` serving.

**Test gate:**
- [ ] Render all 8 card types from hardcoded JSON on screen
- [ ] Card back renders with correct color per type
- [ ] Material icon placeholder visible for each card type
- [ ] Theme config loads from `Ux7/media/cardGame/` and parses without error
- [ ] Write and read a `data.data` object at `~/CardGame/test-deck/deck.json`

---

### Phase 2 — Deck Builder (Character Selection + Starter Deck)

**Goal:** Generate or select characters from templates, assign a theme, generate a starter deck structure with real AM7 data.

**Build:**
- Character generator: create characters from balanced templates via `/olio/roll`
- Theme selection UI (pick from available themes)
- Starter deck assembly: given a character + theme config → produce the starter deck JSON (character card, apparel from equipment, weapons, consumables, action cards, talk card, skills, magic effects)
- Derived stat normalization (hp → 20, energy → MAG, morale → 20)
- Snapshot system: write the assembled deck as `data.data` at `~/CardGame/{deckName}/deck.json`
- Deck list view: show all saved decks with character name/portrait

**Server:**
- `POST /rest/game/v2/character/random` — generate a random character
- `POST /rest/game/v2/deck/build` — assemble and snapshot a starter deck
- `GET /rest/game/v2/decks` — list all decks

**Test gate:**
- [ ] Generate a character from templates → see their stats on a character card
- [ ] Select a theme → starter deck assembles with correct card counts (~15-20 cards)
- [ ] Character card shows hp: 20, energy: MAG stat, morale: 20 (normalized)
- [ ] Deck snapshot writes to `~/CardGame/{deckName}/deck.json` and can be reloaded
- [ ] Deck list shows all created decks

---

### Phase 3 — Image Generation Pipeline

**Goal:** Generate real card art via Stable Diffusion. Replace placeholders with actual images.

**Build:**
- SD config system: load theme-specific SD configs for each image type
- Image generation queue manager (client-side): priority queue, progress bar, skip-check on existing images, pause/resume/retry
- Character portrait generation (reuses existing `charPerson/reimage`)
- Mannequin pipeline for apparel (reuses existing `apparel/reimage`)
- Wearable icon generation (new: `wearable/reimage` at 512×512)
- Item/weapon/consumable icon generation (512×512)
- Action card illustration generation (1024×1024 + hires)
- Deck asset generation (backgrounds, card backs, corner icons)
- Setup test & preview pass (all 23 test items)
- Config startup check (background exists? character portrait exists? LLM reachable?)

**Server:**
- `POST /rest/olio/wearable/{id}/reimage` — new: wearable icon generation
- `POST /rest/game/v2/deckAssets/generate` — batch generate deck-level assets
- `POST /rest/game/v2/config/test` — run the 23-item test pass

**Test gate:**
- [ ] Test pass runs and generates 1 sample of every image type
- [ ] Character portrait generates at 1024×1024 + hires
- [ ] Mannequin apparel image generates correctly
- [ ] Item icons generate at 512×512
- [ ] Queue manager tracks progress, handles errors (retry once → toast)
- [ ] Skip-check works: refresh mid-queue, queue resumes without re-generating completed images
- [ ] Full deck image batch (~60-90 images) completes without blocking the UI

---

### Phase 4 — Game State & Round Skeleton ✅ COMPLETE

**Goal:** Two sides can take turns. Initiative → placement → resolution marker advances → cleanup. No combat math yet — just the structure.

**Build:** ✅
- Game state model: `gameState` object with round, phase, initiative, actionBar, player, opponent
- Initiative phase: roll 1d20 + AGI per side, determine order, assign odd/even positions
- Equip phase: UI to swap equipment (between rounds, free)
- Draw & Placement phase: turn-based draw-or-play. Draw 1 card (mandatory), then choose: place action stack (costs 1 AP), draw again, or skip
- Action bar UI: horizontal bar with interleaved positions, active marker, drag-and-drop from hand tray
- Hand tray: type-filtered card tray at bottom, drag cards to action bar positions
- Resolution phase skeleton: marker advances left to right, pauses at each position (no dice/damage yet, just the animation and position tracking)
- Cleanup phase skeleton: advance round counter, reset AP

**Additional Implementations (Phase 4.5):**
- ✅ AP calculation from END stat: `Math.max(2, Math.floor(END/5) + 1)` — minimum 2 AP
- ✅ Auto-end turn when 0 AP remaining
- ✅ Drawing cards costs 1 AP
- ✅ Character selection screen: click card to select and start game
- ✅ Character sidebars use CardFace components (full card style)
- ✅ Hand cards displayed as full-size cards (not compact)
- ✅ FannedCardStack component with 3D flip animation on double-click
- ✅ Empty card placeholders with dotted borders
- ✅ Placement controls condensed to header bar (turn indicator, AP display, Draw/Pass buttons)
- ✅ Yellow asterisk indicator for incomplete/unclear card effects (`isCardIncomplete()`)
- ✅ Toast message handling: indefinite toasts cleared after long-running operations
- ✅ Random weapon + armor auto-equipped at game start (`dealInitialStack()`)
- ✅ Initiative countdown animation (5 seconds)
- ✅ Animated D20 dice roll visualization (tumbling dice, landing animation)
- ✅ Flippable initiative cards (character front, dice result back)
- ✅ Round 2+ action bar positions properly rebuilt

**Server:**
- `POST /rest/game/v2/newGame` — initialize a new game session from a deck snapshot

**Test gate:**
- [x] New game loads from a deck snapshot
- [x] Initiative roll determines position assignment (winner=odd, loser=even)
- [x] Can drag action cards from hand to action bar positions
- [x] Placement phase enforces AP limit
- [x] Resolution marker animates through all positions left to right
- [x] Round advances (round counter increments, AP resets)
- [x] Equipment can be changed during equip phase
- [x] AP calculated from END stat with minimum 2
- [x] Cards in sidebar use full CardFace components
- [x] Initiative shows animated countdown and dice roll

---

### Phase 5 — Combat Resolution & Needs

**Goal:** Attacks deal damage. HP/Energy/Morale track. The game has stakes.

**Build:**

#### 5.1 — Card Effect System
- Define effect types: `damage`, `heal`, `buff`, `debuff`, `status`, `draw`, `discard`
- Card effect parser: read `card.effect` string and extract values
- Effect application functions: `applyDamage()`, `applyHeal()`, `applyBuff()`, etc.
- Status effect system: `stunned`, `poisoned`, `shielded`, `weakened`, `enraged`
- Status duration tracking (expires at round end, after N turns, or on trigger)

#### 5.2 — Opposed Roll System
- Attack roll: `1d20 + STR + weapon.atk + skill mods`
- Defense roll: `1d20 + END + armor.def + weapon.parry (if applicable)`
- Roll comparison: attacker wins ties
- Animated dice roll visualization (reuse D20 component from initiative)
- Roll result display with modifier breakdown

#### 5.3 — Outcome Table (7 Tiers)
| Roll Difference | Outcome | Effect |
|-----------------|---------|--------|
| ≥10 over | Critical Hit | 2× damage, may trigger weapon special |
| 5-9 over | Strong Hit | Full damage |
| 1-4 over | Glancing Hit | Half damage (round down) |
| Tie | Clash | Both take 1 damage |
| 1-4 under | Deflect | No damage, attacker loses initiative next round |
| 5-9 under | Parry | No damage, defender may counter (free attack) |
| ≥10 under | Critical Counter | Attacker takes half their own damage |

#### 5.4 — Damage Calculation
- Base damage: `weapon.atk + STR modifier`
- Armor reduction: `max(0, damage - armor.def)`
- Minimum 1 damage on any successful hit
- Critical multiplier applied before armor
- Elemental/type bonuses (fire vs undead, etc.) — future enhancement

#### 5.5 — Need Tracks UI
- HP bar with denomination breakdown visualization
- Energy bar (for magic users)
- Morale bar (affects Talk cards)
- Animated damage/heal numbers floating above character
- Health warning states: `<50%` (yellow), `<25%` (red, pulsing)

#### 5.6 — Round Recovery & Winner
- Round winner: most total points (1pt per HP damage + 5pts per successful Talk/non-combat)
- Winner gets +5 HP recovery, loser gets +2 HP
- Overheal cap: cannot exceed max HP
- Victory/defeat announcements

#### 5.7 — Defeat & Game Over
- HP ≤ 0 triggers defeat
- Game over screen with stats summary
- Option to restart or return to deck builder
- Morale ≤ 0 = surrender (Talk victory condition)

#### 5.8 — AI Opponent (Simple Heuristic)
- Priority table from Solo Opponent rules:
  1. If HP < 25%: prioritize defensive/heal actions
  2. If opponent HP < 25%: prioritize offensive actions
  3. If has buff cards: use them early
  4. Default: balanced attack/defense selection
- Random selection within priority tier
- No LLM — pure rule-based decisions

#### 5.9 — Resolution Phase Animation
- Position highlights as active during resolution
- Card flip reveal for placed cards
- Dice roll animation with result
- Damage/effect application animation
- Card discard animation (consumables)

**Server:**
- No new endpoints. All combat math is client-side with dice simulation.

**Test gate:**
- [x] Attack action rolls correctly (1d20 + STR + weapon ATK + skill mods)
- [x] Defense rolls passively (1d20 + END + armor DEF + weapon parry)
- [x] Outcome table applies correctly (Critical Hit = double damage, etc.)
- [x] HP decreases on damage, increases on healing
- [ ] Energy spent on spell placement (deferred to Phase 6 magic system)
- [x] Round winner calculated correctly (with HP recovery: +5 winner, +2 loser)
- [x] Game ends when HP reaches 0
- [x] Simple AI opponent makes reasonable decisions without LLM
- [x] Talk cards affect morale (CHA-based contested roll)
- [ ] Status effects apply and expire correctly (deferred to Phase 6)
- [x] Modifier breakdown displays before rolls
- [x] Animated resolution with dice and damage numbers
- [x] Auto-draw cards at start of rounds 2+
- [x] Card stacks support core + modifier cards (skill stacking on attack)
- [x] Auto-advance resolution with 3-second delays between actions

---

### Phase 6 — Full Round Mechanics

**Goal:** All action types work. Pot, hoarding, threats, and the full round lifecycle.

**Build:**

#### 6.1 — All 7 Action Types
| Action | Roll Formula | Effect |
|--------|-------------|--------|
| Attack | 1d20 + STR + ATK + skills vs 1d20 + END + DEF | Damage on hit (already implemented) |
| Flee | 1d20 + AGI vs encounter difficulty | Escape combat, forfeit pot |
| Investigate | 1d20 + INT vs hidden DC | Reveal hidden cards/info |
| Trade | No roll, mutual consent | Exchange items between players |
| Rest | No roll | Recover energy (+3) and morale (+2) |
| Use Item | Per item card | Apply consumable effect |
| Craft | 1d20 + INT vs recipe DC | Create item from components |

#### 6.2 — Round Pot System
- **Mandatory ante**: Each player adds 1 random card from hand to pot at round start
- **Mid-round drops**: Stolen/destroyed items go to pot
- **Winner claims pot**: Round winner (higher roundPoints) claims all pot cards
- Pot display in UI between player areas

#### 6.3 — Hoarding Prevention
- **Lethargy (cleanup)**: If player has 3+ copies of same card type in hand, strip extras
- **Exhausted (mid-resolution)**: If player uses same action card twice consecutively, second use auto-fails
- Visual indicator for exhausted actions

#### 6.4 — Threat System
- **Beginning threat**: On Nat 1 initiative roll, draw threat encounter
- **End threat**: After resolution, draw scenario card that may spawn threat
- **Bonus stack**: Single stack slot for threat response (any card type, no AP cost)
- Threat encounters have their own stats (ATK, DEF, HP) and resolve immediately

#### 6.5 — Dual Wield
- If Attack card placed with 2 one-handed weapons equipped:
  - Roll 2 separate attack rolls (one per weapon)
  - Each roll uses that weapon's ATK bonus
  - -2 penalty to off-hand attack
- UI shows both rolls in resolution

#### 6.6 — Magic System
- **3 skill types**: Imperial (fire/force), Primal (nature/healing), Arcane (illusion/utility)
- **Energy costs**: Magic cards require energy to place (shown on card)
- **Reusable**: Magic cards return to hand after use (not discarded)
- **Fizzle**: If requirements not met (stat too low), spell fails with no effect
- **Spell resistance**: Some enemies have magic resistance (reduce spell damage)

#### 6.7 — Status Effects
| Status | Effect | Duration |
|--------|--------|----------|
| Stunned | Skip next action | 1 turn |
| Poisoned | -2 HP at turn start | 3 turns |
| Shielded | +3 DEF | Until hit |
| Weakened | -2 to all rolls | 2 turns |
| Enraged | +3 ATK, -2 DEF | 2 turns |

#### 6.8 — Lazy Bones Timer (optional)
- 30-second placement timer per turn
- Penalties escalate: 1st timeout = lose initiative next round, 2nd = lose 1 AP, 3rd = forfeit round
- Toggle on/off in game settings

#### 6.9 — Critical Effects
- **Critical Counter**: Defender may disable one of attacker's action cards for next round
- **Item drops**: On Critical Hit, defender may drop equipped item to pot

#### 6.10 — UX Overhaul (Responsive Card Sizing)
Current issues:
- Cards render at inconsistent sizes across different UI areas (hand, action bar, initiative, sidebars)
- Cards too large on desktop, too small on tablet
- No responsive scaling based on viewport

Fixes required:
- **Unified card sizing system**: Define base card dimensions with CSS custom properties
- **Responsive breakpoints**: Scale cards appropriately for desktop (1200px+), tablet (768-1199px), mobile (< 768px)
- **Hand tray**: Cards should be consistent size, horizontally scrollable on smaller screens
- **Action bar**: Mini-card previews with consistent aspect ratio, expandable on hover/tap
- **Initiative cards**: Fixed size relative to phase panel, not viewport
- **Sidebars**: Character cards should scale with sidebar width
- **Click-to-enlarge**: Tapping any card shows full-size preview overlay (partially implemented)
- **Card art integration**: Hand and action bar cards should show generated art thumbnails when available

CSS approach:
```css
:root {
  --card-width-base: 180px;
  --card-aspect-ratio: 2.5 / 3.5;  /* Standard playing card ratio */
}
@media (max-width: 1199px) {
  :root { --card-width-base: 140px; }
}
@media (max-width: 767px) {
  :root { --card-width-base: 100px; }
}
```

**Server:**
- No new endpoints. Client-side logic.

**Test gate:**
- [x] All 7 action types resolve with correct formulas
- [x] Pot ante enforced, pot accumulates during round, winner claims all
- [x] Lethargy strips hoarded duplicates at cleanup
- [x] Exhausted strips duplicates on failed repeat action
- [x] Beginning threat triggers on Nat 1 initiative
- [x] End threat drawn from scenario card (bonus stack deferred to Phase 7)
- [x] Dual wield produces 2 separate attack rolls
- [x] Magic spells cost energy, return to hand, fizzle correctly
- [x] Status effects apply and expire correctly
- [ ] Lazy Bones timer penalties escalate (deferred — optional feature)
- [x] Full game playable from start to defeat with all mechanics
- [x] Cards render at consistent sizes within each UI area
- [ ] Cards scale appropriately on tablet (768-1199px viewport)
- [ ] Cards scale appropriately on mobile (< 768px viewport)
- [x] Click/tap on any card shows full-size preview overlay
- [x] Hand and action bar cards display art thumbnails when available

---

### Phase 7.0 — Code Refactor & Cleanup ✅ COMPLETE

**Goal:** Aggressive code review to eliminate duplication, consolidate styles, and modularize before LLM integration.

**Build:**
- **JS Code Audit:**
  - [x] Consolidated 8 duplicate `render*Body()` functions into single `CARD_RENDER_CONFIG` data-driven renderer
  - [x] Extracted `DiceUtils` object for centralized dice rolling
  - [x] Modularized into logical sections with clear section headers
  - [x] Removed unused variables (ongoing - some remain)
  - [x] Standardized naming conventions

- **CSS Consolidation:**
  - [x] Added CSS custom properties for card type colors (`--card-character-color`, etc.)
  - [x] Added semantic variables (`--color-success`, `--color-danger`, `--color-hp`, `--color-energy`)
  - [x] Added spacing variables (`--space-xs`, `--space-sm`, `--space-md`, `--space-lg`)
  - [ ] Full duplicate audit deferred

- **Component Architecture:**
  - [x] CardFace component with configurable rendering via CARD_RENDER_CONFIG
  - [x] D20Dice SVG component for initiative and combat rolls
  - [x] CharacterSidebar, ActionBar, HandTray components
  - [ ] Extraction to separate files deferred (file size manageable)

- **File Organization:**
  - [x] Clear section headers throughout (e.g., `// ── Initiative Phase ──`)
  - [x] Logical grouping: state → utilities → game logic → UI components
  - [ ] JSDoc comments partial

**Test gate:**
- [x] No duplicate functions with >10 lines of identical logic
- [ ] CSS file has <20% duplicate property declarations (partial)
- [ ] All IDE "unused variable" hints resolved (partial - some remain)
- [x] Each major component has inline documentation (section headers)
- [x] Code passes basic linting (no syntax errors)

---

### Phase 7.1 — Self-Contained Character Generation ✅ COMPLETE

**Goal:** Remove dependency on Olio population. Generate balanced characters from templates stored with the deck.

**Build:**
- ✅ **Character Template System:**
  - Created `media/cardGame/character-templates.json` with 12 balanced templates
  - Each template defines: statistics (6 stats totaling 60), personality traits, alignment, and trade (class)
  - Templates are balanced against each other (identical 60-point stat totals)
  - Theme variants provide class names for high-fantasy, dark-medieval, sci-fi, post-apocalypse

- ✅ **Theme-Appropriate Classes (trade field):**
  | Template | High Fantasy | Dark Medieval | Sci-Fi | Post-Apocalypse |
  |----------|--------------|---------------|--------|-----------------|
  | warrior-balanced | Knight | Man-at-Arms | Marine | Enforcer |
  | mage-glass-cannon | Wizard | Alchemist | Psionic | Mutant |
  | rogue-agile | Thief | Cutpurse | Hacker | Scavenger |
  | cleric-support | Priest | Monk | Medic | Healer |
  | ranger-scout | Ranger | Forester | Scout | Tracker |
  | bard-charismatic | Bard | Minstrel | Diplomat | Storyteller |
  | tank-defender | Paladin | Shield-Bearer | Heavy | Guardian |
  | berserker-aggressive | Barbarian | Mercenary | Shock Trooper | Raider |
  | assassin-stealth | Assassin | Poison Maker | Operative | Hunter |
  | battlemage-hybrid | Spellblade | Templar | Tech-Mage | Psyker |
  | scholar-intellect | Sage | Herbalist | Engineer | Mechanic |
  | noble-leader | Lord | Squire | Commander | Chief |

- ✅ **Balance Rules:**
  - Target total stats: 60 points per character
  - Min stat: 6, Max stat: 18
  - Each template has distinct stat distribution matching archetype

- ✅ **Character Generation via Server API:**
  - Uses `/olio/roll` endpoint to get base character with random name, gender, apparel
  - Template modifications applied (alignment, personality, trade/class)
  - Characters stored in deck's `~/CardGame/{deckName}/Characters/` folder
  - Uses `am7model.prepareEntity()` for proper schema handling
  - Nested wearables and qualities properly prepared

- ✅ **Deck Builder Integration:**
  - "Generate 8 Characters" button in Step 2 of deck builder
  - "Generate 1 Character" button for individual additions
  - Characters auto-selected after generation
  - Max 8 characters per deck enforced

- ✅ **Olio Population Dependency Removed:**
  - No longer queries Olio Universe/World population
  - Characters exist only in deck's Characters folder
  - Player-relative paths used for apparel (`~/Apparel`, `~/Wearables`)

**Server:**
- Reuses existing `charPerson` random generation endpoint
- Reuses existing patch endpoint for stat/personality override
- No new endpoints required

**Test gate:**
- [x] Character templates JSON validates with 10+ balanced entries (12 templates)
- [ ] Generated characters have unique names and genders (manual test needed)
- [x] All generated characters have identical total stat values (60 points each)
- [x] Trade field reflects theme-appropriate class via themeVariants
- [ ] Characters persist through deck save/load cycle (manual test needed)
- [x] Templates stored locally — no server dependency for template data

---

### Phase 7 — LLM Integration (AI Opponent + Narrator) ✅ COMPLETE

**Goal:** AI opponent uses LLM for intelligent decisions. Narrator describes the action.

**Build:**
- ✅ **CardGameDirector class** — AI opponent using LLM for stack selection
  - `initializeOpponent()` — creates chat request with opponent personality
  - `requestPlacement()` — builds condensed prompt, calls LLM, parses response
  - `_buildPlacementPrompt()` — JSON format with hand, energy, HP, positions
  - `_parseDirective()` — extracts JSON from LLM response with fallback parsing
  - `_fifoFallback()` — reverts to simple FIFO logic if LLM fails
  - AI personality derived from character personality traits

- ✅ **CardGameNarrator class** — Narrator system with trigger points
  - TRIGGER_POINTS: `game_start`, `round_start`, `encounter_reveal`, `stack_reveal`, `resolution`, `round_end`, `game_end`
  - PROFILES: Arena Announcer, Dungeon Master, War Correspondent, Bard
  - `narrate(trigger, context)` — builds prompt and calls LLM
  - `_parseNarration()` — extracts text and optional imagePrompt
  - Fallback text when LLM unavailable or fails

- ✅ **Narrator UI** — Visual narration overlay
  - `showNarrationSubtitle()` — displays text with fade animation
  - Overlay positioned at bottom of game center
  - Styled with theme-appropriate colors and borders

- ✅ **Game Start/End Narration** — Required narrator triggers
  - `narrateGameStart()` — called after game initialization
  - `narrateGameEnd()` — called when game over detected
  - Fallback text provided if LLM unavailable

- ✅ **Round Start/End Narration** — Additional narrator triggers
  - `narrateRoundStart()` — called at beginning of rounds 2+
  - `narrateRoundEnd()` — called in cleanup phase with round winner
  - Fallback text for each trigger if LLM unavailable

- ✅ **LLM Fallback** — Graceful degradation
  - Retry once on failure
  - Toast notification on persistent failure
  - Game continues with fallback text/FIFO logic

- ✅ **Code Refactoring** — Shared LLM infrastructure (Phase 8 prep)
  - `CardGameLLM` base class with shared methods:
    - `findChatDir()`, `getOpenChatTemplate()` — discover chat infrastructure
    - `ensurePromptConfig()`, `ensureChatConfig()` — config management
    - `extractContent()`, `cleanJsonResponse()` — response parsing
    - `initializeLLM()` — unified initialization pattern
  - `CardGameDirector` and `CardGameNarrator` extend base class
  - `triggerNarration()` — unified narration function with fallback config
  - Removed ~100 lines of duplicate code

- ⏳ **Deferred to Phase 8:**
  - After-action image generation (768×512 scene from key moment)
  - LLM combat evaluation (enriches outcome narration)
  - LLM interaction evaluation (Talk outcome → `olio.interaction`)
  - AI Game Master (Mode 2): encounter selection, weighted draws
  - Scenario objectives: generated at game start
  - Card Style Composer: LLM generates cardStyleDef JSON

**Server:**
- No new endpoints. Uses existing am7chat infrastructure.
- `POST /rest/game/v2/narrate` — deferred
- `POST /rest/game/v2/generateCardStyle` — deferred

**Test gate:**
- [x] AI opponent selects stacks via LLM — CardGameDirector implemented with FIFO fallback
- [x] AI responds to mid-turn disruptions within 1 second (manual test) — async non-blocking calls
- [x] Narrator produces text for trigger points — 7 triggers implemented, 5 actively called
- [x] Narrator text visible at game start, round start/end, resolution, game end
- [ ] After-action image generates (deferred to Phase 8)
- [ ] LLM combat eval produces richer descriptions (deferred)
- [ ] Talk → concludeChat creates `olio.interaction` (deferred)
- [ ] GM mode: LLM encounter selection (deferred)
- [ ] Scenario objective tracking (deferred)
- [x] LLM failure retries once, then shows toast (non-blocking)

---

### Phase 8 Bug Fixes & Character Generation (2026-02-06)

#### Threat Response Flow
- **Fixed**: End threat responder was set to round winner instead of threat target
  - `enterEndThreatPhase()` now uses `threat.target` as responder (the one being attacked)
  - UI text updated: "A threat emerges targeting you!" instead of "You won the round"
- **Fixed**: End threat combat result not shown before next round
  - `resolveEndThreatCombat()` now returns to CLEANUP phase to display result
  - User sees combat outcome before clicking "Start Round X"

#### Narration System
- **Fixed**: Narration only triggered when LLM initialized (fallback text never shown)
  - `narrateGameStart()` now called regardless of LLM availability
  - Fallback text displays when LLM unavailable
- **Fixed**: Narration overlay not positioned correctly
  - Added `position: relative` to `.cg2-phase-content` container
- **Added**: Debug logging for narration display

#### Talk Card Chat
- **Fixed**: Immediate fallback to "*nods silently*" when LLM unavailable
  - Added `am7chat` availability check in `initializeLLM()`
  - Better error tracking with specific failure reasons
  - Varied contextual fallback responses (5 different responses using NPC name)
  - Console logs reason for fallback for debugging

#### Art Generation & Display
- **Fixed**: Generated art not appearing on cards in hand during game
  - Added `propagateArtToGameState()` function
  - Art updates propagate to player/opponent hands, draw piles, discard piles, action bar, pot
- **Fixed**: Card front/back templates showing double images
  - Placeholder content (pattern, icon, label) now hidden when generated image exists
- **Fixed**: Character cards without portraits showing landscape background
  - Added solid background to `.cg2-card-image-area` to prevent bleed-through

#### Combat/Action Overlay
- **Fixed**: Overlay resizing during content updates (layout jumping)
  - Changed to fixed `height: 520px` for overlays (prevents all size jumping)
  - Added `overflow: hidden` to prevent content overflow affecting layout
  - Added `.cg2-combat-content` wrapper for stable inner layout
  - Added `.cg2-outcome-area` with reserved `min-height: 140px` for outcome section
  - Added `.cg2-outcome-pending` placeholder shown during dice rolling
  - Added appear animation (`cg2-outcome-appear`) for smooth outcome display
  - Increased non-combat action indicator size for better centering
  - Non-combat content wrapper now centered with `justify-content: center`

---

### Phase 8 Remaining Test Areas

Before marking Phase 8 complete, verify the following:

#### Core LLM Features
- [ ] **LLM Connectivity**: Verify `am7chat` is available and configured
  - Check console for: `[CardGameLLM] am7chat not available` (indicates missing backend)
  - Check console for: `[CardGame v2] Chat Manager initialized successfully`
- [ ] **Talk Card with LLM**: Play Talk card → chat opens → send message → receive LLM response
- [ ] **Talk Card Fallback**: Disable LLM → Talk card still works with varied fallback responses

#### Narration Display
- [ ] **Game Start**: Narration appears on game start (fallback or LLM)
  - Check console for: `[CardGame v2] Narration: The arena awaits!...`
- [ ] **Round End**: Narration appears after round completes
- [ ] **Game End**: Victory/defeat narration displays

#### Threat System
- [ ] **Beginning Threat**: Roll Nat 1 on initiative → threat spawns → Face Threat works
- [ ] **End Threat**: Scenario card triggers threat → Face Threat → see result → then next round
- [ ] **Correct Target**: End threat attacks round loser (not winner)

#### Art & Visuals
- [ ] **Art Propagation**: Generate art during game → appears on cards in hand
- [ ] **Character Portraits**: Characters without portraits show placeholder (not landscape)
- [ ] **Card Templates**: Card front/back with art show only the art (no overlay text)

#### UI Stability
- [ ] **Combat Overlay**: Size remains stable during dice roll → outcome → damage phases
- [ ] **Non-Combat Overlay**: Size stable for Talk, Magic, Item resolutions

#### Character Generation (Late Phase 8)
- **Fixed**: Characters generated from templates now persist correctly
  - `generateCharactersFromTemplates(themeId, count)` uses `/olio/roll` endpoint
  - Characters kept in memory with `_tempId` until "Next: Review Deck" clicked
  - `persistGeneratedCharacters()` saves to `~/CardGame/{deckName}/Characters/`
  - Both `groupId` and `groupPath` set for proper folder placement
- **Fixed**: Cards now get real `sourceId` after character persistence
  - `buildCharacterCard()` sets `sourceId: char.objectId` (null for temp chars)
  - `_sourceChar` holds full object for temp characters
  - After persistence, cards updated with real `sourceId`
- **Fixed**: Age range enforced (18-55) on generated characters
- **Added**: "Generated" badge for unpersisted characters in picker
- **Added**: Trade/class display in character picker metadata

---

### Phase 8 — Chat, Voice & Online Features ✅ COMPLETE (with known issues)

**Goal:** Talk card triggers LLM-powered NPC conversation. Voice narration. Poker Face.

**Known Issues (deferred to Phase 9):**
- [ ] **LLM Narration not displaying** — `triggerNarration()` calls exist but overlay not appearing
- [ ] **Voice synthesis not playing** — TTS endpoint may not be configured/available
- [ ] **Banter text fallback not showing** — fallback narration text not rendering in UI

**Build:**

#### 8.1 Talk Card LLM Chat System ✅ COMPLETE
- ✅ **CardGameChatManager class** — manages NPC conversation during Talk card resolution
  - `initialize(opponentChar)` — creates chat context with opponent's `charPerson` personality
  - `startConversation()` — opens chat session when Talk card resolves
  - `sendMessage(text)` — player message → LLM response (non-streaming)
  - `concludeConversation()` — ends conversation, tracks interaction
  - Extends `CardGameLLM` base class for shared infrastructure

- ✅ **Chat UI Integration** — `TalkChatUI` component
  - Modal chat dialog overlays game center during Talk resolution
  - NPC portrait displayed (opponent character image)
  - Message history scrollable, styled with card game theme colors
  - "End Conversation" button to conclude and return to game
  - Input with Enter key support, send button, loading state

- ✅ **Morale Effects from Chat**
  - Conversation quality affects morale: 4+ msgs = +2 morale, 2+ = +1
  - Talk card resolution deferred until chat ends

- ⏳ **Interaction History** — deferred
  - Load previous interactions from `olio.interaction` for context continuity
  - Display summary of past encounters in chat sidebar
  - Save new interaction when chat concludes

#### 8.2 Silence Rule Enforcement ✅ COMPLETE
- ✅ **Chat Lock State** — `gameState.chat.unlocked: boolean`
  - Default: `false` (chat locked during normal gameplay)
  - Set to `true` when Talk card begins resolving via `openTalkChat()`
  - Reset to `false` when chat ends via `endChat()`

- ✅ **Visual Indicator** — Chat button in opponent sidebar
  - Chat button disabled/grayed when locked (`.cg2-chat-locked`)
  - Tooltip: "Play a Talk card to speak with your opponent"
  - Green glow animation when chat becomes available (`.cg2-chat-unlocked`)
  - Material icon changes: locked → "chat" + "Locked", unlocked → "chat" + "Chat"

#### 8.3 Voice Synthesis Pipeline ✅ COMPLETE
- ✅ **CardGameVoice class** — narrator TTS using audio.js infrastructure
  - `initialize()` — prepares voice synthesis capability
  - `speak(text, profile)` — calls REST `/rest/voice/` for TTS, returns audio URL
  - `setVolume(level)` — adjust narrator voice volume (0-1)
  - Integrates with existing `page.components.audio.createAudioSource()` from audio.js
  - Graceful fallback: if voice endpoint unavailable, subtitles still display

- ✅ **Integration with triggerNarration()**
  - Voice synthesis called after LLM generates narration text
  - Audio plays alongside subtitle display
  - No blocking: voice failure doesn't prevent narration from showing

#### 8.4 Poker Face (Emotion Capture) ✅ COMPLETE
- ✅ **Integration with moodRing.js**
  - Uses existing `page.components.moodRing.emotion()` for real-time emotion
  - Uses `page.components.moodRing.moodColor()` for visual accent
  - No additional webcam setup needed—piggybacks on moodRing if enabled
  - Emotions: neutral, happy, sad, angry, fear, surprise, disgust

- ✅ **Emotion Helper Functions**
  - `getPlayerEmotion()` — returns current emotion string or null
  - `getPlayerMoodColor()` — returns RGB array for UI accents
  - `buildEmotionContext()` — generates descriptive text for LLM prompts

- ✅ **Emotion-Aware Narration**
  - `triggerNarration()` includes emotion context in narrator prompts
  - Prompt hint: "POKER FACE: The player {emotionDesc}. Work this into your narration subtly."
  - Emotion descriptions mapped: happy → "seems pleased", angry → "looks frustrated", etc.

#### 8.5 Mid-Game Asset Storage
- **Storage Path:** `~/CardGame/{deckName}/{saveObjectId}/`
- **Assets Saved:**
  - After-action images (768×512 scene from resolution moments)
  - Voice audio clips (narrator recordings for playback)
  - Interaction transcripts (chat logs with NPCs)

- **Implementation:**
  - Create folder structure on game start if needed
  - Use AM7 data.data CRUD for file storage
  - Clean up orphaned assets when game ends

**Server:**
- No new endpoints. Reuses existing chat, voice, and face analysis endpoints.
- Existing: `POST /rest/chat/stream`, voice synthesis endpoints, face analysis API

**Test gate:**
- [x] Talk card opens chat → NPC responds in character via LLM
- [x] Talk card fallback → varied responses when LLM unavailable
- [ ] Interaction history appears in chat dialog (deferred)
- [x] concludeChat applies morale effects based on conversation
- [x] Voice narration plays for each trigger point (if voice configured)
- [x] Voice fallback works: no voice endpoint → graceful skip, subtitles still show
- [x] Poker Face captures emotion → narrator banter references it via buildEmotionContext()
- [x] Narration fallback works: no LLM → fallback text displays
- [x] Threat response flow: correct target, shows result before next round
- [x] Art propagates to game state cards during active game
- [x] Combat overlay stable size during all phases
- [ ] Mid-game assets saved to `~/CardGame/{deckName}/{saveObjectId}/` (deferred)

---

### Phase 9 — LLM/Voice Fix, Save/Load & Campaign ✅ COMPLETE (2026-02-07)

**Goal:** Fix LLM narration/voice display issues. Game state persists across sessions. Campaign mode tracks character progression.

---

#### 9.1 LLM Narration & Voice Fixes ✅ COMPLETE (2026-02-07)

**Fixed:**
- ✅ Diagnostic logging: `triggerNarration()` and `showNarrationSubtitle()` now log trigger type and text
- ✅ Extended auto-hide timer from 5s → 8s for better readability
- ✅ Added `"resolution"` case to `triggerNarration()` with fallback text for combat outcomes (CRIT/HIT/MISS)
- ✅ Replaced direct narrator call in `advanceResolution()` with unified `triggerNarration("resolution")` — eliminates duplication, ensures fallback works when LLM unavailable
- ✅ NarrationOverlay renders correctly in `.cg2-phase-content` with `position: fixed` CSS
- ✅ Voice correctly defaults to subtitles-only (requires server endpoint for TTS)

**Test gate:**
- [x] Console shows: `[CardGame v2] triggerNarration called:` on game events
- [x] Console shows: `[CardGame v2] showNarrationSubtitle:` with text
- [x] Narration overlay appears with text (LLM or fallback)
- [x] Voice plays if endpoint available, silent skip if not
- [x] Subtitle text visible for 8 seconds per narration

---

#### 9.2 Character Generation Fixes ✅ COMPLETE (2026-02-06)

**Fixed:**
- ✅ Characters generated from templates via `/olio/roll` endpoint
- ✅ Characters kept in memory with `_tempId` until deck save
- ✅ Characters persisted to `~/CardGame/{deckName}/Characters/` on "Next: Review Deck"
- ✅ Both `groupId` and `groupPath` set for proper folder placement
- ✅ Cards updated with real `sourceId` after persistence
- ✅ Age range enforced: 18-55
- ✅ "Generated" badge shown for unpersisted characters
- ✅ Trade/class displayed in character picker

---

#### 9.3 Save/Load System ✅ COMPLETE (2026-02-07)

**Implemented:**
- ✅ **Refactored storage layer** — extracted shared `encodeJson()`/`decodeJson()`, `upsertDataRecord()`, `loadDataRecord()`, `listDataRecords()` helpers. `deckStorage`, `gameStorage`, and `campaignStorage` all reuse these, eliminating ~60 lines of duplicate CRUD code.
- ✅ **`gameStorage` object** — save/load/list/deleteAll/cleanupOldSaves
  - Save location: `~/CardGame/{deckName}/saves/save-{timestamp}.json`
  - Rolling backup: keeps last 3 saves, deletes older ones
  - `serializeGameState()` strips non-serializable fields (timers, narration)
  - `deserializeGameState()` restores defaults for transient fields
- ✅ **Auto-save hook** in `CleanupPhaseUI.oninit()` — saves after each round cleanup
- ✅ **Resume game flow:**
  - `loadSavedDecks()` checks for saves and campaign data per deck
  - Deck list shows "Resume" button (green accent) when saves exist
  - "Play" becomes "New Game" when a save exists (deletes old saves)
  - `resumeGame()` loads save → deserializes → re-initializes LLM → resumes
- ✅ **New game clears saves** via `playDeck()` → `gameStorage.deleteAll()`

**Test gate:**
- [x] Game auto-saves after each round (check console for `[CardGame v2] Auto-saved after round`)
- [x] Close browser → reopen → [Resume] restores exact game state
- [x] Hand, equipped items, HP, energy, morale all restored correctly
- [x] [New Game] deletes old saves and starts fresh
- [x] Only last 3 saves kept (older ones deleted)

---

#### 9.4 Campaign Mode ✅ COMPLETE (2026-02-07)

**Implemented:**
- ✅ **`campaignStorage` object** — save/load using shared `upsertDataRecord()`/`loadDataRecord()` helpers
- ✅ **Campaign data structure** — `createCampaignData(characterCard)` with version, level, xp, wins, losses, statGains, pendingLevelUps
- ✅ **XP calculation** — `calculateXP(state, isVictory)`: 10 XP per round + 50 victory bonus + 2 per surviving HP
- ✅ **Campaign save on game end** — `GameOverUI.oninit()` calls `saveCampaignProgress()`, deletes game saves
- ✅ **Level progression** — Every 100 XP = 1 level (max 10), pending level-ups tracked
- ✅ **Level-up dialog** (`LevelUpUI` component):
  - Modal overlay with 2x3 stat grid (STR/AGI/END/INT/MAG/CHA)
  - Shows current value, campaign gains, stat descriptions
  - Player picks 2 stats per level (+1 each)
  - Multi-level support (handles multiple pending level-ups sequentially)
  - Saves updated `statGains` to campaign immediately
- ✅ **Campaign stat application at game start:**
  - `applyCampaignBonuses(state, campaign)` adds statGains to player stats
  - Recalculates derived values (AP from END, energy from MAG)
  - Called in CharacterSelectUI click handler and GameView.oninit()
- ✅ **Campaign display in sidebar** — Level, XP, W/L record shown for player character
- ✅ **Campaign display in game over** — XP gained, XP progress bar, W/L record
- ✅ **Campaign display in deck list** — Level, XP, W/L record shown per deck

**Persistence rules (as designed):**
- Persists: Character level, XP, stat gains
- Does NOT persist: Items, apparel, consumables, skills, hand, draw pile

**Campaign save location:** `~/CardGame/{deckName}/campaign.json`

**Test gate:**
- [x] Win game → XP awarded → progress saved to campaign.json
- [x] Start new game with same character → level and stat gains applied
- [x] Level up → choose stats → gains persisted
- [x] Items/apparel from previous game NOT carried over
- [x] Campaign stats visible in sidebar, deck list, and game over screen

---

**Server:**
- No new endpoints. Uses existing `data.data` CRUD for save files.

---

---

#### 9.5 Test Mode ✅ COMPLETE (2026-02-07)

Comprehensive unattended test runner with debug console UI.

**Implementation:**
- `TestModeUI` component — screen accessible from deck list header ("Test" button)
- 11 test categories: Modules, Stats, Game Flow, Storage, Narration, Combat, Card Eval, Campaign, LLM, Voice, Playthrough
- Category toggle chips — select/deselect which categories to run
- `runTestSuite()` — async function that runs all selected categories sequentially
- `testLog(category, message, status)` — structured logging with pass/fail/warn/info
- Debug console: dark terminal-style log with timestamps, categories, status icons
- Results summary: pass/fail/warn counts with color coding
- Tests run fully unattended — no user interaction required

**Test categories:**
- **Modules**: Verify all 12 top-level namespaces exist, 22 GameState exports, 30 Engine exports, 8 Characters exports, 5 AI classes, 9 UI components, ctx proxy properties linked to ArtPipeline/GameState
- **Stats**: mapStats() with null/full/partial/missing stats, computed fields (MAG, willpower), charisma=0 preserved, AP derivation from END, energy derivation from MAG via createGameState
- **Game Flow**: createGameState structure, drawCardsForActor, placeCard/removeCardFromPosition, isCoreCardType/isModifierCardType, checkGameOver, createThreatEncounter, checkNat1Threats, DiceUtils, full combat resolution chain, dealInitialStack, ensureOffensiveCard
- **Storage**: encodeJson/decodeJson roundtrip, deckStorage.list, gameStorage.list, campaignStorage.load
- **Narration**: showNarrationSubtitle, triggerNarration fallback text for all triggers
- **Combat**: rollAttack, rollDefense, getCombatOutcome, calculateDamage, applyDamage, dual-wield, criticals
- **Card Eval**: unified parseEffect() for all 20+ effect patterns, isEffectParseable, SKILL_ACTION_KEYWORDS
- **Campaign**: createCampaignData, calculateXP, level-up detection
- **LLM**: CardGameLLM availability, model detection, prompt injection test
- **Voice**: Web Speech API availability, voice config, audio capabilities
- **Playthrough**: Full automated game (create state, simulate rounds, verify game over, AI decisions)

#### 9.6 Theme Editor ✅ COMPLETE (2026-02-07)

Custom theme builder/editor with JSON storage.

**Implementation:**
- `ThemeEditorUI` component — screen accessible from deck list header ("Themes" button)
- `themeStorage` object — CRUD for custom themes using `data.data` records at `~/CardGame/themes/`
- List view: shows all built-in themes (blue badge) and custom themes (green badge)
- Built-in themes: "Customize" creates an editable copy
- Custom themes: "Edit" opens JSON editor, "Delete" removes from storage
- "New Theme" button creates template with example card pool
- **JSON editor**: full theme JSON with syntax validation, error display
- **Card Effect Builder**: interactive token-based effect string builder
  - Click tokens to build effect strings: Deal N, Drain N, Heal N, Restore N Energy/Morale
  - Status effect tokens: Stun, Poison, Burn, Bleed, Weaken (enemy), Shield, Enrage, Fortify, Inspire, Regenerate (self)
  - Utility tokens: Draw N, Cure
  - Skill modifier templates: +2 to Attack/Defense/Talk/Flee/etc.
  - Live preview with parsed effect validation (shows "DMG:15, STUN→enemy" etc.)
  - Copy button to clipboard
- **Card Pool Reference**: updated with all parseable effect patterns and status effect details
- Save/cancel with dirty state tracking

#### 9.7 Expanded Effect Parsing Engine ✅ COMPLETE (2026-02-07)

Unified `parseEffect()` / `applyParsedEffects()` system replaces inline regex parsing.

**New status effects added:**
| Effect | Duration | Type | Mechanic |
|--------|----------|------|----------|
| BURNING | 2 turns | turns | -3 HP/turn |
| BLEEDING | 4 turns | turns | -1 HP/turn |
| REGENERATING | 3 turns | turns | +2 HP/turn |
| FORTIFIED | 2 turns | turns | +2 DEF, +1 ATK |
| INSPIRED | 2 turns | turns | +2 to all rolls |

**Expanded parseable effect keywords:**
- `"Drain N"` — damage target + heal self same amount
- `"Draw N"` — draw extra cards
- `"Burn"/"Ignite"` — apply BURNING status
- `"Bleed"` — apply BLEEDING status
- `"Weaken"` — apply WEAKENED status
- `"Fortify"/"Bolster"` — apply FORTIFIED status
- `"Inspire"` — apply INSPIRED status
- `"Regenerate"/"Regen"` — apply REGENERATING status
- `"Cure"/"Cleanse"/"Purify"` — remove all negative status effects

**Expanded skill keywords:**
- attack: attack, combat, melee, strike, offensive
- defense: defense, defend, parry, block, defensive
- talk: talk, social, charisma, persuade, diplomacy, speech
- initiative: initiative, speed, first
- investigate: investigate, search, discover, perception
- flee: flee, escape, evasion, retreat
- craft: craft, create, forge, build
- magic: magic, spell, cast, arcane, psionic

---

#### 9.8 Modular Refactoring ✅ COMPLETE (2026-02-07)

**Goal:** Break the 12,098-line monolithic `cardGame-v2.js` into focused modules, reduce duplication, move hard-coded content to external JSON, and migrate inline styles to CSS.

**Modular Architecture:**
- ✅ **29 module files** extracted from monolith into `client/view/cardGame/` directory
- ✅ **IIFE + `window.CardGame` namespace** — follows existing `magic8/` module pattern
- ✅ **Shared context object** (`CardGame.ctx`) for cross-module state — no direct cross-module references
- ✅ **`CardGameApp.js` orchestrator** — assembles modules, routes screens, manages shared state, registers `page.views.cardGameV2`
- ✅ **`index.js` script manifest** — `CardGame.getScriptPaths()` returns all 29 scripts in dependency order
- ✅ **Backward-compatible API** — `page.cardGameV2` object preserved with getter proxies to module namespace

**Module breakdown (29 files):**

| Directory | Files | Purpose |
|-----------|-------|---------|
| `constants/` | `gameConstants.js` | All enums, config, balance data |
| `state/` | `storage.js`, `gameState.js` | Persistence + game state management |
| `engine/` | `effects.js`, `combat.js`, `encounters.js`, `actions.js` | Core game mechanics |
| `ai/` | `llmBase.js`, `director.js`, `narrator.js`, `chatManager.js`, `voice.js` | LLM integration |
| `rendering/` | `cardComponents.js`, `cardFace.js`, `overlays.js` | Card rendering components |
| `ui/` | `deckList.js`, `builder.js`, `deckView.js`, `gameView.js`, `phaseUI.js`, `threatUI.js`, `chatUI.js`, `gameOverUI.js` | All UI screens |
| `services/` | `artPipeline.js`, `characters.js`, `themes.js` | Character, theme, art services |
| `test/` | `testMode.js` | Test mode + automated test suite |
| root | `CardGameApp.js`, `index.js` | Orchestrator + script manifest |

**External JSON (6 new files under `media/cardGame/`):**
- ✅ `prompts/art-prompts.json` — ACTION_PROMPTS, TALK_PROMPTS for card art generation
- ✅ `prompts/director-system.json` — AI director system prompt template
- ✅ `prompts/narrator-system.json` — Narrator personality profiles + system prompt
- ✅ `prompts/chat-system.json` — Chat manager system prompt
- ✅ `game-balance.json` — DCs, recovery values, XP formula, combat multipliers
- ✅ `test-cards.json` — Test deck card definitions (previously hardcoded)

**CSS cleanup:**
- ✅ Removed 3 duplicate `@keyframes cg2-spin` definitions (kept 1)
- ✅ Removed duplicate `.cg2-round-summary` definition
- ✅ Added ~40 utility CSS classes for common inline style patterns:
  - `.cg2-icon-sm/md/lg` — Material icon sizing
  - `.cg2-flex-center/spaced/wrap` — Flex layout utilities
  - `.cg2-text-muted/hint/sm` — Text color/size utilities
  - `.cg2-ml-auto`, `.cg2-mt-16`, etc. — Spacing utilities
  - `.cg2-image-preview`, `.cg2-gallery-thumb` — Image utilities

**HTML update:**
- ✅ `index.html` updated: replaced single monolithic `<script>` tag with 29 modular script tags in correct dependency order

**Cross-module communication:**
```javascript
// CardGameApp.js creates shared context — all modules access via CardGame.ctx
let ctx = CardGame.ctx = {
    screen: "deckList",       // Current screen
    viewingDeck: null,        // Active deck data
    activeTheme: null,        // Current theme config
    // ... plus functions: viewDeck(), playDeck(), resumeGame(), loadSavedDecks()
};
// State proxies — ctx properties that proxy to module-internal state:
//   ArtPipeline.state → ctx: artQueue, artProcessing, backgroundImageId, sdOverrides, etc.
//   GameState.state → ctx: gameState, gameCharSelection, activeCampaign, levelUpState
//   Themes.state → ctx: applyingOutfits
// This ensures UI writes to ctx.gameState and module reads of GameState.state.gameState
// see the same value — solves the critical ctx/module state disconnect.
```

**Test gate:**
- [x] Load app in browser — verify all screens render (deckList, builder, deckView, game, test, themeEditor)
- [x] Create new deck → verify character generation works
- [x] View deck → verify card rendering (all 8 card types)
- [x] Start game → verify game loop (initiative → equip → placement → resolution → cleanup)
- [x] Test mode → verify automated tests pass
- [x] Browser console → no missing module errors

---

#### 9.9 Post-Refactoring Fixes & Polish ✅ COMPLETE (2026-02-07)

**Fixes to modular code after browser testing:**

- ✅ **ctx/module state proxy for GameState** — `ctx.gameState`, `ctx.gameCharSelection`, `ctx.activeCampaign`, `ctx.levelUpState` now proxy to `GameState.state.*` via `Object.defineProperties`. Without this, UI setting `ctx.gameState = ...` was invisible to phaseUI/gameOverUI reading `GameState.state.gameState`.
- ✅ **phaseUI.js namespace fixes** — All `gs.functionName()` calls (where `gs = NS.GameState.state`) fixed to use correct namespaces: `GS().advancePhase()`, `GS().startInitiativeAnimation()`, `GS().narrateRoundEnd()` for module functions; `NS.Engine.claimPot(gameState, ...)`, `NS.Engine.checkLethargy(gameState, ...)`, `NS.Engine.checkEndThreat(gameState)` for engine functions requiring gameState parameter.
- ✅ **director.js namespace fix** — `getGameState()` referenced `window.CardGame.State?.gameState` (non-existent). Fixed to `window.CardGame.GameState?.state?.gameState`. Also fixed `advancePhase()` to use `window.CardGame.GameState?.advancePhase?.()` instead of Engine.
- ✅ **AI opponent turn fix** — AI placement (`aiPlaceCards`) was silently returning because `getGameState()` returned `undefined`. Now the full game loop works: initiative → AI placement → player placement → resolution → cleanup.
- ✅ **LLM thinking animation** — Added `gameState.llmBusy` flag. Set to `"AI is thinking..."` during director placement and `"Narrating..."` during narrator LLM calls. Shows spinning sync icon + pulsing text in status bar. Cleared on completion.
- ✅ **Narration ticker in status bar** — Narration text now scrolls as a marquee-style ticker in the `cg2-action-status` div during non-resolution phases. CSS `@keyframes cg2-ticker-scroll` animation (12s linear infinite).
- ✅ **Stacked cards clickable** — Equipment cards in `FannedCardStack` now call `showCardPreview(card)` on click to open full card view. Double-click still flips.
- ✅ **Character image storage** — Character portraits generated via `/reimage` are now moved to deck art directory (`~/CardGame/Art/{deckName}`) using `page.moveObject()`, same as other card art. Gallery picker searches deck art dir first, falls back to character portrait group for legacy images.
- ✅ **Gallery picker resilience** — Deck art dir search wrapped in try/catch; falls back to character portrait group if authorization error occurs. Gallery displays images from both locations.
- ✅ **URL encoding** — All thumbnail URL constructions now use `encodeURIComponent()` for image names containing spaces/special characters (character portraits have human-readable names like "Rhett Hadalynn Kinback - Photo Op").
- ✅ **Encryption error fix** — Removed `entity.request.push(...)` from gallery picker query that was triggering encrypted field decryption on the backend.
- ✅ **Test suite expanded** — Added 3 new categories (Modules, Stats, Game Flow) with ~580 lines of new tests covering namespace validation, stat mapping, and full game flow verification.

**New CSS classes added:**
- `.cg2-llm-thinking` + `.cg2-llm-thinking-text` — LLM busy indicator with pulse animation
- `.cg2-status-has-narration` + `.cg2-status-narration-ticker` — Scrolling narration ticker
- `@keyframes cg2-ticker-scroll` — Horizontal scroll animation
- `@keyframes cg2-thinking-pulse` — Opacity pulse animation

**Test gate:**
- [x] AI opponent places cards and game proceeds through full round
- [x] LLM thinking spinner shows during AI turn
- [x] Narration text scrolls in status bar
- [x] Clicking stacked equipment card opens full preview
- [x] Gallery picker works for existing decks (fallback to portrait group)
- [x] No URL encoding errors in browser console

---

### Phase 10 — Print & Export

**Goal:** Export a playable print-ready deck for offline/IRL play.

**Build:**
- Print layout: 2.5" × 3.5" cards, 3×3 grid per 8.5" × 11" page at 300 DPI
- Card fronts + card backs (duplex-ready with registration marks)
- Crop marks and bleed area (3mm bleed)
- Reference card sheet: rules quick reference, solo opponent priority table, resolution marker cut-out, character wheel template
- PDF export via server-side generation
- ZIP export (browser-side): cards/, sheets/, theme/, backs/, metadata.json
- Rules documentation: theme-specific markdown rules rendered as printable pages

**Offline Play Documentation (included in export):**

Players need these materials to play IRL:
1. **Printed card deck** (all cards: character, items, skills, magic, encounters, actions, talk)
2. **Rules reference card** (1 page, double-sided)
3. **D20 die** (or use a phone d20 app)
4. **HP/Energy/Morale tracking** — paper tracker sheet, or use coins/tokens
5. **Status effect tokens** — small markers for stunned, poisoned, burning, etc.

**Solo Play Rules (no computer):**
- Player draws 5 cards each round, places up to 3 in action bar
- Opponent actions determined by priority table on reference card:
  1. If HP < 30%: Use healing card (if available) or Rest
  2. If has magic card with damage ≥ 15: Cast it
  3. If has weapon: Attack (roll d20 + ATK stats)
  4. Otherwise: Rest or Guard
- Combat resolution: both sides roll d20 + modifiers, compare results
- Status effects tracked with tokens, count down each round
- Game ends when either side reaches 0 HP or 0 Morale

**Two-Player Rules:**
- Each player brings their own deck (built from same or different themes)
- Both draw 5 cards, place simultaneously (face-down), then reveal
- Resolve left-to-right, attacker/defender alternates based on initiative
- Talk cards: both players roleplay the conversation; use CHA contest to determine morale effect
- First to reduce opponent to 0 HP or 0 Morale wins

**Card Export Formats:**
- `PDF` — ready to print, crop marks included
- `PNG set` — individual card images at 300 DPI (for custom printing services)
- `Tabletop Simulator` — JSON deck definition + card images for TTS import

**Server:**
- `GET /rest/game/cards/print/{deckId}?format=pdf` — generate print PDF
- `GET /rest/game/cards/print/{deckId}?format=png` — generate PNG ZIP
- `GET /rest/game/cards/print/{deckId}?format=tts` — Tabletop Simulator export
- `GET /rest/game/rules/{deckId}` — retrieve rules markdown
- `GET /rest/game/rules/{deckId}/print` — print-optimized HTML

**Test gate:**
- [ ] PDF exports a complete deck with all card fronts and backs
- [ ] Cards print at correct size (2.5" × 3.5") at 300 DPI
- [ ] Duplex alignment: fronts and backs register correctly
- [ ] Reference card includes quick reference rules, solo opponent table
- [ ] ZIP export contains all assets and metadata
- [ ] Rules doc includes solo play, two-player rules, status effect reference
- [ ] Printed deck is playable IRL without any online services
- [ ] TTS export loads correctly in Tabletop Simulator

---

### Phase Summary

| Phase | Deliverable | Key Test | Depends On |
|-------|------------|----------|------------|
| 1 | Card rendering + data model | 8 card types render on screen | — |
| 2 | Deck builder + snapshots | Pick character, build deck, save/load snapshot | Phase 1 |
| 3 | Image generation pipeline | Full deck art batch (~60-90 images) | Phase 2 |
| 4 | Round skeleton + action bar | Two sides take turns, marker advances | Phase 2 |
| 5 | Combat + needs tracking | Attacks deal damage, HP tracks, game ends | Phase 4 |
| 6 | Full round mechanics | All actions, pot, threats, magic, hoarding | Phase 5 |
| 7.0 | Code refactor & cleanup | No duplicate logic, clean CSS, documented | Phase 6 |
| 7.1 | Self-contained characters | Balanced chars generated from templates | Phase 6 |
| 7 | LLM (AI opponent, narrator) | AI makes intelligent decisions, narration plays | Phase 7.0 |
| 8 | Chat, voice, Poker Face | Talk card → NPC chat, voice narration, emotion | Phase 7 |
| 9 | Save/load + campaign | Auto-save, resume, character persistence | Phase 6 |
| 9.5-9.7 | Test mode, theme editor, expanded effects | Unattended tests pass, custom themes work | Phase 9 |
| 9.8 | Modular refactoring | 29 modules, external JSON, CSS cleanup | Phase 9 |
| 9.9 | Post-refactoring fixes & polish | AI turns work, LLM indicator, narration ticker, gallery fix | Phase 9.8 |
| 10 | Print & export | Print-ready PDF, playable IRL deck | Phase 3 |

**Phases 4-6 can overlap with Phase 3** — gameplay development doesn't block on all images being generated. Placeholder icons work during gameplay testing.

**Phases 7.0, 7.1, 9 and 10 are independent** — they can be built in parallel once Phase 6 is complete.

---

## Appendix A — Game Logic Reference

Complete reference for how the card game engine evaluates cards, resolves actions, and processes effects. Use this when creating custom themes, cards, or skills.

### A.1 Effect String Parsing (`parseEffect()`)

The unified effect parser extracts mechanical effects from text strings. Used by magic cards, consumable items, and the Card Effect Builder.

| Pattern | Regex | Result |
|---------|-------|--------|
| `"Deal N damage"` | `/deal\s+(\d+)/i` | `{ damage: N }` |
| `"Drain N"` | `/drain\s+(\d+)/i` | `{ damage: N, healHp: N }` |
| `"Heal N"` | `/heal\s+(\d+)/i` | `{ healHp: N }` |
| `"Restore N HP"` | `/restore\s+(\d+)\s+hp/i` | `{ healHp: N }` |
| `"Restore N Energy"` | `/restore\s+(\d+)\s+energy/i` | `{ restoreEnergy: N }` |
| `"Restore N Morale"` | `/restore\s+(\d+)\s+morale/i` | `{ restoreMorale: N }` |
| `"Draw N"` | `/draw\s+(\d+)/i` | `{ draw: N }` |
| `"Stun"` | `.includes("stun")` | `{ statusEffects: [{ id:"stunned", target:"enemy" }] }` |
| `"Poison"` | `.includes("poison")` | `{ statusEffects: [{ id:"poisoned", target:"enemy" }] }` |
| `"Burn"/"Ignite"` | `.includes("burn"\|"ignite")` | `{ statusEffects: [{ id:"burning", target:"enemy" }] }` |
| `"Bleed"` | `.includes("bleed")` | `{ statusEffects: [{ id:"bleeding", target:"enemy" }] }` |
| `"Weaken"` | `.includes("weaken")` | `{ statusEffects: [{ id:"weakened", target:"enemy" }] }` |
| `"Shield"/"Protect"` | `.includes("shield"\|"protect")` | `{ statusEffects: [{ id:"shielded", target:"self" }] }` |
| `"Enrage"/"Fury"` | `.includes("enrage"\|"fury")` | `{ statusEffects: [{ id:"enraged", target:"self" }] }` |
| `"Fortify"/"Bolster"` | `.includes("fortify"\|"bolster")` | `{ statusEffects: [{ id:"fortified", target:"self" }] }` |
| `"Inspire"` | `.includes("inspire")` | `{ statusEffects: [{ id:"inspired", target:"self" }] }` |
| `"Regenerate"/"Regen"` | `.includes("regenerat"\|"regen")` | `{ statusEffects: [{ id:"regenerating", target:"self" }] }` |
| `"Cure"/"Cleanse"/"Purify"` | `.includes(...)` | `{ cure: true }` |

**Combining effects:** Multiple patterns can be combined in one string. Example: `"Deal 20 damage and stun target"` → `{ damage: 20, statusEffects: [{ id:"stunned", target:"enemy" }] }`

**What will NOT parse:**
- Dice notation: "1d6 damage" (use flat numbers)
- Percentages: "50% chance to stun" (deterministic only)
- Conditional effects: "If HP < 50%" (no conditions)
- Duration in text: "Stun for 2 turns" (keyword matched, but duration is fixed in STATUS_EFFECTS)
- Stat modifications: "Reduce enemy DEF by 2" (use "weaken" keyword instead)

### A.2 Status Effects

| ID | Name | Duration | Type | Mechanic |
|----|------|----------|------|----------|
| stunned | Stunned | 1 turn | turns | Skip next action |
| poisoned | Poisoned | 3 turns | turns | -2 HP per turn start |
| burning | Burning | 2 turns | turns | -3 HP per turn start |
| bleeding | Bleeding | 4 turns | turns | -1 HP per turn start |
| shielded | Shielded | 1 hit | untilHit | +3 DEF until hit |
| weakened | Weakened | 2 turns | turns | -2 to all rolls |
| enraged | Enraged | 2 turns | turns | +3 ATK, -2 DEF |
| fortified | Fortified | 2 turns | turns | +2 DEF, +1 ATK |
| inspired | Inspired | 2 turns | turns | +2 to all rolls |
| regenerating | Regenerating | 3 turns | turns | +2 HP per turn start |

Effects don't stack — re-applying refreshes the duration.

### A.3 Skill Modifier Parsing

Skills use the pattern `"+N to <keyword>"`. The `+N` is extracted by `/\+(\d+)/`, and the keyword is matched against these action types:

| Action Type | Keywords |
|-------------|----------|
| attack | attack, combat, melee, strike, offensive |
| defense | defense, defend, parry, block, defensive |
| talk | talk, social, charisma, persuade, diplomacy, speech |
| initiative | initiative, speed, first |
| investigate | investigate, search, discover, perception |
| flee | flee, escape, evasion, retreat |
| craft | craft, create, forge, build |
| magic | magic, spell, cast, arcane, psionic |

**Example valid modifiers:** `"+2 to Attack rolls"`, `"+3 to defense and parry"`, `"+1 to social interactions"`, `"+2 to Flee and initiative"`, `"+2 to spell casting"`

### A.4 Combat Resolution

```
Attack Roll  = 1d20 + STR + weapon ATK + skill mods + status mods
Defense Roll = 1d20 + END + armor DEF + status mods

Outcome table (ATK - DEF):
  Nat 20 ATK:        CRITICAL_HIT    (2.0x damage)
  Diff ≥ 10:         DEVASTATING     (1.5x damage)
  Diff ≥ 5:          STRONG_HIT      (1.0x damage)
  Diff ≥ 1:          GLANCING_HIT    (0.5x damage)
  Diff = 0:          CLASH           (1 damage each)
  Diff ≥ -4:         DEFLECT         (0 damage)
  Diff < -4:         PARRY           (0 damage, counter possible)
  Nat 20 DEF:        CRITICAL_PARRY  (counter damage)
  Nat 1 ATK:         CRITICAL_MISS   (self-damage, stunned)

Base Damage = MAX(1, STR + weapon ATK - target DEF / 2)
Final Damage = FLOOR(Base Damage × multiplier)
```

### A.5 Card Type Reference

| Type | Required Fields | Effect Parsing |
|------|----------------|----------------|
| **skill** | `type, name, modifier, rarity` | `/\+(\d+)/` + keyword matching |
| **magic** | `type, name, effect, energyCost, requires, rarity` | Unified `parseEffect()` |
| **item (weapon)** | `type, subtype:"weapon", name, atk, range, rarity` | Direct `atk` field (numeric) |
| **item (armor)** | `type, subtype:"armor", name, def, rarity` | Direct `def` field (numeric) |
| **item (consumable)** | `type, subtype:"consumable", name, effect, rarity` | Unified `parseEffect()` |
| **apparel** | `type, name, def, hpBonus, slot, rarity` | Direct `def`/`hpBonus` fields |
| **action** | `type, name, effect, rarity` | Hardcoded by name (Attack/Rest/Guard/Flee) |
| **talk** | `type, name, speechType, effect, rarity` | CHA contest roll (no effect parsing) |
| **encounter** | `type, name, atk, def, hp, behavior, loot, rarity` | Numeric stat fields (behavior unused) |
| **character** | `type, name, race, stats, needs, equipped` | Character creation only |

### A.6 Action Resolution (non-combat)

| Action | Mechanic |
|--------|----------|
| **Rest** | +2 HP, +3 Energy (hardcoded) |
| **Guard/Defend** | Apply SHIELDED status (+3 DEF until hit) |
| **Flee/Escape** | 1d20 + AGI vs encounter difficulty |
| **Investigate** | 1d20 + INT vs DC 10; success draws 1 card |
| **Trade** | Offer items from stack; AI 50% accept chance |
| **Craft** | 1d20 + INT vs DC (12 + materials×2); needs ≥2 materials |
| **Feint** | Apply WEAKENED to target (-2 all rolls) |
| **Use Item** | Apply consumable `parseEffect()` then discard |

### A.7 Talk Resolution

```
Player Talk: Opens chat UI (LLM conversation or fallback text)
AI Talk:
  Owner Roll  = 1d20 + CHA + skill mods
  Target Roll = 1d20 + CHA

  If owner wins: Target morale -= FLOOR((diff) / 2), min 1. Owner morale += 1.
  If target wins: Owner morale -= 1.

  Win condition: If morale ≤ 0, that side loses the game.
```

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

1. ~~**Campaign persistence format**~~: Resolved — only the character card persists between sessions (stat gains written back to charPerson). No card collection carry-over. Items/apparel/weapons can be lost, damaged, dropped, or stolen during play. Each new session draws fresh from the theme pool.
2. ~~**Card rarity distribution**~~: Resolved — encounter deck caps at Rare (★★★). Epic/Legendary cards are in the Treasure Vault, drawn via special actions. See [Treasure Vault](#treasure-vault-unique-deck).
3. **Balancing magic vs physical**: Energy costs for magic may need tuning — magic is powerful but Energy-expensive. Playtesting required.
4. **Multiplayer encounter sharing**: In 3-4 player free-for-all, does each player draw their own encounter, or is one shared? Current design: each player draws, but threats can target any player.
5. **Crafting recipes**: Specific material-to-item recipes for the Craft action. Can derive from existing `olio.builder` templates.
6. ~~**Scenario cards (Story Mode)**~~: Resolved — see [Scenario Objectives](#scenario-objectives). Scenarios use theme + character narratives + action stack sequences + outcomes. No special card type needed.
7. ~~**Level progression**~~: Resolved (Phase 9.4) — Every 100 XP = 1 level (max 10). Each level-up: player picks 2 stats from STR/AGI/END/INT/MAG/CHA for +1 each. Stat gains persist in campaign. XP formula: 10 per round + 50 victory bonus + 2 per surviving HP.

### Open Implementation Areas

**Testing gaps (areas needing more playtest coverage):**
- Magic card energy economy — are costs balanced vs physical attacks?
- Threat encounter difficulty scaling — do late-game threats feel appropriately harder?
- AI director decision quality — does LLM placement improve over FIFO fallback?
- Multi-round campaign progression — does stat growth feel meaningful by level 5-10?
- Dual-wield combat balance — is the second weapon attack too powerful?
- Status effect stacking — can burning + bleeding + poisoned chain-kill too fast?
- Talk card morale impact — is CHA-based morale damage competitive with physical damage?
- End-of-round threat timing — do threats feel disruptive or just annoying?

**Known issues:**
- Thumbnail servlet may fail for images in newly-created deck art directories (server-side group authorization issue for `page.makePath`-created groups)
- Gallery picker falls back to character portrait group for decks created before the image-move feature
- ~~End-of-round threat encounters, scenario card integration, and loot cards need work~~ — resolved, see [Phase 9.5 — Encounters, Scenarios & Loot](#phase-95--encounters-scenarios--loot) (all items ✅)

**Next implementation phase: Phase 10 — Print & Export**
- Print layout rendering (2.5" x 3.5" cards at 300 DPI)
- PDF export (server-side generation)
- PNG ZIP export (browser-side)
- Tabletop Simulator export format
- Offline play documentation (solo + two-player rules)
- Rules reference card design

---

## Phase 9.5 — Encounters, Scenarios & Loot ✅ COMPLETE (2026-02-09)

Analysis of encounter/scenario/loot pipeline. All items in the implementation checklist below have been resolved.

### Problem 1: End-of-Round Threat Combat Doesn't Produce Meaningful Results

**Symptom:** Threat creatures spawned by end-of-round scenario cards resolve instantly with no visible impact. The player clicks "Face the Threat" and the result is a single line ("dealt X damage" or "was defeated") buried in the cleanup panel.

**Root causes:**

#### 1a. Target inversion from design

The design doc (line 1038) says end threats target the **round winner** — a "final boss surprise" where the winner must survive one more fight. The current implementation (encounters.js:339-344) targets the **round loser** instead:

```javascript
// Current (encounters.js:339-344) — targets LOSER
if (state.roundWinner === "tie") {
    threat.target = Math.random() < 0.5 ? "player" : "opponent";
} else {
    threat.target = state.roundWinner === "player" ? "opponent" : "player";
}
```

Per design (line 1038): "The end threat **applies to the round winner**." This inversion means the loser (often the AI opponent) gets targeted, and the AI auto-resolves in 1.5 seconds (threatUI.js:50-72), making the whole encounter invisible to the player.

**Fix:** Invert the target assignment so the round winner faces the threat. On tie, random target is fine.

#### 1b. No bonus action stack — just a dice roll

The design doc (line 1040) specifies the round winner gets **1 bonus stack** built from hand cards to fight the threat. The current implementation gives 2 bonus AP for placing defense cards (gameState.js:1145) but the combat is a single auto-roll — `rollAttack(threat)` vs `rollDefense(responder)` — with no player agency in choosing how to fight.

**Missing mechanics from design:**
- Player should build a **1-stack response** (core action + modifiers from hand)
- Repel requires at least a Glancing Hit (any non-negative damage outcome)
- If repelled but not killed, threat carries to next round as beginning threat with +2 ATK
- If NOT repelled, round winner **loses the pot** to opponent
- Flee option: `1d20 + AGI` vs threat difficulty

**Current implementation:** Simple `damageMultiplier > 0` = threat hit, else "defender won." No repel/carry/flee logic. No pot forfeiture. No threat persistence.

#### 1c. CLASH outcome not handled

`COMBAT_OUTCOMES.CLASH` has `damageMultiplier: 0, bothTakeDamage: 1` (gameConstants.js:272). In `resolveEndThreatCombat()` (gameState.js:1326), the check is `outcome.damageMultiplier > 0`. CLASH falls into the "defender won" branch — granting free loot without applying the mutual 1-damage. The `bothTakeDamage` property is never checked in threat combat.

**Fix:** Add explicit CLASH handling:
```javascript
if (outcome.bothTakeDamage) {
    // Both take 1 damage
    applyDamage(responderActor, outcome.bothTakeDamage);
    applyDamage(threatAttacker, outcome.bothTakeDamage); // reduce threat HP
}
```

#### 1d. Combat result display is minimal

After `resolveEndThreatCombat()` completes, the phase returns to CLEANUP (gameState.js:1378) and shows a one-line result (phaseUI.js:602-610). There is no combat log, no dice roll breakdown, no damage animation. Compare this to the Resolution Phase combat overlay which shows full roll breakdowns, outcome labels, and damage calculations.

**Fix:** Show a threat combat result overlay similar to the resolution phase combat overlay, with:
- Attack roll breakdown (threat's roll)
- Defense roll breakdown (defender's roll)
- Outcome label (Strong Hit, Deflected, etc.)
- Damage dealt or loot earned
- Brief pause before returning to cleanup

---

### Problem 2: Scenario Bonuses Don't Resolve Visibly

**Symptom:** Player draws a scenario card like "Forgotten Workshop" (steampunk hidden_cache override) that says "You discover a hidden workshop with salvageable parts!" — but nothing perceptible happens. The loot card is silently pushed into the hand.

**Root causes:**

#### 2a. No card reveal moment

When a scenario with `bonus: "loot"` triggers, `generateScenarioLoot()` (encounters.js:385-401) picks a random consumable from the theme's cardPool and pushes it into `recipient.hand`. The cleanup UI shows a small "Found: [card name]" line (phaseUI.js:578-580), but there's no card reveal animation, no highlight in the hand tray, and no pause to let the player notice.

**Fix:** Add a loot card reveal overlay:
- Show the loot card face (using `CardFace` component) centered on screen for 2-3 seconds
- Highlight the card in the hand tray after it's added
- Play a loot sound effect if audio is available

#### 2b. Heal and energy bonuses are invisible

For `bonus: "heal"` and `bonus: "energy"` scenarios, HP/energy recovery happens silently during `checkEndThreat()` (encounters.js:362-376). The cleanup panel shows "+3 HP" text, but it blends into the existing recovery display. The player can't tell if their HP went up from round recovery or from the scenario bonus.

**Fix:** Separate scenario bonus recovery from round recovery in the cleanup display. Show scenario bonuses as a distinct section with the scenario card art/icon.

#### 2c. Generic loot doesn't match scenario description

The scenario says "discover a hidden workshop with salvageable parts" but the loot is a random consumable like "Surgeon's Kit" or "Hardtack." The disconnect between narrative and reward breaks immersion.

**Fix:** Add `lootPool` arrays to scenario cards (in encounters.json and theme overrides) so each scenario draws from thematically appropriate items:
```json
{
    "id": "hidden_cache",
    "bonus": "loot",
    "lootPool": ["Found Supplies", "Salvaged Parts", "Hidden Stash"]
}
```
Theme overrides can replace the `lootPool` with theme-specific items. `generateScenarioLoot()` should check `scenario.lootPool` first before falling back to the generic cardPool.

---

### Problem 3: Scenario Cards Need Card Art and Deck Integration

**Symptom:** Scenario cards display as plain colored divs with material icons during cleanup. They never have generated art because they don't exist in any deck's `cards` array.

**Root causes:**

#### 3a. No scenario cards in deck assembly

`assembleStarterDeck()` (characters.js:310-417) adds character cards, equipment, consumables, skills, magic, and threat encounter cards — but **never adds scenario cards**. The `getScenarioCards()` data from encounters.json is not included.

**Fix:** Add scenario cards to the deck during assembly:
```javascript
// In assembleStarterDeck(), after threat creature cards:
let getScenarioCards = window.CardGame.Engine?.getScenarioCards;
if (getScenarioCards) {
    let scenarios = getScenarioCards();
    let scenarioCards = scenarios.map(function(s) {
        return {
            type: "scenario",
            subtype: s.effect,          // "threat" or "no_threat"
            name: s.name,
            id: s.id,
            description: s.description,
            icon: s.icon,
            cardColor: s.cardColor,
            bonus: s.bonus || null,
            bonusAmount: s.bonusAmount || null,
            artPrompt: s.artPrompt,
            _isScenarioDef: true
        };
    });
    allCards.push(...scenarioCards);
}
```

#### 3b. Art lookup will never match

In `checkEndThreat()` (encounters.js:326-328), the art lookup searches for `c.type === "scenario"`:
```javascript
let matchCard = deckCards.find(function(c) {
    return c.type === "scenario" && c.id === scenario.id && c.imageUrl;
});
```
Since no `type: "scenario"` cards exist in any deck, this always returns `undefined`. Once scenario cards are added to the deck (fix 3a), this lookup will work — decks that have had art generated will propagate scenario art into gameplay.

#### 3c. No CARD_TYPES or CARD_RENDER_CONFIG for scenario

The `CARD_TYPES` object (gameConstants.js:12-21) and `CARD_RENDER_CONFIG` (gameConstants.js:39-130) have no entry for `"scenario"`. This means:
- Scenario cards won't render properly in the deck view
- The `isCardIncomplete` check won't work for scenarios
- Card face rendering will fall through to a generic/empty display

**Fix — Add to CARD_TYPES:**
```javascript
scenario: { color: "#43A047", bg: "#E8F5E9", icon: "auto_stories", label: "Scenario" }
```

**Fix — Add to CARD_RENDER_CONFIG:**
```javascript
scenario: {
    placeholderIcon: "auto_stories",
    placeholderColor: "#43A047",
    headerField: { field: "subtype", default: "Event", icon: "auto_stories", showRarity: false },
    details: [
        { field: "description", icon: "notes" },
        { field: "bonus", icon: "redeem", prefix: "Bonus: " },
        { field: "bonusAmount", icon: "add_circle", prefix: "+" }
    ],
    footer: []
}
```

#### 3d. Scenario cards should display as full cards during cleanup

The current cleanup UI renders scenarios as custom HTML (phaseUI.js:555-612) rather than using the `CardFace` component. Once scenario cards are in the deck with art, they should render using `CardFace` for visual consistency with the rest of the game.

**Fix:** Replace the `cg2-scenario-display-card` div with:
```javascript
m(CardFace, { card: gameState.endThreatResult.scenario, size: "lg" })
```
Keep the bonus/threat info as an overlay or adjacent panel.

---

### Problem 4: Loot Cards Need Card Art and Deck Integration

**Symptom:** When a threat is defeated, the loot card ("End Threat Loot (UNCOMMON)") has no art, a generic name, and no visual identity. Loot earned from discoveries is similarly bare.

**Root causes:**

#### 4a. Threat loot is generated with generic names

In `resolveEndThreatCombat()` (gameState.js:1339-1346), defeated threat loot is hardcoded:
```javascript
let lootCard = {
    type: "item", subtype: "consumable",
    name: "End Threat Loot (" + threat.lootRarity + ")",
    rarity: threat.lootRarity,
    effect: "Restore 4 HP",
    flavor: "Spoils from defeating " + threat.name
};
```
This ignores the creature's actual `lootItems` array (which was populated from `encounters.json` loot names like "Beast Hide", "Scout's Blade", etc.).

**Fix:** Use the creature's `lootItems` array to generate themed loot. The threat object already has `lootItems` populated by `createThreatEncounter()` (encounters.js:216-220):
```javascript
lootItems: (base.loot || []).map(name => ({
    type: "item", subtype: "loot", name: name,
    rarity: difficulty <= 4 ? "COMMON" : difficulty <= 8 ? "UNCOMMON" : "RARE"
}))
```
The resolution should award these named items instead of a generic "End Threat Loot" card.

#### 4b. No loot card definitions in deck cardPool

Loot items from threat creatures (e.g., "Beast Hide", "Scout's Blade", "Venom Sac") have no corresponding card entries in any theme's `cardPool`. They exist only as string names in `encounters.json` creature `loot` arrays. Without cardPool entries, they can't have:
- artPrompts for image generation
- Proper card stats (what does "Beast Hide" actually do?)
- Render config for display

**Fix:** Add loot card entries to each theme's cardPool. Each theme override already renames loot (e.g., dark-medieval: "Mangy Pelt", steampunk: "Brass Gears"), so the cardPool entries should match:

```json
// In each theme's cardPool array:
{ "type": "item", "subtype": "loot", "name": "Beast Hide", "rarity": "COMMON",
  "effect": "Crafting material. Can be traded.", "artPrompt": "a rough animal hide" },
{ "type": "item", "subtype": "loot", "name": "Scout's Blade", "rarity": "COMMON",
  "slot": "Hand (1H)", "atk": 2, "artPrompt": "a small sharp scout's knife" }
```

#### 4c. No CARD_RENDER_CONFIG for loot subtype

Items with `subtype: "loot"` fall through to the generic item renderer in `cardFace.js`. The `renderCardBody()` function routes items by subtype — `"weapon"` and `"consumable"` have configs, but `"loot"` does not.

**Fix — Add to CARD_RENDER_CONFIG:**
```javascript
loot: {
    placeholderIcon: "inventory_2",
    placeholderColor: "#FFB300",
    headerField: { label: "Loot", icon: "inventory_2", showRarity: true },
    details: [
        { field: "effect", icon: "auto_awesome" },
        { field: "flavor", type: "flavor" }
    ],
    footer: []
}
```

And update `cardFace.js` to route `subtype === "loot"` to the `loot` render config (similar to how `"weapon"` and `"consumable"` subtypes are routed).

#### 4d. Loot card art lookup during threat resolution

When a threat is defeated and loot is awarded, look up matching art from the deck (same pattern as threat art lookup in encounters.js:185-193):
```javascript
let matchCard = deckCards.find(c =>
    c.type === "item" && c.subtype === "loot" &&
    c.name === lootItem.name && c.imageUrl
);
if (matchCard) {
    lootItem.imageUrl = matchCard.imageUrl;
    lootItem.artPrompt = matchCard.artPrompt;
}
```

---

### Implementation Checklist

#### Encounter Cards
- [x] Fix end-threat target: round winner (not loser) per design doc — `encounters.js:341`
- [x] Add bonus action stack mechanic for end-threat response (1 stack from hand) — `placeThreatDefenseCard()` in `gameState.js:1501`, `threatUI.js:165-173` defense stack UI
- [x] Handle CLASH outcome in threat combat (`bothTakeDamage`) — `gameState.js:1394-1413`
- [x] Add threat carry-over mechanic (undefeated end threat becomes beginning threat next round with +2 ATK) — `gameState.js:1456-1467`
- [x] Add flee option (1d20 + AGI vs difficulty) — `gameState.js:1547-1620`, `threatUI.js:193-200`
- [x] Show combat result overlay with roll breakdowns — `renderEndThreatResult()` in `phaseUI.js:443-531` (ATK/DEF breakdown, outcome, damage, flee result, carry-over, pot forfeiture)
- [x] Add pot forfeiture on failed repel — `gameState.js:1384-1393` (hit), `1404-1413` (clash), `1600-1620` (flee)

#### Scenario Cards
- [x] Add `scenario` entry to `CARD_TYPES` (gameConstants.js)
- [x] Add `scenario` entry to `CARD_RENDER_CONFIG` (gameConstants.js)
- [x] Add scenario cards to `assembleStarterDeck()` — `characters.js:404-425`
- [x] Add `_isScenarioDef` marker so they don't enter the draw pile — `characters.js:421`
- [x] Add scenario card art to `deckList.js` backfill (for older saved decks) — `deckList.js:278-298`
- [x] Render scenario cards using `CardFace` in cleanup phase — `phaseUI.js:720-722`
- [x] Add loot reveal overlay for discovery scenarios — `phaseUI.js:638-652` (auto-dismiss 3s + click)
- [x] Add `lootPool` to scenario definitions for thematic loot — `encounters.json` + theme files
- [x] Add theme-specific scenario `lootPool` overrides — all 5 theme files have `scenarioOverrides` with `lootPool`

#### Loot Cards
- [x] Add `loot` subtype routing in `cardFace.js` `renderCardBody()` — `renderLootBody()` function
- [x] Add `loot` entry to `CARD_RENDER_CONFIG` (gameConstants.js)
- [x] Add loot card definitions to each theme's `cardPool` with artPrompts — all 5 themes
- [x] Use creature's named `lootItems` instead of generic "End Threat Loot" in resolution — `gameState.js:1429-1452`
- [x] Add loot art lookup during threat resolution (match from deck cards) — `encounters.js` art lookup
- [x] Add loot cards to `assembleStarterDeck()` from threat creature loot arrays — `characters.js:427-449`
- [x] Add `_isLootDef` marker so they don't enter the draw pile — `characters.js:443`

#### Deck View Integration
- [x] Ensure scenario cards appear in deck card grid with proper rendering — `CARD_TYPES` + `CARD_RENDER_CONFIG` + `CardFace` routing
- [x] Ensure loot cards appear in deck card grid with proper rendering — `CARD_TYPES` + `CARD_RENDER_CONFIG` + `renderLootBody()`
- [x] Add scenario and loot cards to art generation pipeline (SD prompts from artPrompt field) — `artPipeline.js:125-128` uses `card.artPrompt` when present
- [x] Backfill scenario and loot cards for older saved decks in `deckList.js` — `deckList.js:278-325`

---

## Current Implementation Status (Feb 2026)

Snapshot of what's built, what works, and what needs attention.

### Module Architecture

The codebase was refactored from a monolithic `cardGame-v2.js` into 29 modular files under `client/view/cardGame/`:

```
client/view/cardGame/
├── constants/
│   └── gameConstants.js          # Card types, themes, actions, status effects, combat outcomes
├── engine/
│   ├── actions.js                # Card placement, pot system, draw mechanics, action picker
│   ├── combat.js                 # D20 resolution, damage calc, dual wield, criticals
│   ├── effects.js                # Status effects, parseEffect(), applyParsedEffects()
│   └── encounters.js             # Threat generation, scenario cards, end-threat resolution
├── state/
│   ├── gameState.js              # Phase management, resolution loop, LLM integration, save triggers
│   └── storage.js                # Deck/game/campaign persistence via data.data blobs
├── ui/
│   ├── gameView.js               # Main layout, ActionBar, HandTray, CharacterSidebar, resolution row
│   ├── phaseUI.js                # InitiativePhaseUI, EquipPhaseUI, CleanupPhaseUI
│   ├── deckView.js               # Deck editor, SD config, game config, art grid
│   ├── deckList.js               # Deck browser/launcher
│   ├── cardPreview.js            # Full-screen card preview overlay
│   ├── gameOverUI.js             # Victory/defeat screen
│   ├── threatUI.js               # Threat response phase UI
│   └── talkChatUI.js             # LLM chat for Talk action
├── rendering/
│   ├── cardFace.js               # CardFace/CardBack components, config-driven rendering
│   └── d20Dice.js                # SVG D20 component with roll animation
├── services/
│   ├── artPipeline.js            # SD prompt building, art queue, image sequences, SD overrides
│   ├── narrator.js               # LLM narration (round start/end, resolution)
│   ├── director.js               # LLM AI opponent card placement
│   ├── gameChatManager.js        # LLM chat session for Talk cards
│   ├── pokerFace.js              # Emotion/banter system (MoodRing)
│   └── characters.js             # Character generation, deck assembly, template system
├── data/
│   ├── encounters.json           # Threat creatures, scenario definitions
│   ├── character-templates.json  # 12 balanced character archetypes
│   └── action-definitions.json   # External action config (loaded at runtime)
└── cardGame-v2-entry.js          # Module loader / namespace wiring
```

### What Works (Tested & Functional)

| Feature | Status | Notes |
|---------|--------|-------|
| **Card rendering** | ✅ Complete | All 10 card types render with config-driven bodies, corner icons, rarity badges |
| **Deck builder** | ✅ Complete | Theme selection, character generation, SD config overrides, art queue |
| **Art pipeline** | ✅ Complete | Per-type prompt building, batch queue, image sequences, theme-aware styles |
| **Initiative phase** | ✅ Complete | D20 animation, card flip, winner/loser badges, Nat 1 threat warnings |
| **Draw & placement** | ✅ Complete | Virtual action picker, modifier stacking, drag-drop, item auto-select |
| **Combat resolution** | ✅ Complete | ATK vs DEF, 9 outcomes, criticals, dual wield, armor reduction, status effects |
| **Magic resolution** | ✅ Complete | Channel + direct magic with casting animation, potency roll, fizzle check |
| **Non-combat actions** | ✅ Complete | Rest, Guard, Flee, Investigate, Trade, Steal, Craft, Feint, Use Item |
| **Resolution UI** | ✅ Complete | Inline result row aligned with positions, dice animation, hold time, click-expand |
| **Talk action** | ✅ Complete | Player Talk opens LLM chat; opponent Talk uses CHA contest roll |
| **Cleanup phase** | ✅ Complete | Recovery, pot claiming, scenario display, loot reveal, auto-save |
| **Threat system** | ✅ Complete | Nat 1 beginning threats, end-of-round threats, flee, carry-over, pot forfeiture, combat result overlay |
| **AI opponent** | ✅ Complete | LLM director for placement with FIFO fallback; AI auto-fills positions |
| **Narration** | ✅ Complete | LLM round start/end narration, resolution narration, ticker display |
| **Poker Face** | ✅ Complete | Emotion display, banter triggers (attack, defense, magic) |
| **Save/resume** | ✅ Complete | Auto-save at cleanup, rolling 3-save backups, resume from deck list |
| **Campaign** | ✅ Partial | Win/loss tracking per character, XP/leveling defined but not fully wired |
| **Status effects** | ✅ Complete | 11 effects (stunned, poisoned, burning, etc.) with duration and turn-start callbacks |
| **Card hoarding** | ✅ Complete | Exhausted check during resolution, lethargy check during cleanup |
| **5 themes** | ✅ Complete | High-fantasy, dark-medieval, sci-fi, post-apocalypse, steampunk |

### Resolution Phase Flow (Current)

```
advanceResolution() called per position:
  ├── Skip check (stunned, empty stack)
  ├── isAttack? ──────────────→ Combat branch
  │   ├── "rolling" phase (1.5s dice animation, ATK vs DEF)
  │   ├── resolveCombat() → currentCombatResult
  │   ├── "result" phase (2.5s hold — outcome label + damage)
  │   ├── "done" → pos.resolved, pos.combatResult set
  │   ├── Exhausted check, magic-on-attack modifiers, threat loot
  │   └── Auto-advance (1.5s gap)
  │
  ├── isMagicAction? ─────────→ Magic branch (Channel or direct magic)
  │   ├── "rolling" phase (1.2s single die casting animation)
  │   ├── Resolve spell (stat check → fizzle or roll + apply effects)
  │   ├── "result" phase (2.0s hold — spell name + damage/healing)
  │   ├── "done" → pos.resolved, pos.magicResult set
  │   ├── Magic cards return to hand if reusable
  │   └── Auto-advance (1.5s gap)
  │
  └── else ────────────────────→ Non-combat branch
      ├── 1.0s delay
      ├── Execute action (Rest/Guard/Flee/Talk/Trade/Steal/Craft/Feint/UseItem)
      ├── pos.resolved immediately
      └── Auto-advance (2.0s gap)
```

### Action Bar Layout (Current)

```
┌─ Action Bar ──────────────────────────────────────────────┐
│  [Phase Label]  [Turn info]  [End Turn / Ready]           │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐           │
│  │Pos 1 │ │Pos 2 │ │Pos 3 │ │Pos 4 │ │Pos 5 │  ← Track │
│  │ P/O  │ │ P/O  │ │ P/O  │ │ P/O  │ │ P/O  │           │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘           │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐           │
│  │Result│ │Result│ │Result│ │  ·   │ │      │  ← Results │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘  (res only)│
│  ── You: 12 dealt, 3 taken │ Opp: 3 dealt, 12 taken ──  │
└───────────────────────────────────────────────────────────┘
```

Result slots show:
- **Combat:** ATK vs DEF dice → outcome label + damage → click for roll breakdown
- **Magic:** Single die casting → spell name + damage/heal → click for MAG breakdown
- **Non-combat:** Checkmark + action name (or red X if skipped/failed)

### Card Placement Model (Current)

Actions are **not** drawn from hand. The player clicks an empty position to open an icon picker showing available actions. Modifier cards (items, skills, magic) **are** drawn from hand and placed on top of actions.

```
Hand contains: [Sword] [Shield] [Fireball] [Swordsmanship] [Health Potion]
                 item    item     magic       skill           item

Player clicks empty position → action picker shows:
  ⚔️ Attack | 🛡️ Guard | 🏃 Flee | 🔮 Channel | 💤 Rest | ...

Player picks "Attack" → virtual action card created
Player drags [Sword] from hand → stacked as weapon modifier
Player drags [Swordsmanship] → stacked as skill modifier

Position stack: [Attack] + [Sword] + [Swordsmanship]
Roll: 1d20 + STR + Sword ATK + Swordsmanship bonus
```

### File Storage Layout (Current)

```
~/CardGame/
├── {deckName}/
│   ├── deck.json              # Deck snapshot (cards, theme, sdConfig)
│   ├── gameConfig.json        # LLM/voice/poker face settings
│   ├── Art/                   # Generated art (SD pipeline output)
│   │   ├── background-{theme}-{ts}.png
│   │   ├── tabletop-{theme}-{ts}.png
│   │   ├── card-front-{theme}-{ts}.png
│   │   ├── card-back-{theme}-{ts}.png
│   │   └── {cardName}-{ts}.png      # Per-card art
│   ├── saves/
│   │   ├── save-{timestamp1}.json
│   │   ├── save-{timestamp2}.json
│   │   └── save-{timestamp3}.json   # Rolling 3-save max
│   └── campaign.json          # Win/loss record per character
│
└── Characters/                # Generated olio.charPerson records
```

Art is stored under the deck folder (`~/CardGame/{deckName}/Art/`). Deck delete removes the parent group (`~/CardGame/{deckName}`) which should recursively clean up all children. **Note:** If the server does not perform recursive group deletion, art and saves would be orphaned — see [Open Issues](#open-issues).

---

## Open Issues

### Issue 1: Deck Delete — Server Recursive Deletion Unverified
Art is stored under the deck folder (`~/CardGame/{deckName}/Art/`) and deck delete removes the parent group via `page.deleteObject("auth.group", grp.objectId)`. This should recursively delete all children (Art, saves, campaign, gameConfig). **Verify** that the server's group delete actually performs recursive deletion — if not, children (art images, save files) would be orphaned.

~~Chat cleanup:~~ **Resolved.** `deleteDeck()` in `deckList.js` now calls `deleteGameChats(storageName)` before removing the deck group. This finds all `CG Chat/Narrator/Director` chat requests for the deck and deletes each via `am7chat.deleteChat(req, true)`, which also deletes referenced session objects. A manual "Delete All Chats" button was also added to the Game Config panel in `deckView.js`.

### Issue 2: Backend Model List Processing Error
Jackson deserialization fails when processing `SWModelListResponse["files"]`:
```
Cannot deserialize value of type `java.util.ArrayList<java.lang.Object>` from Object value
(token `JsonToken.START_OBJECT`)
at [Source: REDACTED; line: 1, column: 24]
(through reference chain: org.cote.accountmanager.olio.sd.swarm.SWModelListResponse["files"]->java.util.ArrayList[0])
```
The backend expects `files` to be an array of simple objects but the SD Forge/SwarmUI API returns complex nested objects that Jackson cannot auto-deserialize into `ArrayList<Object>`. Fix requires updating `SWModelListResponse.files` to use a proper typed model or `JsonNode` instead of raw `ArrayList<Object>`.

### ~~Issue 3: End-of-Round Threats Need Rework~~ ✅ RESOLVED (2026-02-09)
All Phase 9.5 encounter items implemented: target winner, defense stack, CLASH handling, threat carry-over (+2 ATK), flee (d20+AGI vs DC), pot forfeiture, full combat result overlay with roll breakdowns.

### ~~Issue 4: Scenario/Loot Card Integration Incomplete~~ ✅ RESOLVED (2026-02-09)
All Phase 9.5 scenario/loot items implemented: scenario + loot cards in deck assembly with `_isScenarioDef`/`_isLootDef` markers, `CardFace` rendering, loot reveal overlay, `lootPool` on scenarios with theme overrides, named `lootItems` from creatures, loot art lookup, backfill for older decks, `artPrompt` support in art pipeline.

### ~~Issue 5: Equipment Phase Is Display-Only~~ ✅ Resolved
Interactive EQUIP phase inserted after initiative. Players can click to equip/unequip items across 7 slots (head, body, handL, handR, feet, ring, back). AI auto-equips. ATK/DEF calculated from equipped items. Two-handed weapon support. `EQUIP_SLOT_MAP` constant maps card slot values to equipped keys.

### ~~Issue 6: Campaign Progression Not Fully Wired~~ ✅ Resolved
XP system fully wired: 10 base XP per round + damage dealt + 25 round victory bonus + HP bonus + 50 game victory bonus. Campaign stores xp, level, totalXpEarned, statGains, pendingLevelUps. XP bar shown in player sidebar during game and on game-over screen. Level-up stat picker UI (choose 2 stats per level). Campaign stat gains applied at game start. Threshold: level * 100 XP, max level 10.

### ~~Issue 7: Treasure Vault Not Implemented~~ ✅ Resolved
Vault assembled at game start from theme `treasureVault` config: EPIC/LEGENDARY items from cardPool + boss threats (difficulty 12-16). Pot jackpot (5+ cards) triggers vault draw. Boss draws queued as carried threats for next round. Item draws added to winner's hand. Vault draw reveal shown in cleanup phase with gold shimmer (items) or red pulse (bosses).

### ~~Issue 8: Durability System Not Implemented~~ ✅ Resolved
Weapons lose 1 durability per round when Attack action is used (cleanup phase). Apparel loses 1 durability per hit taken (-2 on critical hit, in combat resolution). Items at 0 durability are destroyed, removed from equipped slots, and added to pot. Durability summary shown in cleanup UI with "DESTROYED → Pot" labels.

### ~~Issue 9: Round Pot Not Fully Implemented~~ ✅ Resolved
Consumed items routed to pot instead of discard. Destroyed equipment (durability 0) added to pot. Pot jackpot trigger: when pot has 5+ cards at claim time, `_jackpotTriggered` flag set with gold animated banner. Jackpot triggers treasure vault draw.

### Issue 10: Multiplayer (IRL) Not Implemented
Online play is 1v1 vs AI only. Design supports 3-4 player IRL with:
- Round-robin initiative
- Directional combat (attack next, defend from previous)
- Per-player encounter draws
- This is an IRL-only feature requiring print support (Phase 10)

---

## Next Steps (Priority Order)

### Near-term (Gameplay Polish)
1. **Verify deck delete cleanup** — confirm server recursive group deletion (chat cleanup implemented)
2. ~~**Equipment phase interactivity**~~ ✅ Interactive equip/unequip with 7 slots, AI auto-equip
3. ~~**End-threat rework**~~ ✅ Phase 9.5 complete (target winner, bonus stack, CLASH, carry-over, flee, pot forfeiture, combat overlay)
4. ~~**Loot card integration**~~ ✅ Phase 9.5 complete (named lootItems, deck assembly, art, lootPool, backfill)
5. ~~**Scenario card integration**~~ ✅ Phase 9.5 complete (deck assembly, CardFace in cleanup, loot reveal, artPrompt)

### Mid-term (Campaign & Balance)
6. ~~**XP & level-up**~~ ✅ XP tracking, level-up stat picker, campaign persistence, XP bar in HUD
7. ~~**Durability system**~~ ✅ Weapon/apparel durability decrement, destroy at 0, route to pot
8. ~~**Pot improvements**~~ ✅ Consumed items to pot, destroyed equipment to pot, jackpot trigger
9. **Balance tuning** — magic energy costs, threat difficulty scaling, status effect stacking
10. ~~**Treasure vault**~~ ✅ Vault deck assembly, jackpot draw trigger, boss/item reveal in cleanup

### Long-term (Export & Expansion)
11. **Print & export (Phase 10)** — PDF generation, PNG export, TTS format, rules reference
12. **Multiplayer rules documentation** — IRL play reference cards for 3-4 player
13. **Custom theme editor** — user-created themes with card pools and art styles
