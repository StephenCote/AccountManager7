# Card Game v2 — Theme Card Pools

Complete card definitions for three launch themes. Each theme provides a full playable deck: starter cards, encounter deck, and loot pool. All card stats are tuned for the 1d20 + modifier system defined in [cardGame-v2.md](cardGame-v2.md).

**Stat modifier note:** All stat references below use the stat value directly as the modifier (1-20 scale). If the core rules adopt `stat/2` scaling, halve all `Requires` thresholds accordingly.

---

## Theme 1: High Fantasy

**Setting:** Classic sword-and-sorcery. Elven forests, dwarven mines, dragon lairs, ancient ruins. Magic is common and respected.
**Art style prompt suffix:** `"high fantasy, vibrant colors, detailed illustration, medieval fantasy, D&D style"`
**Magic level:** High — all three skill types available (Imperial, Undead, Psionic)
**Mortality:** Medium

### Apparel (10 cards)

| # | Name | Slot | Rarity | DEF | HP Bonus | Special | Durability | Requires | Art Prompt |
|---|------|------|--------|-----|----------|---------|------------|----------|------------|
| A1 | Leather Jerkin | Body | ★ | +1 | — | — | 4 | — | "worn leather jerkin, stitched hide, adventurer gear" |
| A2 | Chainmail Hauberk | Body | ★★ | +3 | — | — | 6 | STR 8 | "steel chainmail shirt, interlocking rings, polished" |
| A3 | Plate Cuirass | Body | ★★★ | +5 | +10 | — | 8 | STR 12 | "gleaming plate armor breastplate, ornate engravings" |
| A4 | Mage Robes | Body | ★★ | +1 | — | +2 to Magic rolls | 3 | MAG 10 | "flowing blue mage robes, arcane sigils, silver trim" |
| A5 | Iron Helm | Head | ★ | +1 | — | — | 5 | — | "simple iron helmet, noseguard, dented" |
| A6 | Crown of Insight | Head | ★★★★ | +1 | — | +3 to Investigate rolls | 4 | INT 12 | "silver circlet with glowing sapphire, elven craft" |
| A7 | Leather Boots | Feet | ★ | +1 | — | — | 4 | — | "sturdy leather boots, travel-worn, buckled" |
| A8 | Boots of Swiftness | Feet | ★★★ | +1 | — | +3 to Flee rolls | 5 | AGI 10 | "enchanted boots, faint glow, feathered anklets" |
| A9 | Wooden Shield | Hand | ★ | +2 | — | — | 4 | — | "round wooden shield, iron boss, painted crest" |
| A10 | Cloak of Shadows | Back | ★★★ | +1 | — | +2 to Flee rolls; first attack against you each round has -2 | 4 | AGI 10 | "dark hooded cloak, shifting shadows, clasped brooch" |

**Starter pool (players draw 2-3):** A1, A5, A7, A9

### Weapons (8 cards)

| # | Name | Slot | Rarity | ATK | Range | Type | Special | Durability | Requires | Art Prompt |
|---|------|------|--------|-----|-------|------|---------|------------|----------|------------|
| W1 | Short Sword | Hand | ★ | +3 | Melee | Slashing | — | 6 | — | "short steel sword, leather-wrapped grip" |
| W2 | Iron Longsword | Hand | ★★ | +4 | Melee | Slashing | — | 8 | STR 8 | "iron longsword, crossguard, worn leather grip" |
| W3 | Battle Axe | Hand×2 | ★★ | +6 | Melee | Slashing | -1 to Flee rolls (heavy) | 7 | STR 12 | "double-headed battle axe, oak haft, bloodstained" |
| W4 | Hunting Bow | Hand×2 | ★★ | +4 | Ranged | Piercing | — | 6 | AGI 10 | "yew longbow, quiver of arrows, leather grip" |
| W5 | Staff of Flames | Hand×2 | ★★★ | +3 | Ranged | Magic | +2 to Imperial magic rolls | 5 | MAG 12 | "gnarled wooden staff, crystal tip wreathed in flame" |
| W6 | Dagger | Hand | ★ | +2 | Melee | Piercing | Can be used as stack 2 with 0 extra Energy cost | 5 | — | "steel dagger, slim blade, wrapped handle" |
| W7 | Warhammer | Hand | ★★ | +5 | Melee | Blunt | Ignores 1 DEF (armor-piercing) | 7 | STR 10 | "iron warhammer, short haft, flat striking face" |
| W8 | Elven Blade | Hand | ★★★★ | +5 | Melee | Slashing | +2 ATK vs Undead skill-type enemies | 10 | AGI 12 | "slender elven sword, leaf-shaped blade, mithril edge, glowing runes" |

**Starter pool (players draw 1-2):** W1, W2, W6

### Consumables (10 cards)

| # | Name | Rarity | Effect | Art Prompt |
|---|------|--------|--------|------------|
| C1 | Health Potion | ★ | Restore 30 HP | "red potion in glass flask, glowing liquid, cork stopper" |
| C2 | Energy Elixir | ★ | Restore 25 NRG | "sparkling blue potion, crystal vial, lightning crackle" |
| C3 | Ration | ★ | Restore 30 Hunger | "wrapped bread, dried meat, traveler's ration bundle" |
| C4 | Bandage | ★ | Restore 10 HP. Can be played on another player's stack. | "linen bandage roll, healing herbs tucked in" |
| C5 | Torch | ★ | +3 to Investigate rolls this round. Consumed. | "burning wooden torch, flickering flame, wrapped pitch" |
| C6 | Antidote | ★★ | Remove one negative status effect (poison, curse). | "green potion, small phial, leaf stopper" |
| C7 | Smoke Bomb | ★★ | Auto-succeed one Flee action this round. | "small clay pot, trailing smoke, fuse lit" |
| C8 | Fortifying Stew | ★★ | Restore 50 Hunger and +5 Morale. | "steaming bowl of stew, thick broth, wooden spoon" |
| C9 | Whetstone | ★ | Restore 3 durability to one weapon. | "flat sharpening stone, oil sheen, worn groove" |
| C10 | Scroll of Protection | ★★★ | +5 DEF this round (stacks with armor). Consumed. | "rolled parchment, wax seal, glowing ward sigil" |

**Starter kit:** C1 ×1, C3 ×2, C4 ×1, C5 ×1

### Skills (10 cards)

| # | Name | Category | Modifier | Requires | Art Prompt |
|---|------|----------|----------|----------|------------|
| S1 | Swordsmanship | COMBAT | +2 to Attack with Slashing weapons | STR 8 | "crossed swords emblem, steel on steel, sparks" |
| S2 | Archery | RANGED | +2 to Attack with Ranged weapons | AGI 10 | "arrow in bullseye target, bow silhouette" |
| S3 | Shield Wall | DEFENSE | +2 to Defend when shield equipped | END 8 | "overlapping shields, iron wall formation" |
| S4 | Woodland Lore | SURVIVAL | +2 to Investigate in forest/wilderness encounters | INT 8 | "oak leaf with compass, forest canopy" |
| S5 | Herbalism | CRAFTING | +2 to Craft consumables; crafted potions restore +10 extra | INT 10 | "mortar and pestle, herbs, bubbling flask" |
| S6 | Silver Tongue | SOCIAL | +3 to Talk rolls | CHA 10 | "golden tongue emblem, speech lines radiating" |
| S7 | Shadow Step | STEALTH | +3 to Flee rolls; if Flee succeeds, gain +2 to next round's Attack (ambush) | AGI 12 | "footprint dissolving into shadow, dark mist" |
| S8 | Arcane Study | KNOWLEDGE | +2 to Investigate; can identify Magic Effect cards before encounter reveals them | INT 12 | "open spellbook, glowing text, eye symbol" |
| S9 | Heavy Blow | COMBAT | +3 to Attack with Blunt weapons; on Critical Hit, stun target (they lose next action) | STR 12 | "fist smashing stone, shockwave radiating" |
| S10 | Dual Wield | COMBAT | Can equip two one-handed weapons; add both ATK values to Attack rolls | AGI 12 | "two crossed daggers, blur motion lines" |

**Starter pool (players draw 1-2):** S1, S2, S3, S4, S6

### Magic Effects (8 cards)

| # | Name | Skill Type | Min Stat | Energy | Effect | Reusable | Art Prompt |
|---|------|-----------|----------|--------|--------|----------|------------|
| M1 | Fireball | Imperial | MAG 12 | 20 | Deal 15 fire damage, ignore 2 DEF | Yes | "sphere of flames, explosive burst, orange-red" |
| M2 | Ice Wall | Imperial | MAG 10 | 15 | +5 DEF this round; negate Flee attempts against you | Yes | "wall of jagged ice crystals, blue frost mist" |
| M3 | Healing Light | Imperial | MAG 10 | 15 | Restore 25 HP to self or one ally | Yes | "golden radiance, warm light beams, gentle glow" |
| M4 | Raise Thrall | Undead | INT 14 | 25 | Defeated encounter becomes your ally for 3 rounds | No | "skeletal hand rising from grave, green necro-glow" |
| M5 | Entropy Touch | Undead | INT 12 | 15 | Target's equipped weapon loses 3 durability | Yes | "withering hand, rust spreading, decay particles" |
| M6 | Mind Read | Psionic | INT 12 | 10 | See target's hand (reveal at start of Plan Phase, before stacks built) | Yes | "glowing third eye, transparent skull, thought waves" |
| M7 | Telekinetic Slam | Psionic | INT 14 | 20 | 12 force damage + target cannot attack you next round | Yes | "invisible force wave, shattered stone, pushed figure" |
| M8 | Soul Drain | Undead+Psionic | INT 16 | 30 | Deal 10 damage and restore 10 HP to self | No | "spectral tendrils pulling life essence, dual glow" |

### Threat Encounters (15 cards)

| # | Name | Difficulty | ATK | DEF | HP | Behavior | Loot | Art Prompt |
|---|------|-----------|-----|-----|-----|----------|------|------------|
| T1 | Wolf Pack | 6 | +3 | +2 | 20 | Attacks lowest HP character | Wolf Pelt (apparel mat.), Raw Meat (C-food) | "pack of wolves, snarling, dark forest" |
| T2 | Goblin Raiding Party | 7 | +4 | +1 | 25 | Attacks randomly; flees below 10 HP | Rusty Dagger (W, ATK+1), 3 Gold | "goblins with crude weapons, torchlight, ambush" |
| T3 | Forest Troll | 9 | +5 | +4 | 40 | Regenerates 5 HP per round; weak to fire (double damage from Imperial fire) | Troll Hide (apparel mat.), Troll Blood (alchemy) | "massive green troll, mossy skin, tree-trunk club" |
| T4 | Skeleton Warriors | 7 | +4 | +3 | 30 | Immune to Psionic effects; destroyed by Blunt weapons in 1 fewer hit | Bone Shield (A, DEF+2), Ancient Coin | "armored skeletons, rusted swords, glowing eye sockets" |
| T5 | Giant Spider | 8 | +4 | +2 | 25 | Poison: on hit, target loses 5 HP per round for 2 rounds (Antidote cures) | Spider Silk (crafting), Venom Sac (alchemy) | "giant spider, web-covered lair, fangs dripping venom" |
| T6 | Bandit Ambush | 8 | +5 | +3 | 30 | Steals 1 random item card on hit; Talk can negotiate release | Stolen Goods (random item), Bandit Cloak (A, +1 DEF) | "hooded bandits, crossbow, forest road ambush" |
| T7 | Cave Bear | 9 | +6 | +3 | 35 | Charges: +2 ATK on first attack; flees if HP < 15 | Bear Pelt (apparel mat.), Bear Claw (weapon mat.) | "enormous brown bear rearing up, cave entrance" |
| T8 | Wyvern | 11 | +6 | +5 | 45 | Flying: immune to Melee attacks round 1; lands round 2+ | Wyvern Scale (A, rare armor mat.), Wyvern Fang (W mat.) | "wyvern swooping, leathery wings, venomous tail" |
| T9 | Dark Necromancer | 10 | +4 | +3 | 30 | Summons 1 Skeleton Warrior (T4 stats, 15 HP) each round; kill necromancer to stop | Necromancer Staff (W, ATK+3, +2 Undead), Tome of Shadows | "robed necromancer, skull staff, green energy swirl" |
| T10 | Orc War Party | 10 | +5 | +4 | 40 | Two attacks per round (split damage); intimidation aura: -1 Morale per round | Orcish Blade (W, ATK+5, STR 10), War Trophy | "orc warriors, war paint, crude heavy weapons, banner" |
| T11 | Fire Elemental | 10 | +5 | +2 | 30 | Immune to Imperial fire magic; deals burn: 3 extra damage on hit lasting 2 rounds | Fire Crystal (magic mat.), Ember Core | "living flame humanoid, molten stone, heat shimmer" |
| T12 | Cursed Knight | 11 | +6 | +6 | 35 | Reflects 3 damage back to attacker on each hit; Talk can break the curse (CHA 14) | Knight's Plate (A, DEF+5, ★★★), Cursed Blade (W, ATK+5, -5 Morale) | "spectral knight, tarnished armor, ghostly blue glow" |
| T13 | Young Dragon | 14 | +8 | +6 | 60 | Breath attack: 10 fire damage to all characters every other round; flying round 1 | Dragon Scale (legendary armor mat.), Dragon Hoard (3 random items) | "young dragon, scales gleaming, fire breath, treasure pile" |
| T14 | Basilisk | 9 | +4 | +4 | 30 | Gaze: on hit, target is stunned next round (no actions); avoidable with Shield equipped | Basilisk Eye (magic mat.), Stone-Scaled Hide (A mat.) | "serpentine basilisk, stone gaze, petrified surroundings" |
| T15 | Lich | 14 | +7 | +5 | 50 | Immune to Undead magic; casts Entropy Touch each round (auto, costs no Energy); regenerates 5 HP/round | Lich Phylactery (★★★★★, +3 to all Undead magic), Staff of Undeath | "skeletal lich, crown of bone, swirling death magic, throne" |

### Event Encounters (10 cards)

| # | Name | Effect | Art Prompt |
|---|------|--------|------------|
| E1 | Thunderstorm | -2 to all Ranged attack rolls this round. All torch effects negated. | "lightning striking forest, dark clouds, driving rain" |
| E2 | Fairy Glade | All players restore 10 HP and 10 Morale. Peaceful — no threats this round. | "glowing fairy ring, soft light, mushroom circle, twilight" |
| E3 | Earthquake | All players lose 1 durability on equipped Body armor. Structures collapse. | "cracking ground, falling rocks, dust cloud, shaking trees" |
| E4 | Full Moon | All Undead skill-type magic effects cost 5 less Energy this round. +2 to Undead rolls. | "enormous full moon, silver light, silhouetted castle" |
| E5 | Thick Fog | -3 to all Investigate rolls this round. +2 to all Flee rolls. | "dense white fog, barely visible trees, eerie silence" |
| E6 | Merchant Caravan | All players may Trade without a Trade action card this round (free Trade). | "colorful wagon caravan, horses, lanterns, road" |
| E7 | Campfire Rest | Any player who plays Rest this round restores double values (40 NRG, 20 HP). | "crackling campfire, starry night, bedroll, warmth" |
| E8 | Solar Eclipse | Imperial magic effects cost 10 more Energy this round. Psionic effects cost 5 less. | "solar eclipse, corona ring, unnatural darkness, stars visible" |
| E9 | Landslide | All players must Flee (difficulty 8) or take 15 damage. Ignore if playing Defend. | "boulders tumbling down mountainside, dust cloud, narrow path" |
| E10 | Blessing of the Ancients | One random player gains +2 to all rolls next round. Draw an extra card from encounter deck. | "ancient stone altar glowing, golden runes, divine light beam" |

### Discovery Encounters (10 cards)

| # | Name | Investigate DC | Reward (Success) | Reward (No Investigate) | Art Prompt |
|---|------|---------------|------------------|------------------------|------------|
| D1 | Hidden Cache | 10 | 2 random consumable items + 1 random weapon | 1 random consumable | "stone wall with hidden compartment, old chest, cobwebs" |
| D2 | Abandoned Camp | 8 | 2 Rations + 1 Bandage + 1 random Skill card | 1 Ration | "cold campfire, scattered gear, torn tent, forest clearing" |
| D3 | Crystal Cave | 12 | 1 random Magic Effect card + Fire Crystal (magic mat.) | 1 Torch | "geode cave, glittering crystals, prismatic light" |
| D4 | Old Battlefield | 10 | 1 random Weapon (★★) + 1 random Apparel (★★) | 1 rusty weapon (ATK+1, dur 2) | "old battlefield, rusted armor in grass, broken standards" |
| D5 | Healing Spring | 8 | Restore full HP and cure all status effects | Restore 20 HP | "clear pool, faintly glowing water, mossy stones, ferns" |
| D6 | Ruined Library | 14 | 1 random Skill card + 1 random Magic Effect card | 1 Torch | "collapsed library, scattered books, intact shelf, dust motes" |
| D7 | Mushroom Patch | 6 | 3 Foraged Mushrooms (+15 Hunger each, 10% chance of poison: -10 HP) | 1 Foraged Mushroom | "colorful mushroom cluster, forest floor, dappled light" |
| D8 | Sunken Chest | 12 | 1 random item (★★★ rarity) | Nothing (chest is stuck) | "half-submerged chest in shallow river, glinting lock" |
| D9 | Herb Garden | 8 | 3 Healing Herbs (crafting ingredient for potions) + 1 Antidote | 1 Healing Herb | "overgrown garden, labeled herb rows, stone wall, wildflowers" |
| D10 | Dragon's Shed Scale | 16 | Dragon Scale (legendary armor material — craft into ★★★★★ armor) | Nothing (you don't notice it) | "single iridescent dragon scale half-buried in ash, massive" |

### NPC Encounters (8 cards)

| # | Name | Type | Talk DC | Trade Offers | Special | Art Prompt |
|---|------|------|---------|-------------|---------|------------|
| N1 | Wandering Merchant | Trader | — | Sells: Health Potion (10g), Rations (5g), Bandage (3g), Whetstone (8g) | Prices modified by CHA | "traveling merchant, pack mule, colorful goods, dusty road" |
| N2 | Elven Ranger | Ally | 10 | Offers: Hunting Bow or Archery skill card in exchange for 2 consumables | If Talk succeeds: teaches Woodland Lore (free skill) | "elven ranger, green cloak, bow ready, forest perch" |
| N3 | Dwarven Smith | Crafter | 8 | Repairs all equipment to full durability for 15g. Sells Warhammer (20g). | Can craft ★★★ items if you bring materials | "stocky dwarf, leather apron, glowing forge, anvil, hammer" |
| N4 | Mysterious Hermit | Quest | 12 | Offers: Scroll of Protection for free IF you agree to fight next Threat | Reveals location of nearest ★★★★ item (next Discovery is upgraded to rare) | "hooded figure, cave entrance, strange symbols, mismatched eyes" |
| N5 | Traveling Bard | Social | 6 | Offers: +15 Morale for free (song). Sells: Lute (item, +2 to Talk rolls, no ATK) | If Talk succeeds: tells a rumor (preview next 2 encounter cards) | "bard with lute, colorful outfit, campfire, musical notes" |
| N6 | Wounded Knight | Rescue | 10 | Offers: Knight's Sword (W, ATK+4, ★★) if you give 1 Health Potion or Bandage | If healed: becomes temporary ally for 2 rounds (ATK+4, DEF+4, 20 HP) | "wounded knight against tree, dented armor, broken lance" |
| N7 | Witch of the Woods | Magic | 14 | Sells: 1 random Magic Effect card (25g). Trades: 2 consumables for 1 Antidote. | If Talk succeeds with CHA 12+: teaches one Undead or Imperial spell free | "old woman, gnarled staff, potion bottles, cottage in clearing" |
| N8 | Tax Collector | Hostile | 12 | Demands: 2 random items or 20g. If refused, becomes Threat (ATK+3, DEF+3, 20 HP) | If Talk succeeds: reduced to 1 item or 10g. If Talk crit: waives tax entirely. | "armored tax collector, guards, scrolls, stern expression" |

---

## Theme 2: Sci-Fi

**Setting:** Far-future interstellar. Space stations, alien worlds, cybernetic augmentation, energy weapons. Faster-than-light travel is common but dangerous.
**Art style prompt suffix:** `"sci-fi, neon glow, cyberpunk, futuristic, holographic HUD, hard science fiction"`
**Magic relabeling:** Imperial → **Energy Tech**, Undead → **Nano-Tech**, Psionic → **Psi-Tech**
**Magic level:** Medium — Psi-Tech common, Energy Tech available, Nano-Tech rare and dangerous
**Mortality:** Medium

### Apparel (10 cards)

| # | Name | Slot | Rarity | DEF | HP Bonus | Special | Durability | Requires | Art Prompt |
|---|------|------|--------|-----|----------|---------|------------|----------|------------|
| A1 | Utility Jumpsuit | Body | ★ | +1 | — | — | 4 | — | "grey utility jumpsuit, patches, tool loops, worn" |
| A2 | Kevlar Vest | Body | ★★ | +3 | — | — | 6 | — | "black kevlar vest, tactical webbing, armored panels" |
| A3 | Powered Exosuit | Body | ★★★ | +5 | +10 | +2 STR for melee attacks while worn | 8 | STR 10 | "powered exoskeleton suit, servo joints, HUD visor" |
| A4 | Psi-Amp Bodysuit | Body | ★★ | +1 | — | +2 to Psi-Tech rolls | 3 | INT 10 | "sleek black bodysuit, neural filament traces, subtle glow" |
| A5 | Combat Helmet | Head | ★ | +1 | — | — | 5 | — | "tactical combat helmet, visor, chin strap, scuffed" |
| A6 | Neural Interface Crown | Head | ★★★★ | +1 | — | +3 to Investigate; can interface with terminals | 4 | INT 12 | "thin metallic circlet, holographic display, neural plugs" |
| A7 | Mag-Boots | Feet | ★ | +1 | — | — | 4 | — | "heavy magnetic boots, metal soles, LEDs, industrial" |
| A8 | Grav-Boots | Feet | ★★★ | +1 | — | +3 to Flee rolls; ignore terrain penalties | 5 | AGI 10 | "sleek anti-gravity boots, glowing blue soles, hovering" |
| A9 | Riot Shield | Hand | ★ | +2 | — | — | 5 | — | "transparent ballistic riot shield, handle grip, scuffed" |
| A10 | Stealth Cloak | Back | ★★★ | +1 | — | +3 to Flee; first attack against you each round has -2 | 4 | AGI 10 | "adaptive camouflage cloak, shimmering edges, partly invisible" |

**Starter pool:** A1, A5, A7, A9

### Weapons (8 cards)

| # | Name | Slot | Rarity | ATK | Range | Type | Special | Durability | Requires | Art Prompt |
|---|------|------|--------|-----|-------|------|---------|------------|----------|------------|
| W1 | Combat Knife | Hand | ★ | +2 | Melee | Piercing | 0 extra Energy cost as stack 2 | 5 | — | "tactical combat knife, carbon fiber handle, sharp edge" |
| W2 | Pulse Pistol | Hand | ★ | +3 | Ranged | Energy | — | 6 | — | "compact energy pistol, glowing barrel, grip charge indicator" |
| W3 | Plasma Rifle | Hand×2 | ★★ | +5 | Ranged | Energy | — | 6 | AGI 10 | "long plasma rifle, blue energy coils, scope, stock" |
| W4 | Vibro-Blade | Hand | ★★ | +4 | Melee | Slashing | Ignores 1 DEF (vibration cuts through armor) | 7 | STR 8 | "vibrating mono-edge sword, blur effect, hi-tech hilt" |
| W5 | Ion Cannon | Hand×2 | ★★★ | +6 | Ranged | Energy | -2 to Flee (heavy); on Critical Hit, disables 1 target equipped item for 2 rounds | 5 | STR 12 | "heavy ion cannon, shoulder-mounted, massive barrel, capacitor glow" |
| W6 | Shock Baton | Hand | ★★ | +3 | Melee | Blunt | On hit, target loses 5 NRG (EMP effect) | 7 | — | "electrified baton, arcing sparks, rubber grip, blue glow" |
| W7 | Psi-Blade | Hand | ★★★ | +4 | Melee | Magic | +2 to Psi-Tech rolls; ATK scales with INT instead of STR | 6 | INT 12 | "shimmering psychic blade projecting from bracer, purple energy" |
| W8 | Gauss Sniper | Hand×2 | ★★★★ | +7 | Ranged | Piercing | Ignores 3 DEF; -3 to same-round Defend (slow to reposition) | 5 | AGI 14 | "long electromagnetic sniper rifle, magnetic rails, scope HUD" |

**Starter pool:** W1, W2

### Consumables (10 cards)

| # | Name | Rarity | Effect | Art Prompt |
|---|------|--------|--------|------------|
| C1 | Med-Patch | ★ | Restore 30 HP | "adhesive medical patch, glowing green, biogel layer" |
| C2 | Stim-Pack | ★ | Restore 25 NRG | "injector pen, blue liquid, adrenaline boost label" |
| C3 | Ration Bar | ★ | Restore 30 Hunger | "compressed nutrition bar, foil wrapper, military issue" |
| C4 | Nano-Bandage | ★ | Restore 10 HP. Can be played on another player's stack. | "silver mesh bandage, crawling nanobots, self-sealing" |
| C5 | Flare | ★ | +3 to Investigate this round. Consumed. | "handheld signal flare, bright white-red light, smoke trail" |
| C6 | Anti-Toxin Injector | ★★ | Remove one negative status effect (poison, radiation, EMP). | "auto-injector, yellow liquid, biohazard warning label" |
| C7 | Flash Grenade | ★★ | Auto-succeed one Flee action this round. | "cylindrical flashbang grenade, pin pulled, bright burst" |
| C8 | Protein Synth Meal | ★★ | Restore 50 Hunger and +5 Morale. | "hot synthesized meal tray, steaming, utensils, cafeteria" |
| C9 | Repair Kit | ★ | Restore 3 durability to one weapon or apparel. | "small toolkit, micro-welder, adhesive strips, circuit tape" |
| C10 | Energy Shield Module | ★★★ | +5 DEF this round (stacks with armor). Consumed. | "disc-shaped shield emitter, hexagonal force field, blue glow" |

**Starter kit:** C1 ×1, C3 ×2, C4 ×1, C5 ×1

### Skills (10 cards)

| # | Name | Category | Modifier | Requires | Art Prompt |
|---|------|----------|----------|----------|------------|
| S1 | Close Quarters Combat | COMBAT | +2 to Attack with Melee weapons | STR 8 | "fist and knife crossed, military badge, combat stance" |
| S2 | Marksman | RANGED | +2 to Attack with Ranged weapons | AGI 10 | "crosshair scope reticle, bullet trail, precision" |
| S3 | Tactical Defense | DEFENSE | +2 to Defend; +1 additional if shield equipped | END 8 | "chevron shield emblem, tactical formation diagram" |
| S4 | Scanner Proficiency | SURVIVAL | +2 to Investigate; reveals encounter HP before committing stacks | INT 8 | "handheld scanner device, holographic readout, data streams" |
| S5 | Field Mechanic | CRAFTING | +2 to Craft; repair actions restore +2 extra durability | INT 10 | "wrench and circuit board, sparks, mechanical diagram" |
| S6 | Negotiator | SOCIAL | +3 to Talk rolls | CHA 10 | "handshake hologram, diplomatic seal, golden speech icon" |
| S7 | Ghost Protocol | STEALTH | +3 to Flee rolls; if Flee succeeds, opponent cannot target you next round | AGI 12 | "digital ghost outline, fading footsteps, matrix effect" |
| S8 | Data Mining | KNOWLEDGE | +2 to Investigate; on success, also reveals next encounter card in deck | INT 12 | "holographic data cube, code streams, magnifying glass" |
| S9 | Power Strike | COMBAT | +3 to Attack with Blunt weapons; on Critical Hit, EMP: target loses 10 NRG | STR 12 | "powered fist impact, shockwave, cracked floor, sparks" |
| S10 | Ambidextrous | COMBAT | Can equip two one-handed weapons; add both ATK values | AGI 12 | "dual pistols crossed, motion blur, spent casings" |

**Starter pool:** S1, S2, S3, S4, S6

### Magic Effects — relabeled as Tech Powers (8 cards)

| # | Name | Skill Type | Min Stat | Energy | Effect | Reusable | Art Prompt |
|---|------|-----------|----------|--------|--------|----------|------------|
| M1 | Plasma Burst | Energy Tech | MAG 12 | 20 | Deal 15 energy damage, ignore 2 DEF | Yes | "plasma explosion, blue-white burst, energy ripple" |
| M2 | Force Barrier | Energy Tech | MAG 10 | 15 | +5 DEF this round; negate Flee attempts against you | Yes | "hexagonal force field wall, energy crackling, blue glow" |
| M3 | Med-Drone | Energy Tech | MAG 10 | 15 | Restore 25 HP to self or one ally | Yes | "small hovering medical drone, green beam, healing pulse" |
| M4 | Nano-Swarm Reanimation | Nano-Tech | INT 14 | 25 | Defeated encounter becomes your ally for 3 rounds | No | "swarm of nanobots, reassembling wreckage, silver cloud" |
| M5 | System Corruption | Nano-Tech | INT 12 | 15 | Target's equipped weapon loses 3 durability (nano-corrosion) | Yes | "digital corruption glitch, dissolving metal, code fragments" |
| M6 | Mind Probe | Psi-Tech | INT 12 | 10 | See target's hand (reveal at start of Plan Phase) | Yes | "glowing brain outline, telepathic waves, translucent skull" |
| M7 | Telekinetic Blast | Psi-Tech | INT 14 | 20 | 12 force damage + target cannot attack you next round | Yes | "invisible force shockwave, debris flying, distorted air" |
| M8 | Neural Drain | Nano-Tech+Psi-Tech | INT 16 | 30 | Deal 10 damage and restore 10 HP to self (nanite life-siphon) | No | "neural tendrils connecting two figures, energy transfer" |

### Threat Encounters (15 cards)

| # | Name | Diff | ATK | DEF | HP | Behavior | Loot | Art Prompt |
|---|------|------|-----|-----|-----|----------|------|------------|
| T1 | Feral Dogs | 6 | +3 | +1 | 20 | Attacks lowest HP character | Scrap Leather, Synth-Meat | "mutant dogs, bared teeth, derelict corridor" |
| T2 | Space Pirates | 7 | +4 | +2 | 25 | Steal 1 random item on hit; Talk can negotiate | Pulse Pistol, Stolen Goods | "pirates in mismatched armor, energy weapons, docking bay" |
| T3 | Rogue Security Bot | 9 | +5 | +5 | 35 | Immune to Psi-Tech; weak to EMP (Blunt+electric: double damage) | Servo Parts (craft mat.), Security Badge | "damaged security robot, red eye, sparking joints, corridor" |
| T4 | Xenomorph Scout | 8 | +5 | +2 | 25 | Acid blood: attacker takes 3 damage on melee hit | Acid Gland (craft mat.), Chitin Plate (A mat.) | "alien creature, sleek black carapace, inner jaw, hive walls" |
| T5 | Nano-Plague Carrier | 8 | +3 | +2 | 20 | On hit, target loses 3 durability on random equipped item (nano-corrosion) | Nano Sample (magic mat.), Bio-Data | "shambling figure, silver nanite veins, corroded environment" |
| T6 | Raider Gang | 8 | +4 | +3 | 30 | 2 attacks per round (split damage) | Combat Knife, Kevlar Vest, Stim-Pack | "gang of raiders, neon tattoos, makeshift weapons, alley" |
| T7 | Mech Walker | 11 | +6 | +6 | 50 | Heavy armor; Melee attacks deal only half damage; Ranged full | Mech Core (craft mat.), Heavy Plating (A mat.) | "bipedal combat mech, autocannon arm, searchlight, urban ruin" |
| T8 | Psi-Stalker | 10 | +4 | +3 | 30 | Uses Mind Probe each round (free); immune to Psi-Tech | Psi-Amp Crystal, Neural Crown | "gaunt humanoid, oversized cranium, psychic aura, floating" |
| T9 | Void Horror | 12 | +6 | +4 | 40 | Dimensional: 50% chance to phase through attacks (roll 1d20, miss on 10+) | Void Fragment (★★★★ magic mat.), Phase Cloak mat. | "tentacled horror, dimensional rift, stars visible through body" |
| T10 | Automated Defense Grid | 10 | +5 | +5 | 35 | Turrets: attacks all characters each round (split damage between targets) | Targeting Module (S, +2 Ranged), Power Cell | "ceiling-mounted turrets, laser grid, security corridor, red light" |
| T11 | Toxic Gas Leak | 7 | +2 | — | — | Not a creature. All players take 10 damage per round. Flee (DC 8) or Investigate (DC 10 to seal). No loot on defeat — resolved by sealing. | (none — environmental) | "green gas billowing from ruptured pipe, warning lights, haze" |
| T12 | Corporate Kill Squad | 11 | +6 | +4 | 40 | Coordinated: +1 ATK per round survived (tactical adaptation) | Plasma Rifle, Powered Exosuit Piece, Corp ID Badge | "elite soldiers, matching black armor, laser sights, breaching" |
| T13 | AI Core Defender | 14 | +7 | +6 | 55 | Adapts: immune to whatever damaged it last round (switch damage types) | AI Logic Core (★★★★★), Quantum Processor | "massive robot guardian, multiple arms, energy shield, data center" |
| T14 | Radiation Storm | 9 | +4 | — | — | Environmental. All players take 8 damage per round. Flee (DC 10) or Defend reduces to 3. Lasts 3 rounds then dissipates. | (none) | "crackling radiation storm, green lightning, warped landscape" |
| T15 | Hive Queen | 14 | +7 | +5 | 60 | Spawns 1 Xenomorph Scout (T4 stats, 15 HP) each round; screech: -2 to all rolls | Royal Jelly (consumable: +20 all needs), Hive Crown (A, ★★★★) | "massive alien queen, egg chamber, bioluminescent, armored carapace" |

### Event Encounters (10 cards)

| # | Name | Effect | Art Prompt |
|---|------|--------|------------|
| E1 | Solar Flare | -2 to all Energy Tech rolls this round. All electronic items lose 1 durability. | "sun flare visible through viewport, static, warning klaxon" |
| E2 | Station Shore Leave | All players restore 10 HP and 10 Morale. No threats this round. | "space station lounge, neon lights, drinks, viewport with stars" |
| E3 | Hull Breach | All players must Defend (or take 15 damage from decompression). | "cracked hull, stars visible, alarms, debris floating" |
| E4 | Psi-Storm | All Psi-Tech effects cost 5 less Energy this round. +2 to Psi-Tech rolls. | "swirling psychic energy in space, aurora-like, brain outline" |
| E5 | System Blackout | -3 to all Investigate rolls. Electronic weapons (Energy type) have -2 ATK. | "dark corridor, emergency red lights only, sparks, flashlight beam" |
| E6 | Supply Drop | All players may Trade without a Trade action this round. | "cargo pod dropping from ship, parachute, supply crates, landing zone" |
| E7 | Cryo-Rest Cycle | Any player who plays Rest restores double values. | "cryo-pod chamber, frost, soft blue light, monitoring screens" |
| E8 | Gravity Flux | Melee attacks have +2 ATK (momentum). Ranged attacks have -2 ATK (drift). | "objects floating, gravity warning sign, disoriented figures" |
| E9 | Asteroid Impact | All players Flee (DC 8) or take 15 damage. Defend reduces to 8 damage. | "asteroid slamming into station hull, explosion, shockwave" |
| E10 | AI Blessing | One random player's next roll is auto-success (the ship's AI intervenes). | "holographic AI face, benevolent expression, data streams, helping hand" |

### Discovery Encounters (10 cards)

| # | Name | Investigate DC | Reward (Success) | Reward (No Investigate) | Art Prompt |
|---|------|---------------|------------------|------------------------|------------|
| D1 | Cargo Bay Cache | 10 | 2 random consumables + 1 random weapon | 1 random consumable | "sealed cargo container, manifest screen, warehouse bay" |
| D2 | Abandoned Lab | 8 | 2 Stim-Packs + 1 Nano-Bandage + 1 random Skill | 1 Med-Patch | "wrecked laboratory, broken vials, holographic notes, dark" |
| D3 | Data Vault | 12 | 1 random Tech Power card + AI Logic Shard (craft mat.) | 1 Flare | "secure server room, blinking towers, holographic lock" |
| D4 | Crash Site | 10 | 1 random Weapon (★★) + 1 random Apparel (★★) | 1 scrap weapon (ATK+1, dur 2) | "crashed shuttle wreckage, smoke, scattered equipment, crater" |
| D5 | Med-Bay | 8 | Restore full HP and cure all status effects | Restore 20 HP | "clean medical bay, auto-doc bed, glowing scanner, sterile" |
| D6 | Officer's Quarters | 14 | 1 random Skill + 1 random Tech Power | 1 Ration Bar | "locked officer cabin, personal effects, terminal, star map" |
| D7 | Hydroponics Bay | 6 | 3 Fresh Produce (+15 Hunger each) | 1 Ration Bar | "overgrown hydroponics lab, vines, grow lights, water tanks" |
| D8 | Weapons Locker | 12 | 1 random weapon (★★★ rarity) | Nothing (locked) | "reinforced weapons locker, biometric lock, red warning light" |
| D9 | Pharmacy | 8 | 3 Med-Patches + 1 Anti-Toxin Injector | 1 Med-Patch | "pharmacy shelves, labeled medicine, dispensing terminal" |
| D10 | Prototype Vault | 16 | 1 weapon or armor (★★★★★ rarity, unique) | Nothing | "high-security vault, energy field door, single pedestal, spotlight" |

### NPC Encounters (8 cards)

| # | Name | Type | Talk DC | Trade Offers | Special | Art Prompt |
|---|------|------|---------|-------------|---------|------------|
| N1 | Station Vendor | Trader | — | Sells: Med-Patch (10cr), Ration Bar (5cr), Repair Kit (8cr), Stim-Pack (12cr) | Prices modified by CHA | "vendor kiosk, holographic menu, credit scanner, goods display" |
| N2 | Ex-Military Sniper | Ally | 10 | Offers: Plasma Rifle or Marksman skill for 2 consumables | Teaches Ghost Protocol if Talk crit | "scarred veteran, long rifle, military patches, thousand-yard stare" |
| N3 | Station Engineer | Crafter | 8 | Repairs all equipment to full durability for 15cr. Sells Repair Kit (8cr). | Can upgrade ★★ items to ★★★ with materials | "engineer, welding visor, tools, grease-stained coveralls, workshop" |
| N4 | Mysterious AI Terminal | Quest | 12 | Offers: Free Energy Shield Module if you fight next Threat | Reveals next 2 encounter cards | "glowing terminal, face-like display, cryptic text, dark room" |
| N5 | Ship's Entertainer | Social | 6 | +15 Morale for free (performance). Sells: Holo-Projector (+2 Talk, no ATK) | Rumors: preview next 2 encounter cards | "performer, holographic stage, music notes, captive audience" |
| N6 | Wounded Pilot | Rescue | 10 | Gives: Nav Data (free auto-Flee next round) if healed with Med-Patch or Nano-Bandage | Becomes temporary ally for 2 rounds (Ranged ATK+3, 20 HP) | "injured pilot against wall, flight suit, blood, cockpit debris" |
| N7 | Black Market Dealer | Magic | 14 | Sells: 1 random Tech Power (25cr). Trades: 2 consumables for 1 Anti-Toxin. | Teaches one Nano-Tech power if Talk crit with INT 14+ | "hooded figure, back alley, glowing illegal wares, surveillance" |
| N8 | Corporate Inspector | Hostile | 12 | Demands: 2 items or 20cr for "licensing fees." Refuse = Threat (ATK+4, DEF+3, 25 HP) | Talk success: reduced to 1 item. Talk crit: waived. | "suited inspector, bodyguard, clipboard, stern, corporate logo" |

---

## Theme 3: Post Apocalyptic

**Setting:** Decades after civilization collapsed. Irradiated wastelands, scavenger settlements, mutant wildlife, rusted ruins. Technology is salvaged and unreliable. The strong survive.
**Art style prompt suffix:** `"post apocalyptic, wasteland, rusted metal, dust and ash, gritty realism, Mad Max meets Fallout"`
**Magic relabeling:** Imperial → **Ancient Tech** (rare pre-collapse artifacts), Undead → **Mutations** (radiation-induced abilities), Psionic → **Evolved** (rare post-human psi powers)
**Magic level:** Low-Medium — Mutations most common, Ancient Tech rare and powerful, Evolved very rare
**Mortality:** High

### Apparel (10 cards)

| # | Name | Slot | Rarity | DEF | HP Bonus | Special | Durability | Requires | Art Prompt |
|---|------|------|--------|-----|----------|---------|------------|----------|------------|
| A1 | Scrap Vest | Body | ★ | +1 | — | — | 3 | — | "vest made from license plates and road signs, wire stitching" |
| A2 | Leather Duster | Body | ★★ | +2 | — | +1 to Flee rolls (flowing coat) | 5 | — | "long leather coat, patches, bullet holes, dust-worn" |
| A3 | Salvaged Riot Armor | Body | ★★★ | +4 | +10 | — | 6 | STR 10 | "police riot armor, cracked visor, spray-painted skull" |
| A4 | Rad-Suit | Body | ★★ | +1 | — | Immune to radiation damage; +2 to Mutation rolls | 4 | — | "yellow hazmat suit, patched tears, fogged visor, Geiger counter" |
| A5 | Motorcycle Helmet | Head | ★ | +1 | — | — | 4 | — | "cracked motorcycle helmet, scratched visor, faded decals" |
| A6 | Scavenger Goggles | Head | ★★ | +1 | — | +2 to Investigate rolls | 3 | — | "welding goggles, leather strap, tinted lenses, duct tape" |
| A7 | Worn Boots | Feet | ★ | +1 | — | — | 3 | — | "military surplus boots, worn treads, sand-caked, laced" |
| A8 | Sprint Treads | Feet | ★★★ | +1 | — | +3 to Flee rolls | 4 | AGI 10 | "modified running shoes with metal spring inserts, lightweight" |
| A9 | Car Door Shield | Hand | ★ | +2 | — | Heavy: -1 to Flee rolls | 5 | STR 8 | "car door ripped off, handle grip welded on, bullet dents" |
| A10 | Gas Mask | Head | ★★ | — | — | Immune to gas/toxin damage; +1 DEF vs environmental encounters | 3 | — | "military gas mask, cracked lens, filter canisters, straps" |

**Starter pool:** A1, A5, A7, A9

### Weapons (8 cards)

| # | Name | Slot | Rarity | ATK | Range | Type | Special | Durability | Requires | Art Prompt |
|---|------|------|--------|-----|-------|------|---------|------------|----------|------------|
| W1 | Shiv | Hand | ★ | +2 | Melee | Piercing | 0 extra Energy cost as stack 2 | 4 | — | "sharpened metal shiv, tape-wrapped handle, crude but deadly" |
| W2 | Pipe Wrench | Hand | ★ | +3 | Melee | Blunt | Can also be used to Craft (+1 Craft bonus) | 6 | — | "heavy pipe wrench, rust, bloodstains, solid iron" |
| W3 | Scrap Axe | Hand | ★★ | +4 | Melee | Slashing | — | 5 | STR 8 | "axe head welded to rebar, wire-bound grip, jagged edge" |
| W4 | Hunting Rifle | Hand×2 | ★★ | +5 | Ranged | Piercing | Limited ammo: 6 uses total, then discarded (ammo cards restore uses) | 8 | AGI 10 | "bolt-action rifle, wooden stock, duct-taped scope, worn" |
| W5 | Molotov Cocktail | Hand | ★ | +3 | Ranged | Fire | Consumable weapon: single use, deal 3 burn/round for 2 rounds | 1 | — | "glass bottle with rag fuse, amber liquid, lit flame" |
| W6 | Spiked Bat | Hand | ★★ | +4 | Melee | Blunt | On hit, 2 extra piercing damage (nails) | 5 | STR 8 | "wooden baseball bat studded with nails, wrapped handle, blood" |
| W7 | Pre-War Pistol | Hand | ★★★ | +4 | Ranged | Piercing | Limited ammo: 8 uses | 7 | — | "preserved semi-auto pistol, holster, scarce ammunition box" |
| W8 | Chainsaw | Hand×2 | ★★★★ | +7 | Melee | Slashing | -2 to Flee (heavy/loud); on kill, +5 Morale (terrifying) | 4 | STR 12 | "gas-powered chainsaw, sputtering, rusted chain, fuel splatter" |

**Starter pool:** W1, W2

### Consumables (10 cards)

| # | Name | Rarity | Effect | Art Prompt |
|---|------|--------|--------|------------|
| C1 | Dirty Bandage | ★ | Restore 20 HP. 20% chance of infection: lose 5 HP next round. | "torn cloth bandage, stained, barely clean, first-aid tin" |
| C2 | Purified Water | ★ | Restore 25 NRG | "sealed water bottle, handwritten 'PURE' label, precious" |
| C3 | Canned Food | ★ | Restore 30 Hunger | "dented tin can, faded label, pull-tab, old but sealed" |
| C4 | Scrap Bandage | ★ | Restore 10 HP. Can be played on another player's stack. | "strip of fabric torn from shirt, makeshift, clean-ish" |
| C5 | Road Flare | ★ | +3 to Investigate this round. Consumed. | "burning road flare, red light, smoke, night visibility" |
| C6 | Rad-Away | ★★ | Remove one radiation/mutation negative effect. | "IV bag of chelation fluid, needle, medical cross, yellowed" |
| C7 | Smoke Grenade | ★★ | Auto-succeed one Flee action this round. | "military smoke grenade, pin pulled, billowing grey smoke" |
| C8 | Cooked Rat | ★ | Restore 40 Hunger and +3 Morale (it's warm at least). | "rat on a stick over campfire, charred, surprisingly appetizing" |
| C9 | Duct Tape | ★ | Restore 2 durability to one weapon or apparel. | "silver duct tape roll, universal repair, wasteland essential" |
| C10 | Pre-War Med-Kit | ★★★ | Restore 40 HP, cure all status effects. Rare and precious. | "pristine red first-aid kit, white cross, sealed, pre-collapse" |

**Starter kit:** C1 ×1, C3 ×2, C4 ×1, C5 ×1

### Skills (10 cards)

| # | Name | Category | Modifier | Requires | Art Prompt |
|---|------|----------|----------|----------|------------|
| S1 | Brawler | COMBAT | +2 to Attack with Melee weapons; can attack unarmed at ATK +1 | STR 8 | "scarred knuckles, clenched fist, blood, arena cage" |
| S2 | Dead Eye | RANGED | +2 to Attack with Ranged weapons; +1 ammo use per ranged weapon | AGI 10 | "bullet casing and crosshair, wasteland sunset, squinting eye" |
| S3 | Turtle Up | DEFENSE | +2 to Defend; if Defend succeeds, restore 5 HP (adrenaline) | END 8 | "hunched figure behind cover, sparks deflecting, gritted teeth" |
| S4 | Scavenger | SURVIVAL | +3 to Investigate; Discovery encounters give +1 extra item | INT 8 | "figure picking through rubble, flashlight, loaded backpack" |
| S5 | Jury Rigger | CRAFTING | +2 to Craft; weapons you craft have +1 durability | INT 10 | "hands assembling weapon from junk, wire, sparks, workbench" |
| S6 | Intimidation | SOCIAL | +3 to Talk rolls when threatening; hostile NPCs have -1 ATK if Talk succeeds | CHA 10 | "snarling face, raised fist, intimidating posture, fire behind" |
| S7 | Rat Runner | STEALTH | +3 to Flee; if Flee succeeds, steal 1 random item from the encounter's loot | AGI 12 | "figure sprinting through rubble alley, something clutched in hand" |
| S8 | Wasteland Lore | KNOWLEDGE | +2 to Investigate; identify traps before triggering (+2 DEF vs environmental events) | INT 10 | "hand-drawn map, annotations, radiation symbols, compass" |
| S9 | Berserker Rage | COMBAT | +4 to Attack with any melee weapon; but -2 to Defend this round | STR 12 | "screaming warrior, veins bulging, wild swing, dust explosion" |
| S10 | Dual Wield | COMBAT | Can equip two one-handed weapons; add both ATK values | AGI 12 | "two mismatched weapons crossed, blurred motion, shell casings" |

**Starter pool:** S1, S2, S3, S4, S6

### Magic Effects — relabeled as Wasteland Powers (8 cards)

| # | Name | Skill Type | Min Stat | Energy | Effect | Reusable | Art Prompt |
|---|------|-----------|----------|--------|--------|----------|------------|
| M1 | Ancient Laser | Ancient Tech | MAG 12 | 25 | Deal 18 damage, ignore 3 DEF. Weapon has 3 charges total then depleted. | No (3 charges) | "pre-war energy weapon firing, pristine tech, blinding beam" |
| M2 | Force Wall | Ancient Tech | MAG 10 | 15 | +6 DEF this round; negate Flee attempts against you | Yes | "shimmering barrier from buried tech artifact, hexagonal grid" |
| M3 | Regeneration | Mutation | INT 10 | 15 | Restore 20 HP to self. If Hunger > 50, restore 30 instead. | Yes | "wounds visibly closing, green cellular glow, mutant healing" |
| M4 | Rad-Thrall | Mutation | INT 14 | 25 | Defeated creature becomes irradiated servant for 3 rounds | No | "shambling irradiated figure, glowing veins, obedient stance" |
| M5 | Corrosive Touch | Mutation | INT 12 | 15 | Target's equipped weapon or armor loses 4 durability (acid mutation) | Yes | "hand dripping acid, melting metal, smoking corrosion" |
| M6 | Danger Sense | Evolved | INT 12 | 10 | See target's hand; additionally, +2 to Defend this round (precognition) | Yes | "glowing third eye, branching probability lines, alert stance" |
| M7 | Telekinetic Throw | Evolved | INT 14 | 20 | Deal 12 force damage using debris + target cannot attack next round | Yes | "rubble flying telekinetically, car doors as projectiles, focused mind" |
| M8 | Life Leech | Mutation+Evolved | INT 16 | 30 | Deal 12 damage and restore 12 HP (mutation drains life force) | No | "parasitic energy tendrils between two figures, sickly green glow" |

### Threat Encounters (15 cards)

| # | Name | Diff | ATK | DEF | HP | Behavior | Loot | Art Prompt |
|---|------|------|-----|-----|-----|----------|------|------------|
| T1 | Rad-Rats | 5 | +2 | +1 | 15 | Attacks lowest HP; on hit, 10% radiation sickness (lose 3 HP/round for 3 rounds) | Rat Meat ×2, Rat Hide (craft mat.) | "swarm of oversized rats, glowing eyes, rubble, sewer" |
| T2 | Scavenger Gang | 7 | +4 | +2 | 25 | Steals 1 random consumable on hit; Talk can negotiate | Scrap Axe, Canned Food ×2, random junk | "ragged scavengers, crude weapons, face paint, ruined street" |
| T3 | Mutant Boar | 8 | +5 | +3 | 30 | Charge: +3 ATK first round, +0 after. Flees below 10 HP. | Boar Meat (consumable: +40 Hunger), Boar Tusk (W mat.) | "massive mutant boar, extra tusks, patchy fur, glowing eyes" |
| T4 | Feral Ghouls | 7 | +3 | +1 | 20 | On hit, radiation exposure: -5 max HP until Rad-Away used | Ragged Clothes (A, +1 DEF), Pre-War Coins | "shambling irradiated corpses, peeling skin, lurching, ruins" |
| T5 | Giant Scorpion | 9 | +5 | +4 | 30 | Poison: on hit, -10 NRG per round for 2 rounds. Antidote cures. | Scorpion Venom (craft mat.), Chitin Plate (A mat.) | "massive scorpion, pincers raised, stinger poised, desert sand" |
| T6 | Raider War Party | 9 | +5 | +3 | 35 | 2 attacks per round (split); has 1 Ranged attacker (ignores melee-only DEF bonuses) | Hunting Rifle, Leather Duster, Stim-Pack | "war party in vehicles, spikes, mohawks, flame paint, road" |
| T7 | Irradiated Bear | 10 | +6 | +3 | 40 | Radiation aura: all attackers take 3 damage per melee hit (proximity radiation) | Bear Pelt (★★★ A mat.), Glowing Marrow (magic mat.) | "enormous bear, patchy glowing fur, exposed bone, radioactive" |
| T8 | Mine Field | 7 | +4 | — | — | Environmental. Each round, random player rolls 1d20: on 5 or less, take 20 damage. Investigate DC 10 to map and disarm. Lasts until disarmed or all players Flee. | Explosive Parts (craft mat.) ×2 | "cracked road, half-buried mines, danger signs, bleached bones" |
| T9 | Cult of the Atom | 10 | +4 | +3 | 30 | Leader casts Regeneration each round (restores 10 HP). Kill leader to stop. 2 cultists (15 HP each) + 1 leader (30 HP). | Rad-Suit, Ancient Artifact (magic mat.), Holy Text | "robed cultists, radiation symbol tattoos, glowing idol, chanting" |
| T10 | Deathclaw | 14 | +8 | +5 | 55 | 2 attacks per round; ignores 2 DEF (razor claws); -2 Morale/round (terror) | Deathclaw Hide (★★★★ A mat.), Deathclaw Claw (W, ATK+6) | "towering reptilian predator, massive claws, armored scales" |
| T11 | Toxic Gas Pocket | 6 | +2 | — | — | Environmental. All players take 8 damage/round. Gas Mask negates. Flee DC 6 or Investigate DC 8 to find safe route. 2 rounds duration. | (none) | "sickly yellow-green gas cloud, dead vegetation, corroded metal" |
| T12 | Raider Warlord | 12 | +6 | +5 | 45 | Armored vehicle: immune to Melee round 1 (in vehicle); dismounts round 2. Intimidation aura: -1 to all player rolls. | Salvaged Riot Armor, Pre-War Pistol, War Trophy (★★★) | "warlord in armored truck, skull hood ornament, chain weapons" |
| T13 | Rogue Robot | 11 | +6 | +6 | 40 | Immune to Mutation and Evolved powers; EMP vulnerability (Blunt+electric double dmg) | Robot Parts (craft mat.) ×3, Targeting Module | "pre-war military robot, damaged but functional, red targeting laser" |
| T14 | Rad Storm | 9 | +5 | — | — | Environmental. 10 radiation damage/round for 3 rounds. Rad-Suit halves it. Shelter (Defend action) reduces by 5. Cannot be fled from. | Irradiated Crystals (magic mat.) | "swirling green storm, lightning, irradiated rain, apocalyptic sky" |
| T15 | The Behemoth | 16 | +9 | +6 | 70 | Mega-mutant. Destroys cover: Defend provides only half bonus. Earthquake stomp every other round: all take 8 damage. | Behemoth Heart (★★★★★ magic mat.), Massive Bone (W, ATK+7) | "building-sized mutant, club from streetlight, cars as armor" |

### Event Encounters (10 cards)

| # | Name | Effect | Art Prompt |
|---|------|--------|------------|
| E1 | Dust Storm | -2 to all Ranged attacks. -2 to Investigate. +2 to Flee (cover). | "wall of brown dust, zero visibility, howling wind, debris" |
| E2 | Oasis Camp | All players restore 10 HP, 10 NRG, 10 Morale. Peaceful round. | "hidden oasis, clean water spring, shade trees, camp setup" |
| E3 | Aftershock | All equipped Body armor loses 1 durability. Structures collapse. | "ground cracking, rubble falling, dust cloud, tilting building" |
| E4 | Radiation Spike | All Mutation powers cost 5 less Energy. +2 to Mutation rolls. Unprotected players take 5 radiation damage. | "green aurora in sky, Geiger counter maxing out, eerie glow" |
| E5 | Night Fall | -3 to Investigate (dark). +2 to Stealth/Flee rolls. Torch/Flare negates Investigate penalty. | "wasteland at night, stars, campfire in distance, shadows" |
| E6 | Traveling Caravan | Free Trade action for all players this round. | "armored caravan, brahmin-like beasts of burden, armed guards" |
| E7 | Shelter Found | Rest action restores double this round. All players restore 10 Hunger (scavenged). | "intact basement shelter, supplies, cots, generator humming" |
| E8 | Clear Day | +2 to all Ranged attacks (visibility). +1 to Morale (hope). | "clear blue wasteland sky, sun, distant intact building, flowers" |
| E9 | Flash Flood | All players Flee (DC 7) or take 12 damage and lose 1 random consumable (swept away). | "brown water rushing through dry riverbed, debris, surprise" |
| E10 | Airdrop | Random supply drop: each player gains 1 random consumable. 50% chance a Threat encounter is also drawn (attracted by the drop). | "parachute crate falling, smoke signal, scramble, contested" |

### Discovery Encounters (10 cards)

| # | Name | Investigate DC | Reward (Success) | Reward (No Investigate) | Art Prompt |
|---|------|---------------|------------------|------------------------|------------|
| D1 | Buried Bunker | 10 | 2 Pre-War consumables + 1 random weapon (★★) | 1 Canned Food | "concrete bunker hatch, overgrown, partially excavated" |
| D2 | Wrecked Convoy | 8 | 2 Canned Food + 1 Duct Tape + 1 random Skill | 1 Canned Food | "overturned military trucks, bullet holes, scattered crates" |
| D3 | Glowing Cave | 12 | 1 Mutation power card + Irradiated Crystal (magic mat.) | 5 radiation damage (curiosity) | "cave entrance with green glow, crystals, radiation warning" |
| D4 | Rusted Armory | 10 | 1 random weapon (★★) + 1 random apparel (★★) | 1 Shiv | "collapsed military armory, gun racks, ammo crates, dust" |
| D5 | Clean Water Source | 8 | 3 Purified Water + restore 20 HP (clean water is medicine) | 1 Purified Water | "underground spring, clear water, hidden from contamination" |
| D6 | Pre-War Library | 14 | 1 random Skill card + 1 random Wasteland Power | 1 Road Flare | "crumbling library, fallen shelves, intact books in vault" |
| D7 | Wild Garden | 6 | 3 Edible Plants (+15 Hunger each, safe) | 1 Edible Plant | "garden growing from cracked asphalt, mutated but edible" |
| D8 | Sealed Vault | 14 | 1 random item (★★★ rarity) | Nothing (sealed tight) | "massive vault door, number plate, terminal, untouched" |
| D9 | Pharmacy Ruins | 8 | 2 Dirty Bandages + 1 Rad-Away + 1 Pre-War Med-Kit | 1 Dirty Bandage | "collapsed pharmacy, shelves tipped, medicine scattered" |
| D10 | Military Black Site | 18 | Ancient Tech weapon (★★★★★, unique, 5 charges) | Nothing (looks like rubble) | "hidden underground military facility, blast doors, pristine tech" |

### NPC Encounters (8 cards)

| # | Name | Type | Talk DC | Trade Offers | Special | Art Prompt |
|---|------|------|---------|-------------|---------|------------|
| N1 | Wasteland Trader | Trader | — | Sells: Dirty Bandage (5 scrap), Canned Food (3 scrap), Duct Tape (6 scrap), Ammo (8 scrap) | Prices modified by CHA | "grizzled trader, pack brahmin, junk cart, barter goods" |
| N2 | Settlement Militia | Ally | 10 | Offers: Hunting Rifle or Dead Eye skill for 2 consumables | If Talk succeeds: safe passage (skip next Threat) | "armed settlers, sandbag wall, watchtower, suspicious but fair" |
| N3 | Junkyard Mechanic | Crafter | 8 | Repairs all equipment to full for 10 scrap. Sells: Pipe Wrench (5 scrap). | Upgrades ★★ weapons to ★★★ with materials | "mechanic under car hood, welding mask, tool belt, junkyard" |
| N4 | Wandering Prophet | Quest | 12 | Offers: Rad-Away free if you fight next Threat | Reveals next 2 encounter cards; +10 Morale (hope) | "robed wanderer, radiation-scarred, calm eyes, walking staff" |
| N5 | Campfire Storyteller | Social | 6 | +15 Morale (tales of the old world). Sells: Pre-War Map (+2 Investigate, no ATK) | Preview next 2 encounters | "old person at campfire, listeners, stars, oral tradition" |
| N6 | Injured Scavenger | Rescue | 8 | Gives: Map to nearest Discovery (next Discovery reward is doubled) if healed | Becomes ally for 2 rounds (ATK+3, 15 HP) | "wounded scavenger, makeshift splint, grateful expression" |
| N7 | Mutant Shaman | Magic | 14 | Sells: 1 random Mutation power (20 scrap). Trades: 2 consumables for 1 Rad-Away. | Teaches Mutation power free if Talk crit (INT 12+) | "mutated figure, third arm, glowing eyes, herbs, ritual circle" |
| N8 | Toll Road Boss | Hostile | 12 | Demands: 3 items or 25 scrap for passage. Refuse = Threat (ATK+5, DEF+3, 30 HP) | Talk success: 1 item. Talk crit: free passage + 1 free item. | "armored figure at barricade, thugs, chain toll gate, menacing" |

---

## Theme Comparison Summary

| Aspect | High Fantasy | Sci-Fi | Post Apocalyptic |
|--------|-------------|--------|-----------------|
| Max single-hit damage (non-magic) | W3 Battle Axe: ATK+6 + STR | W8 Gauss Sniper: ATK+7 + AGI | W8 Chainsaw: ATK+7 + STR |
| Max magic single-hit | M1 Fireball: 15 | M1 Plasma Burst: 15 | M1 Ancient Laser: 18 |
| Toughest starter threat | T1 Wolf Pack (20 HP) | T1 Feral Dogs (20 HP) | T1 Rad-Rats (15 HP) |
| Toughest boss threat | T15 Lich (50 HP) | T13 AI Core Defender (55 HP) | T15 Behemoth (70 HP) |
| Boss difficulty | 14 | 14 | 16 |
| Healing potion HP | 30 (C1) | 30 (C1 Med-Patch) | 20 (C1 Dirty Bandage, with risk) |
| Food supply quality | Reliable (Rations +30) | Reliable (Ration Bar +30) | Unreliable (Canned Food +30, Cooked Rat +40) |
| Environmental hazards | 2 (E3, E9) | 3 (E3, E9, E1-electronics) | 5 (E1, E3, E4-rad, E9, every Rad encounter) |
| Unique mechanic | Undead immunity/weakness cycles | Adapt-immune boss, EMP vulnerability | Radiation sickness, ammo scarcity, dirty healing |
| Currency | Gold (g) | Credits (cr) | Scrap |

---

## Starter Deck Templates

### Per Theme — What Each Player Gets

| Component | High Fantasy | Sci-Fi | Post Apocalyptic |
|-----------|-------------|--------|-----------------|
| Character | 1 (from AM7) | 1 (from AM7) | 1 (from AM7) |
| Apparel | A1 + A5 + A7 (body+head+feet) | A1 + A5 + A7 (body+head+feet) | A1 + A5 + A7 (body+head+feet) |
| Weapon | W1 Short Sword | W2 Pulse Pistol | W2 Pipe Wrench |
| Consumables | C1×1, C3×2, C4×1, C5×1 | C1×1, C3×2, C4×1, C5×1 | C1×1, C3×2, C4×1, C5×1 |
| Actions | 8 (full set) | 8 (full set) | 8 (full set) |
| Talk | 1 | 1 | 1 |
| Skills | S1 (Combat) + S4 (Survival) | S1 (Combat) + S4 (Survival) | S1 (Brawler) + S4 (Scavenger) |
| Magic | — (unless MAG≥10) | — (unless INT≥12) | — (unless INT≥10) |
| **Total** | **~18 cards** | **~18 cards** | **~18 cards** |

---

## AI Config References Per Theme

Each theme ships with a full set of AI configs stored under `Ux7/media/cardGame/`. These are stored as normal AM7 objects (not snapshotted) and referenced by name in the theme config. See [cardGame-v2.md — AI Config Storage](cardGame-v2.md#ai-config-storage-ux7mediacardgame) for the full naming convention and storage architecture.

### Config File Manifest

**High Fantasy** (`high-fantasy.*`):
```
media/cardGame/sd/high-fantasy.cardBack.sdConfig.json
media/cardGame/sd/high-fantasy.cardStyle.sdConfig.json
media/cardGame/sd/high-fantasy.charPerson.sdConfig.json
media/cardGame/sd/high-fantasy.animal.sdConfig.json
media/cardGame/sd/high-fantasy.item.sdConfig.json
media/cardGame/sd/high-fantasy.deckAssets.sdConfig.json
media/cardGame/sd/high-fantasy.afterAction.sdConfig.json
media/cardGame/prompts/high-fantasy.narrator.promptConfig.json
media/cardGame/prompts/high-fantasy.cardStyleComposer.promptConfig.json
media/cardGame/prompts/high-fantasy.combatEval.promptConfig.json
media/cardGame/prompts/high-fantasy.interactionEval.promptConfig.json
media/cardGame/prompts/high-fantasy.aiOpponent.promptConfig.json
media/cardGame/prompts/high-fantasy.gmEncounter.promptConfig.json
media/cardGame/chat/high-fantasy.narrator.chatConfig.json
media/cardGame/chat/high-fantasy.playerChat.chatConfig.json
media/cardGame/chat/high-fantasy.cardStyleComposer.chatConfig.json
```

**Sci-Fi** (`sci-fi.*`):
```
media/cardGame/sd/sci-fi.cardBack.sdConfig.json
media/cardGame/sd/sci-fi.cardStyle.sdConfig.json
media/cardGame/sd/sci-fi.charPerson.sdConfig.json
media/cardGame/sd/sci-fi.animal.sdConfig.json
media/cardGame/sd/sci-fi.item.sdConfig.json
media/cardGame/sd/sci-fi.deckAssets.sdConfig.json
media/cardGame/sd/sci-fi.afterAction.sdConfig.json
media/cardGame/prompts/sci-fi.narrator.promptConfig.json
media/cardGame/prompts/sci-fi.cardStyleComposer.promptConfig.json
media/cardGame/prompts/sci-fi.combatEval.promptConfig.json
media/cardGame/prompts/sci-fi.interactionEval.promptConfig.json
media/cardGame/prompts/sci-fi.aiOpponent.promptConfig.json
media/cardGame/prompts/sci-fi.gmEncounter.promptConfig.json
media/cardGame/chat/sci-fi.narrator.chatConfig.json
media/cardGame/chat/sci-fi.playerChat.chatConfig.json
media/cardGame/chat/sci-fi.cardStyleComposer.chatConfig.json
```

**Post Apocalyptic** (`post-apoc.*`):
```
media/cardGame/sd/post-apoc.cardBack.sdConfig.json
media/cardGame/sd/post-apoc.cardStyle.sdConfig.json
media/cardGame/sd/post-apoc.charPerson.sdConfig.json
media/cardGame/sd/post-apoc.animal.sdConfig.json
media/cardGame/sd/post-apoc.item.sdConfig.json
media/cardGame/sd/post-apoc.deckAssets.sdConfig.json
media/cardGame/sd/post-apoc.afterAction.sdConfig.json
media/cardGame/prompts/post-apoc.narrator.promptConfig.json
media/cardGame/prompts/post-apoc.cardStyleComposer.promptConfig.json
media/cardGame/prompts/post-apoc.combatEval.promptConfig.json
media/cardGame/prompts/post-apoc.interactionEval.promptConfig.json
media/cardGame/prompts/post-apoc.aiOpponent.promptConfig.json
media/cardGame/prompts/post-apoc.gmEncounter.promptConfig.json
media/cardGame/chat/post-apoc.narrator.chatConfig.json
media/cardGame/chat/post-apoc.playerChat.chatConfig.json
media/cardGame/chat/post-apoc.cardStyleComposer.chatConfig.json
```

### Theme-Specific Config Notes

| Theme | SD Art Style Suffix | Narrator Default | Chat Model Notes |
|-------|-------------------|-----------------|-----------------|
| High Fantasy | `"high fantasy, vibrant colors, painterly, magical atmosphere"` | Bard (poetic, theatrical) | Higher temperature for creative chat |
| Sci-Fi | `"science fiction, clean lines, neon accents, digital art, futuristic"` | Ship AI / Computer (clinical, precise) | Lower temperature for technical dialogue |
| Post Apocalyptic | `"post-apocalyptic, desaturated, gritty, harsh lighting, wasteland"` | War Correspondent (terse, urgent) | Mid temperature, survival-focused prompts |
