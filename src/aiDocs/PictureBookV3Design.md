# PictureBook V3 — Illustration Architecture Design

**Date:** 2026-10-09
**Status:** Design ratified for implementation 2026-10-09 (open items below have recorded
defaults and block nothing); no code written yet. Implementation tracking lives in
`PictureBookV3Implementation.md` once Phase 0 starts. **Grounding pass completed
2026-10-09 (§36):** every claim about current code was checked against the repository, and every
claim about the renderer/LLM was checked against the live servers — SD = SwarmUI on
`192.168.1.39:7801`, LLM = Ollama on `192.168.1.42:11434`. Corrections are applied in place and
marked **[verified 2026-10-09]**; the former **[verify]** markers are resolved. **Ratified so
far** (§34): D1/D2, D3 + wardrobe, world-scoped locations, approval pins, PB1 disable→verify→remove,
ChapBook first, **Kontext retired (§37)**, **Comfy graphs compiled dynamically with the PB workflow
as proxy (§38)**, **auto-approve is one general switch (§10)**, **VLM QA default =
`valkyriesys/eudaimonia-dryad3-vision`, Comfy detection fallback (§17)**, **POI link kept
(§7.2 — object/item references)**, **composition up front (§9.1)**, **events/interactions skipped
(§34 item 15)**. Open: §34 items 3 (default value only), 7, 8, Q14 (source (a) vs (b) only) — none
blocks Phase 0–2; the design records a default for each.
**Inputs:** `PictureBookV3Notes.md` (target architecture), `PictureBookImageGenerationDesign.md`
(current behaviour and gaps G-1…G-36), `PictureBook2Plan.md` (workflow graph, §6 ComfyUI),
`PictureBookWorkflowOverhaul.md`, `PictureBookAsyncJobDesign.md`, `PictureBookSdConfigRefactor.md`.
**Not available:** the SequenceDev document that the notes refer to is not in the repository.
Where this design relies on SequenceDev (location library, background-first and sequential
inpainting), it uses the notes' description of it. **G-numbers [reconciled 2026-10-09]:**
`PictureBookImageGenerationDesign.md` was restored to the tree on 2026-10-09 (it had been missing
from the working tree and every commit). Its G-1…G-36 numbering differs from what this document
originally cited in eleven places; all G-n references below now match that file (§36.8 lists the
corrections). Two items this design treats as Phase 0 hygiene — raw `FIELD_BYTE_STORE` writes and
runs left RUNNING on exception — are not numbered gaps there but Appendix A "Other" items, and are
cited as such. **[Corrected 2026-10-09, Phase 0 planning:** orphaned runs are left **PENDING**, not
RUNNING — `PbGraphUtil.startRun` (`:738`) writes PENDING and nothing stamps RUNNING today. The only
run lifecycle is in `PictureBookUtil.generateSceneImage` (`openSceneGraph` `:9626`, `closeRun`
`:10357/:10361`); `PbNodeExecutor.executeNode` and `PbServiceFacade.testNode` open no run. Phase 0's
closure fix therefore lands in `PictureBookUtil` (finally-block) plus a FAILED stamp on the node in
`PbNodeExecutor.executeNode`; PENDING semantics are left alone until Phase 1's RUNNING/INTERRUPTED
work (§21). Line references in this document that the Phase 0 planner found stale:
`PictureBookUtil.resolveLandscapePrompt` NPE is at `:3845` (gap doc said `:3629`).**]**

> The notes set the *what*: scene intent kept separate from rendering, with this flow:
> composition → render plan → strategy → variants → QA → approval.
> This document sets the *how* for AM7. It covers concrete models, where code lives, how the
> design reuses the PB2 workflow graph that already exists, the REST/Ux surface, migration and
> phasing. Claims about current code were taken from reading it and are cited. Claims about SwarmUI
> capabilities that the code does not use today were marked **[verify]** and have now been checked
> against the installed build (§36.2).

---

## Part I — Foundations

### 1. The key decision: V3 is built on the PB2 graph, not beside it

The notes ask for render history, exact-version manifests, dependency tracking, stale detection,
variants and selection. **Most of that machinery already exists**, unused by the path that real
books take (PB1 `generateSceneImage`):

| Notes concept | Existing PB2 mechanism |
|---|---|
| Render history / versions | `olio.pb.artifact`: `revision`, `supersedes`, `contentHash`, per-artifact `sdConfigSnapshot`, sanitized `generatorRequest`, `seed`, `backend`, `backendGraph` |
| Canonical render | `artifact.selected` (exactly one per (node, role), enforced in `PbArtifactUtil.setSelected`) |
| Dependency tracking | `olio.pb.binding`: the consumer node, the producer node, and the **exact** artifact revision consumed, plus `refModel`/`refObjectId` for ordinary records |
| Asset versions | `binding.refHash` over a declared watched-field set (`PbWatchedFields`, `watched/v2`) |
| Stale detection | `node.inputHash` (pipeline version + bindings + configHash + prompt) compared on read; `markStaleDownstream` does a breadth-first walk |
| Pinned / protected outputs | `node.pinned`. Propagation still marks a pinned node stale, but regenerate refuses it with a 409 (`PbServiceFacade.requestRegenerate`, Objects7 `:299-308`; Service7 only maps the exception) **[verified 2026-10-09]** |
| Run tracking | `olio.pb.run` (`requestedNodeIds`, counts, `runStatus`). `PbRunStatusEnumType` = `UNKNOWN, PENDING, RUNNING, COMPLETED, FAILED, CANCELLED`; `CANCELLED` is declared "reserved and currently unwritten"; there is no `INTERRUPTED` yet **[verified 2026-10-09]** |
| Sparse overrides | `node.configOverride` / `scene.configOverride`, resolved by `PbConfigUtil` |

**Decision D1:** V3 models are added to the `olio.pb.*` family. Rendering *is* graph execution.
"Render", "variant", "render manifest" and "version" are expressed as artifacts and bindings rather
than parallel entities. PB1's `generateSceneImage` and its note-blob scene state are retired (this
is the end state that `PictureBookWorkflowOverhaul.md` already recommends).

**Decision D2 — current state in records, history in artifacts.** Records hold the *current* state:
`olio.charPerson`, `olio.pb.location`, `olio.pb.composition`, `olio.pb.style`. They are edited in
place, as today. Every render persists an immutable **render plan artifact** (JSON) that snapshots
the fully resolved inputs. A version manifest is therefore never reconstructed: it is the plan
artifact plus the bindings. That avoids copy-on-write versioning of characters, which would
conflict with Olio owning `charPerson`.

"Version numbers" in the Ux (Alice v4, Tavern v2) are **display revisions**. Each one is a counter
of the distinct watched hashes that an asset has rendered under. They are not stored copies.

### 2. Module placement and layering

| Layer | What goes there |
|---|---|
| `AccountManagerObjects7` → `olio/illustration/` (**new package**) | The shared illustration service: `RenderPlan`, `StrategySelector`, prompt compilers, `WorkflowCompiler` + `ComfyGraph` (§38), `ModelResolver`, `RendererAdapter` + capability model, `QaProvider`. It does not know about PictureBook or ChapBook, so ChapBook, character reimage and chat can use it. |
| `AccountManagerObjects7` → `olio/sd/` | Renderer adapter. `SwarmRendererAdapter` wraps the existing `SDUtil`/`SWUtil` and submits V3-compiled Comfy graphs through Swarm (§15, §38). |
| `AccountManagerObjects7` → `olio/picturebook/` | The PB domain: style/location/composition utilities, the composition LLM step, graph construction, `PbNodeExecutor` (now calling the illustration service), staleness and review. All of it is reached through `PbServiceFacade`. |
| `AccountManagerObjects7` → `resources/` | New model JSON under `models/olio/pb/`; prompt templates under `olio/llm/templates/`; `olio/illustration/qualityPresets.json` and `capabilities.json` (editable resources, following the `flux2Defaults.json` precedent) |
| `AccountManagerService7` | Transport only: new routes on `PictureBookService` / `ChapBookService` delegating to `PbServiceFacade`. `@RolesAllowed({"admin","user"})` on every route. |
| `AccountManagerUx752` | Book workspace, composition board, location library, review. |

There is no ISO dependency, no business logic in Service7, and every record access goes through
`AccessPoint`. The illustration service takes the **acting user** and the **per-call** server and
model (architecture rule: per-org values travel with the call; they are never pushed into
`IOContext` singletons).

### 3. Vocabulary

- **Asset**: an input the user can approve. A character portrait, a location render, the style
  references.
- **Composition**: the structured intent of one image (scene, cover, chapter opener, ChapBook page).
- **Render plan**: an immutable, fully resolved snapshot of a composition plus its assets, config,
  strategy and canvas, for **one** attempt.
- **Attempt**: one execution of one plan. It produces **N variants**.
- **Variant**: one output image. It is an artifact on the composite node.
- **Canonical render**: the selected variant.
- **Approved render**: a canonical render the user explicitly approved (§10).

---

## Part II — Domain model

All new models follow the existing `olio.pb.*` pattern:
- `inherits: [common.groupExt, common.baseLight, common.urn]` with `likeInherits: [data.directory]`;
- stored in the book's group (series-scoped records go in the series group, like cast baselines);
- `organizationId` and `groupId` conditions on every list query (PBAC rules).

Enums are new `*EnumType` classes under `schema/type`.

### 4. Book style — `olio.pb.style` (new)

Replaces `book.sdConfig`, `book.compositeSdConfig` and the meta-note config. This removes the
two-store drift (G-15, Appendix A: the book config is stored in two places and they drift) by
construction.

| Field | Type | Notes |
|---|---|---|
| `name`, `description` | string | `description` is the positive style text that every compiler appends exactly once |
| `exclusions` | string | Negative prompt / exclusions. Applied by every compiler, including ChapBook (G-25) |
| `sdConfig` | FK `olio.sd.config` | Sampler/steps/cfg/LoRAs/hires. The existing canonical config record (KI-38) |
| `taskModels` | string (JSON) | Model per task: `{portrait, location, sceneDirect, inpaint, harmonize, upscale}`. Resolved by `ModelResolver` (§16). Missing tasks fall back to the style default and then to the deployment default |
| `referenceImages` | list FK `data.data` | Style anchors (an approved scene, artwork) used by strategies that support style references |
| `palette` | string | A free-text palette/lighting direction, compiled into the prompt |
| `pageFormat` | enum `PbPageFormatEnumType` | PORTRAIT_PAGE, LANDSCAPE_PAGE, SQUARE, CUSTOM; with `customWidth`/`customHeight`. Gives explicit dimensions (G-18) |
| `qualityPreset` | enum `PbQualityPresetEnumType` | DRAFT, STANDARD, FINAL (§18) |
| `photoreal` | boolean | Whether compilers may emit realism boilerplate. False for comic/anime/art (G-16) |
| `locked` | boolean | §4.1 |
| `scope` | enum | BOOK, SERIES, LIBRARY. A series style is inherited by its chapter books |

`olio.pb.book` gets a `style` FK. `sdConfig`/`compositeSdConfig` stay, read-only, until migration
(§24) finishes.

#### 4.1 Inheritance and locking

The config is resolved by extending the existing `PbConfigUtil.resolveDeclaredConfig` pattern:

```
deployment defaults  →  style  →  composition.override (sparse)  →  attempt.override (sparse, not persisted
                                                                   except inside the render plan artifact)
```

- **No step writes to its parent.** The single write path for book defaults is
  `PUT /{book}/style`. The generation path never persists config, which removes the leak behind
  G-15.
- **Locked style:** composition overrides may not touch fields in the style-field set (`style`,
  per-style details, `description`, `exclusions`, `palette`, `taskModels`, LoRAs). Non-style
  overrides (seed, steps, camera) are still allowed. Unlocking and then editing marks every
  dependent node stale through the normal hash path.

### 5. Characters — `olio.charPerson`, unchanged as the owner

Characters stay Olio-owned. V3 adds **book-scoped portrait nodes**, not a character copy:

- Each character in a book gets one `PORTRAIT` node with `scope=book` and `scopeRef=charPerson.objectId`.
  It is bound to the charPerson (`refHash` over `PbWatchedFields`) and to its default apparel.
- **Portrait versions** = the artifact chain on that node. The **active portrait** = the selected
  artifact. `identity.profile.portrait` is kept in sync as a *projection* of the selected artifact,
  so the existing gallery and character views keep working.
- **Portraits as references.** A scene binds to the character's selected portrait artifact. It does
  not bind to `profile.portrait` directly.
- **One pipeline (G-4).** "Regenerate portrait" anywhere in a book runs the book's PORTRAIT node
  through the illustration service with the book style. Standalone character reimage (outside any
  book) also uses the illustration service, with a "character style" built from the user's existing
  `sdcfg-<oid>` preference. It does not use `generateSDImages`.
- **Portrait shot variants** (optional; Phase 5): `portrait` (head and shoulders, identity
  reference) and `fullbody` (outfit reference) as two roles on the same node. Inpaint strategies
  use the identity reference for faces and the full-body reference for clothing.

### 6. Outfits — `olio.apparel`, used directly

The notes propose a new Outfit entity. `olio.apparel` already is one: it has `name`, `type`,
`gender`, `wearables`, `inuse`, `gallery`, a designer, and an existing apparel wizard and mannequin
images. **Decision D3 (ratified 2026-10-09):** no new outfit model. Outfits are `olio.apparel`
records.

#### 6.1 Wardrobe: alternate apparel

A character changing clothes during the story is a first-class case:
- **Wardrobe** = the character's `store.apparel` list, which already holds many apparel records.
- **Default outfit** = the one record with `inuse=true`. It is the character's baseline and the
  outfit on the PORTRAIT node's full-body reference.
- **Alternates** = the other apparel in the store (`inuse=false`): a ball gown, armour, a disguise.
  They are created three ways:
  - **From the story:** the composition step (§9) detects an outfit change ("she changed into her
    riding clothes") and proposes a new alternate with an LLM-written description. The existing
    apparel/wearable generator turns that into apparel and wearables. It is created unapproved and
    `inuse=false`.
  - **By the user**, in Manage Characters, using the existing apparel wizard.
  - **By migration**, from apparel that has a `sceneIndex` tag.
- **Each alternate has its own reference image** (mannequin or outfit render in `apparel.gallery`)
  with the same review/approve flow as other assets.

#### 6.2 Per-scene outfit and continuity

- `sceneActor.apparel` is an FK to any apparel in **that character's** wardrobe; validation rejects
  apparel owned by someone else.
- **Carry-forward:** when no outfit is specified for a scene, the actor wears what they wore in the
  previous scene of the same book (by `sceneIndex`). If there is no previous scene, they wear the
  default. A change persists until the next change, which matches how stories work.
  - The resolved outfit is shown on the board with its source: "(from scene 4)" or "(default)".
  - Pinning an explicit outfit on a scene breaks the carry-forward chain at that scene.
- **Rendering never mutates the character.** There is no `inuse` flip during rendering.
  - The outfit is a composition input bound by FK and hashed through the `olio.apparel` watched
    set.
  - The outfit text is compiled from the referenced apparel record, freshly loaded. It is never read
    from a stub character store, which closes G-5 structurally.
  - Changing the *default* outfit (`inuse`) is a character edit. It marks only the scenes that
    resolved to the default as stale.
- **Supersedes:** `tagApparelSceneIndex` and the `sceneIndex` attribute. Migration converts an
  existing tag "apparel X from scene N" into explicit `sceneActor.apparel` on scene N, and lets
  carry-forward cover the scenes after it (§28).

### 7. Locations — `olio.pb.location` + `olio.pb.locationVariant` (new)

**`olio.pb.location`**: the *identity*.

| Field | Type | Notes |
|---|---|---|
| `name`, `description` | string | The canonical visual identity ("old stone tavern, dark oak beams…") |
| `kind` | enum | INTERIOR, EXTERIOR, MIXED, ABSTRACT |
| `world` | FK `olio.world` | The scope (see 7.1) |
| `aliases` | list string | Names the extractor matched to this location ("the tavern", "the Prancing Pony") |
| `poi` | FK `olio.pointOfInterest` | **Ratified 2026-10-09: use it** — "it will allow for object/item references". The POI's `store.items` is where the things at this place live, and scene elements reference them (§7.2, §8). Created map-free in a book world; linked to the existing placed POI in a game world |
| `approvedRender` | FK `olio.pb.artifact` | The approved render of the default variant. Variants carry their own |
| `locked` | boolean | Approved identity. Edits require unlock |

#### 7.1 Scope: the Olio world (ratified 2026-10-09)

Olio supports locations at both book and series level. **For PictureBook, location scope is
simplified to the book's Olio world.** That one rule covers both cases, because of how worlds are
already assigned:
- **A standalone book** (STORY or CHAPBOOK) gets its own world from `PbBookUtil.createBook`, so its
  locations are book-local.
- **Every chapter book in a series** references the one shared series world (`book.world =
  series.universe`, `PbSeriesUtil`), so its locations are series-wide automatically. "The tavern"
  in chapter 1 is the same location in chapter 9.

Records live in the world's group (a `Locations` sub-group, following the world's existing
group layout) and are found by the world FK.

**Approved renders are world assets.** Each book's workflow has its own graph, so a render
produced in one chapter must be reusable by the others. To make that work:
1. The approved render is recorded on the location (`approvedRender`, and the same field on
   `olio.pb.locationVariant`).
2. Book graphs bind to the location **record**. The `olio.pb.location` watched set includes
   `approvedRender`.
3. Approving a new render in chapter 3 therefore marks the dependent scenes stale in every chapter
   that uses that location. Each chapter sees this when its book is next opened (§11.1).
4. The LOCATION node that rendered it stays in the book that ran it, as provenance.

**`olio.pb.locationVariant`**: a *realization key*. It has a `location` FK, a `name`
("night exterior"), and sparse deltas: `timeOfDay`, `weather`, `season`, `viewpoint`, `extra`.
"Default" is the variant with no deltas.

- **Renders** are artifacts on a `LOCATION` node per (location, variant) in the book that ran them.
  Approval promotes one of them to the location's `approvedRender` (§7.1).
- Scenes bind to the location / variant **record** (`role=location`). The watched fields (name,
  description, kind, `approvedRender`, plus the variant deltas) are added to `PbWatchedFields`
  (new set `watched/v3`).
- **Location workflow:** generate → preview → edit description/prompt → regenerate → approve →
  lock. This is the same review state machine as scenes (§10).
- **Extraction.** The composition step (§9) proposes a location name per scene. The location
  resolver matches it against existing names/aliases; the LLM may return `sameAs: "<existing>"`.
  New locations are created unapproved. Scenes 3, 7 and 12 in "the tavern" all point to one record
  (G-10).

#### 7.2 Olio points of interest — the `poi` link and what it buys [grounded 2026-10-09; ratified: use it]

What `olio.pointOfInterest` actually is, from the model and the utilities that use it:

| Fact | Where |
|---|---|
| A POI is a **thing placed inside a mapped cell**: `location` is a FK to `data.geoLocation` (a `cell`, 100 m × 100 m), and `east` / `north` are **metres from the cell's western / northern grid line** (default −1 = unplaced) | `pointOfInterestModel.json:39-72` |
| It carries a `store` (inventory) and a `builder` (what can be gathered/built there); types are RESOURCE, HARVESTABLE, STASH, LAIR, RUIN, STRUCTURE, … | `pointOfInterestModel.json:46-60`, `PointOfInterestEnumType` |
| POIs are **painted onto cells** during map preparation, `n` per cell by terrain-appropriate builders, at a random metre offset | `PointOfInterestUtil.paintPointsOfInterest` (`:30-71`), called from `GeoLocationUtil.prepareCells` (`GeoLocationUtil.java:298`) |
| The position system is the MGRS-style grid: GZD → kident (100 km) → feature (1 km) → cell (100 m) → metre (`state.currentEast/North`, `poi.east/north`); distance and bearing come from `GeoLocationUtil` | `Objects7DeepReference.md` "Olio Map System"; `GeoLocationUtil.getDistanceToState`, `getAngleBetweenInDegrees` |
| Consumers are the simulation actions: `Look` lists POIs in the actor's cell with distance/direction; `Gather` walks the actor to the nearest POI's `east/north` and draws from its store | `Look.java:76-79`, `Gather.java:96-185` |
| **A PictureBook world has no map, by design.** Book *and* series worlds initialise with `BookWorldInitializationRule`, which must never call `prepareMapGrid` / `prepareK100` / `prepareCells` / `newLocation`, and whose Javadoc says: "Do not 'fix' this by giving a book world a location — that would reintroduce exactly the map data this rule exists to avoid" | `BookWorldInitializationRule.java:22-38, 53-56`; `PbOlioContextUtil.java:270, 590` |

**Decision (Stephen, 2026-10-09): use the POI link — "it will allow for object/item references."**
My earlier recommendation (drop it, because a POI is a metre position inside a mapped cell and
book worlds have no map) missed what the link is *for*: not placement, but the POI's **`store`**,
which is the Olio home for the things that exist at a place (`olio.store.items` → `olio.item`,
with `name`, `description`, `color`/`complementColor`/`accentColor`, `materials`, `pattern`,
`category`; `storeModel.json`, `itemModel.json`). That is exactly what a scene element needs to
be the *same* lantern, carriage or sword from one scene to the next.

How it works without a map — the facts that make it possible:

| Fact | Where |
|---|---|
| `pointOfInterest.location` is **nullable** (no `allowNull:false`), and `east`/`north` default to **−1 = unplaced**. A POI with no cell is a valid record; only `PointOfInterestUtil.listPointsOfInterest`/`getPointOfInterest` assume a cell, because they are cell-keyed queries | `pointOfInterestModel.json:39-72`, `PointOfInterestUtil.java:128-164` |
| **Every** world — book and series worlds included — gets `Points of Interest`, `Stores`, `Items` and `Builders` groups from `WorldFactory`; `BookWorldInitializationRule` adds `Book`/`Workflow`/`Artifacts` on top and forbids only the **map** (`prepareMapGrid`/`prepareCells`/`newLocation`), not POIs | `WorldFactory.java:64-78`, `BookWorldInitializationRule.java:32-38, 97-103` |
| `WorldUtil.deleteWorld` already cleans the world's POI and store groups, so book deletion needs nothing new | `WorldUtil.java:551, 563` |
| `PointOfInterestEnumType` has values that fit illustrated places: STRUCTURE, LANDMARK, RUIN, COMMERCIAL, RECREATIONAL, ENCAMPMENT, LAIR (plus the resource types the simulation paints) | `PointOfInterestEnumType.java` |

**V3 rules for the link:**
1. **Book / series world (no map):** when the location resolver creates an `olio.pb.location`, it
   also creates an `olio.pointOfInterest` in the world's `Points of Interest` group — `name` =
   the location name, `type` from `kind`/description (INTERIOR tavern → COMMERCIAL or STRUCTURE;
   EXTERIOR ruin → RUIN; LANDMARK for named natural places; ABSTRACT → UNKNOWN), **`location`
   null, `east`/`north` −1**, `concealed` false, and a fresh `olio.store` in the world's `Stores`
   group. **No `data.geoLocation` is created** — this honours the book-world rule's intent
   (no map data), while giving the place an Olio identity and an inventory. The POI is written
   by the olio principal like every other world sub-record (`PbBookUtil` pattern).
2. **Game world (mapped):** the resolver matches by name against the world's existing POIs (its
   `Points of Interest` group) and links to the placed one; V3 never writes `location`,
   `east` or `north`. The grid stays the simulation's.
3. **Lookup** is by FK from the `olio.pb.location` side, and by `groupId + name` on the POI side
   (a new `PointOfInterestUtil.findByName(ctx, name)`); the cell-keyed utilities are left alone.
4. **Items as scene elements.** `olio.pb.sceneElement` gains an optional `item` FK
   (`olio.item`). The location's POI `store.items` are offered to the composer (§9) as candidate
   elements, and an element the user or LLM promotes to an item is **added to that store**
   (`MemberUtil` participation on `store.items`, the same `ItemUtil.addNewInventory` shape the
   painter uses), so "the brass lantern" is one `olio.item` across scenes 3, 7 and 12. Its
   `name`/`description`/colours/`materials` join the element's watched set; a change marks the
   scenes that reference it stale (§11). Items are **not** reference images in V3 (§8) — the
   win is stable, identical prompt text and a record to hang a reference on later.
5. **Deletion / orphans:** a POI created by V3 is owned by the world and dies with it;
   `PbOrphanUtil`/`PbHealthUtil` gain one check — a `pb.location.poi` that points at a deleted
   POI is repaired by re-creating it (rule 1), not reported.

**Not adopted with it — the metre grid.** `east`/`north` placement, `Look`/`Gather`, cells and
features stay untouched; §7.2's first table is why. If a game world ever needs *illustrated*
POIs placed on its map, that is the map's feature, not V3's.

### 8. Scene, Composition, Actors, Elements

`olio.pb.scene` stays the story-side record (text, setting, action, mood, blurb, sceneIndex, book).
**Image intent moves to `olio.pb.composition`**, which is also used for covers, chapter openers and
ChapBook pages, so it is not tied to scenes.

**`olio.pb.composition`** (new)

| Field | Type | Notes |
|---|---|---|
| `book` | FK | |
| `purpose` | enum `PbCompositionPurposeEnumType` | SCENE, COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART, CHAPBOOK_PAGE |
| `scene` | FK `olio.pb.scene` (nullable) | Set for SCENE / CHAPBOOK_PAGE |
| `location`, `locationVariant` | FK | Nullable. Null means the setting is text-only |
| `settingText` | string | Fallback / extra setting description |
| `action`, `mood` | string | Copied from the scene at compose time; editable here without changing the story text |
| `shotType` | enum | CLOSE_UP, MEDIUM, FULL_BODY, WIDE, ESTABLISHING |
| `viewpoint` | enum | EYE_LEVEL, HIGH, LOW, OVER_SHOULDER, BIRDS_EYE |
| `focalActor` | FK `olio.pb.sceneActor` | |
| `cameraText` | string | Natural-language fallback/extra |
| `props` | string | Free-text props not worth a structured element |
| `configOverride` | string (sparse JSON) | Moves here from `scene.configOverride` |
| `promptOverride` / `promptLocked` | string / boolean | A human-authored prompt, used **verbatim** by the TEXT_ONLY strategy and as the *intent text* for other strategies (§12.3). It replaces ChapBook's `sdPrompt`/`promptLocked` and PB's `sdConfigOverride.description` |
| `source` | enum | LLM, USER, MIGRATED. Records who wrote the current composition |
| `userEdited` | boolean | Protects user edits from re-composition |
| `revision` | int | A display counter, bumped on save |

**`olio.pb.sceneActor`** (new; one row per participant)

| Field | Type | Notes |
|---|---|---|
| `composition` | FK | |
| `character` | FK `olio.charPerson` (nullable) | Null for an EXTRA or CROWD |
| `role` | enum | HERO, SUPPORTING, NAMED_BACKGROUND, EXTRA, CROWD |
| `priority` | int | Rendering order and reference eligibility; the default comes from the role |
| `apparel` | FK `olio.apparel` (nullable) | Null means the character's in-use default |
| `pose`, `expression`, `action` | string | Free text. A small suggested vocabulary in the Ux; not an enum, so the LLM is not over-constrained |
| `facing` | enum | LEFT, RIGHT, TOWARD, AWAY, PROFILE |
| `x`, `y`, `w`, `h` | double | Normalized 0–1 box on the canvas |
| `depth` | int | Z-order; 0 = front |
| `description` | string | For an EXTRA or CROWD: "a barmaid", "a crowd of villagers" |
| `count` | int | For CROWD |
| `referencePolicy` | enum | AUTO, REQUIRE_REFERENCE, TEXT_ONLY |

**`olio.pb.sceneElement`** (new): `composition`, `elementType` (PROP, FURNITURE, ANIMAL, VEHICLE,
STRUCTURE, NATURE, OTHER), `description`, `x/y/w/h`, `depth`, `importance` (LOW/MEDIUM/HIGH),
and **`item` (FK `olio.item`, nullable; added 2026-10-09 with the POI link, §7.2 rule 4)**. When
`item` is set, the element's prompt text is compiled from the item record (name, description,
colours, materials) rather than from the free `description`, so the same object reads the same in
every scene; the item is also in the location's POI store, which is how the composer finds it
again. Elements are compiled into prompt text and into regional prompts. They are never
referenced images in V3.

**Why rows rather than one JSON blob:** actors carry FKs to `charPerson` and `apparel`. Those need
integrity, PBAC and watched-field hashing. Making them bindable inputs is the point.
- **PATCH caveat** (`model-api.md`): actor edits are PATCHes against the actor row itself; they do
  not cascade from the composition.
- The composition node hashes the actor/element rows through a new watched set.

**Two-character limit (G-1): removed from the model.** A composition has any number of actors.
How many of them become image references is a renderer decision (§13). Nothing is ever dropped
silently (§19).

### 9. Getting a composition: "AI proposes"

Composition is a **separate LLM step after extraction**, not a bigger extraction prompt. This keeps
`extract-scenes`/`extract-chunk` stable and lets existing books gain compositions.

- New template **`pictureBook.compose-scene`**.
  - **Input:** scene text, setting/action/mood, the cast list (name, short description), the
    existing location names/aliases, the book style name, and — once a location is resolved —
    the **items already in that location's POI store** (name + one-line description), so the
    composer reuses "the brass lantern" instead of inventing "a lamp" (§7.2 rule 4).
  - **Output (JSON):**
    ```json
    { "location": {"name": "The Old Tavern", "sameAs": "The Old Tavern", "variant": "night interior"},
      "camera": {"shot": "wide", "viewpoint": "eye_level", "focal": "Alice"},
      "actors": [ {"character": "Alice", "role": "hero", "pose": "standing", "expression": "concerned",
                   "action": "holding a letter", "placement": "left foreground"},
                  {"character": null, "role": "crowd", "description": "patrons", "count": 6,
                   "placement": "background"} ],
      "elements": [ {"type": "furniture", "description": "large stone fireplace", "placement": "right"} ],
      "mood": "mysterious" }
    ```
- **Placement is coarse words, not coordinates.** `PbLayoutUtil` maps "left foreground" → a
  normalized box, using shot-type-aware templates (a wide shot gives smaller boxes and more
  separation). LLMs produce unreliable numbers; the board is for refinement.
- **Name resolution** reuses the current character matcher (exact → ILIKE + exact → accent-insensitive,
  chapter shadow first). Unresolved names become NAMED_BACKGROUND with a
  `description` and an Ux warning. They never vanish.
- **Guards:** every LLM field passes `NarrativeUtil.isMeaningful` (G-26). Templates follow the
  existing composer, placeholder hard-fail and `/no_think` conventions.
- **Re-compose** never overwrites a composition with `userEdited=true` unless the user asks for it.

### 9.1 When composition runs: up front vs. at first render — **ratified 2026-10-09: up front (A)**

**Decision (Stephen, 2026-10-09): A — up front**, with the lazy per-scene fallback and the
"Quick start" toggle from the recommendation below. Composition is its own pipeline phase and
job kind (`pb.compose`, §21), run in story order straight after `createFromScenes`, ending with
the whole-book location pass; the Ux shows extraction → compose → review → render (§26). The
table is kept as the record of why.

**Case A — up front ("eager").** Composition runs as its own pipeline phase straight after
`createFromScenes`. It is an async job (`pb.compose`), processes scenes in story order, and ends
with a whole-book pass.

**Case B — at first render ("lazy").** Composition runs inside the render job, for the scene being
rendered, the first time that scene renders.

| Concern | A — up front | B — at first render |
|---|---|---|
| **Location identity** | Sees every scene, so it can cluster settings into one location ("the tavern" in scenes 3, 7, 12) before anything renders. Enables approving locations *before* scenes (the notes' location workflow) | Each scene matches only against locations created so far. Rendering out of order (scene 12 first) creates duplicates that later need merging. Locations can't be approved ahead of scenes because they don't exist yet |
| **Outfit continuity** (§6.2) | Carry-forward needs story order. Running in order gives correct outfit changes for every scene | Rendering scene 9 before scene 4 resolves scene 9's outfit without knowing about the change in scene 4. That is wrong until a re-compose |
| **Cast roles / consistency** | Can balance roles across the book (who is HERO where) and catch unresolved names once, as a list | Decided per scene in isolation; unresolved names surface one render at a time |
| **"AI proposes, user approves"** | The user can review and correct cast, placement, location and outfit for the whole book on the board **before spending GPU time**. Generate All renders what was approved | The first thing the user sees is an image. Correcting the composition means a second render. There is no "Review compositions" step |
| **Cost / time-to-first-image** | One LLM call per scene up front (seconds to tens of seconds each, plus the whole-book pass) before any image. Wasted on scenes later deleted or merged | No up-front wait; cost is spread out and only paid for scenes actually rendered |
| **LLM ↔ SD interleaving** | All LLM work is batched, then all SD work. This is the reason `prepare-images` exists today: the current code unloads the LLM before SD (`OllamaModelUtil.unloadAll`) | LLM and SD alternate per scene. Where they share a machine, that forces a model unload/load per scene; it also mixes LLM failures into render jobs |
| **Failure isolation** | An LLM outage fails the compose job only. Affected scenes are marked "not composed" and fall back to B for those scenes. Rendering is never blocked by a compose failure discovered mid-batch | An LLM outage fails or degrades each render as it reaches it (needs the MIGRATED-style fallback composition, §29) |
| **Staleness after story edits** | Compositions exist early, so edits to scene text or cast before rendering make them stale. That needs a "re-compose stale scenes" action, respecting `userEdited` | Composed at the last moment from current text, so it is never stale at first render |
| **Async/job model** | A new job kind with its own progress; fits the extraction → compose → render pipeline the Ux already shows | No new job kind; render jobs gain an LLM stage |
| **ChapBook** | **ChapBook already works this way.** It writes each stanza's landscape prompt at create time, in order, with prior-stanza continuity (`assemblePriorContext`). Composing up front keeps PB and ChapBook on one model | ChapBook would need its continuity re-derived per page at render time. Single-page render today already lacks continuity (`ChapBookUtil` carries only the mood) |
| **Migration / existing books** | Existing books have no compositions, so they need a one-off compose job on first V3 open | Handles existing books naturally |
| **Complexity** | One more pipeline phase plus a stale-composition path | Simpler pipeline, but duplicate-location merging and outfit re-resolution become permanent clean-up work |

**Reading the table:**
- A wins on **correctness**: location identity, outfit continuity, and review before GPU spend.
  Those are the V3 goals G3, G4 and G10 in the notes.
- B wins on **time-to-first-image** and on books that already exist.
- B's costs don't go away; they turn into ongoing clean-up (merging locations, re-resolving
  outfits). A's costs are paid once, up front.

**Recommendation (adopted): A, with B kept only as a fallback.**
- Compose up front as a pipeline phase.
- For any scene that has no composition at render time (LLM was down, migrated book, newly added
  scene), compose lazily for that scene only and record it as a degradation, then offer
  "re-compose in order" afterwards.
- Run a whole-book location pass after the per-scene calls.
- Offer a **"Quick start"** toggle that renders DRAFT images as each scene finishes composing, for
  users who want early images. This gets most of B's time-to-first-image without losing A's
  ordering.

~~**Your call.**~~ Decided: A. §9, the job kinds (§21) and the wizard flow (§26) are written for A;
B survives only as the per-scene fallback and its degradation record.

---

## Part III — Rendering

### 10. Review state machine

The notes' states split across two existing axes plus one new field. Freshness and approval are
different questions, so they are not one enum.

| Axis | Where | Values |
|---|---|---|
| Freshness / execution | `node.nodeStatus` (exists) | PENDING, READY, RUNNING, DONE, STALE, FAILED, SKIPPED (+ DONE_UNVERIFIED for migrated items) |
| Review | `artifact.reviewStatus` (**new** enum `PbReviewStatusEnumType`) | GENERATED, IN_REVIEW, APPROVED, REJECTED, ARCHIVED |
| Canonical | `artifact.selected` (exists) | exactly one per (node, role) |

Rules:
1. A new attempt's variants arrive as GENERATED, `selected=false`. **No attempt ever changes
   `selected` on its own if the current selected artifact is APPROVED (G10 in the notes).** If
   nothing is approved yet, the first variant is auto-selected so the book has something to show.
2. "Use this image" selects that variant. "Approve" marks the selected variant APPROVED and **pins
   the node**. That is the existing `pinned` semantics: propagation still marks it STALE, and
   regeneration needs an explicit unpin.
3. A STALE node keeps showing its approved artifact. The reader shows a subtle stale badge; nothing
   disappears.
4. REJECTED variants are kept until cleanup (§23). ARCHIVED is used for history beyond the
   retention window.
5. **Auto-approve** (ratified 2026-10-09: auto-approve pins, and it has a clear on/off toggle;
   ratified later the same day: **it is one general switch** — no per-asset-type breakdown):
   - **Setting:** `olio.pb.book.autoApprove` (boolean). It applies to every asset kind the book
     renders (PORTRAIT, LOCATION, SCENE, COVER, CHAPBOOK_PAGE) alike. There is no
     `autoApproveTypes`. The proposed default is **off** (still open, §34 item 3).
   - **Where the toggle appears:** in book settings, and repeated in the Generate All / Render
     dialog showing its current value ("Auto-approve: ON — results will be approved and pinned").
     The book header shows a visible badge while it is on.
   - **When it applies:** when an attempt finishes, the best variant (QA ordering, else the first) is
     selected, marked APPROVED and pinned. That is exactly what a manual Approve does, recorded
     with `approvedBy=AUTO`.
   - **Guards:**
     - It never replaces an existing APPROVED artifact. A stale approved node is re-rendered only
       on request, and the new result goes to review.
     - It skips any variant with QA FAIL, and any attempt with an `ACTOR_FAILED` or
       `MODEL_SUBSTITUTED` degradation. Those stay IN_REVIEW and land in the review queue.
     - Switching it off never un-approves anything.
   - **Audit:** the review queue's "Auto-approved" filter lists everything approved this way, so it
     can be checked afterwards.

This replaces the PB1 note-status strings (`generating`/`done`/`error`/accepted/rejected).

### 11. Graph shape per composition

```
STYLE (book-scope) ──────────────────────────────────────────────┐
PORTRAIT[char] (book-scope) ──┐                                  │
LOCATION[loc,variant] (book-scope) ──┐                           │
                                    ▼                            ▼
COMPOSITION ──► RENDER_PLAN ──► (strategy stages) ──► COMPOSITE ──► QA ──► PAGE
                                 BASE / ACTOR_REGION[i] / HARMONIZE / UPSCALE
```

- **New nodeTypes:** `STYLE` (repurposing the existing `STYLE_BIBLE`), `LOCATION`, `COMPOSITION`,
  `RENDER_PLAN`, `BASE`, `ACTOR_REGION`, `HARMONIZE`, `UPSCALE`, `QA`, `COVER` (purpose-specific
  terminal, sharing COMPOSITE semantics).
- **Retired:** `SCENE_PROMPT`, `LANDSCAPE_PROMPT` and `REFERENCE_STRIP` become compiler/strategy
  internals recorded inside the plan artifact. `LANDSCAPE` becomes `LOCATION`.
- **Book-scope nodes are shared.** Scene 3, 7 and 12 bind to one LOCATION node. A portrait change
  propagates through `markStaleDownstream` to every scene that uses it. That is exactly the notes'
  "3 scenes are affected" view.
- **Strategy stage nodes are created per plan.** Once a plan picks a strategy, the stage nodes for
  that composition are (re)materialized. Each `ACTOR_REGION[i]` is a real node, so "Regenerate
  Bob" means re-executing one stage node, with the base and the other regions bound by exact
  artifact revision (G6 in the notes).

#### 11.1 Fixing stale detection (prerequisite)

Today a `charPerson` edit is invisible to `listStale` until a run re-binds the record, because
`recomputeStatus` compares stored `refHash` values. V3 requires **live drift detection**:
- `listStale(book)` / `nodeSummary` call the existing `PbGraphUtil.driftedRefBindings` (today used
  only by tests) for book-scope input nodes (PORTRAIT, LOCATION, STYLE, COMPOSITION).
- Each drifted binding marks its node STALE and propagates downstream through `markStaleDownstream`.
- PB endpoints that edit characters, apparel, locations, compositions or the style call a cheap
  `PbStaleUtil.touch(record)` after a successful write, so the common case is immediate.
- **Edits made outside PB** (Olio character editor, apparel wizard) are caught by the drift scan on
  the next book open or review. That is acceptable and stated: the bound is "the next time the book
  is viewed". There are no hooks in generic `AccessPoint.update` (layering).
- Cost: one projected, uncached read per bound record per scan. Book-scope bindings are tens of
  records, so this is fine. Cache the scan result per book for ~30 s.

### 12. The render plan

`RenderPlan` is a value object in `olio/illustration/`. It is persisted as a `JSON` artifact on the
`RENDER_PLAN` node; that persisted artifact is the version manifest.

```
RenderPlan
 ├── planVersion            "illustration/1"
 ├── purpose                SCENE | COVER | ... | CHAPBOOK_PAGE | PORTRAIT | LOCATION
 ├── canvas                 {width, height}            ← always explicit (G-18)
 ├── preset                 DRAFT | STANDARD | FINAL, + resolved {steps, variants, upscale, qa}
 ├── style                  {styleObjectId, revisionHash, description, exclusions, palette, photoreal, refs[]}
 ├── config                 fully resolved olio.sd.config snapshot (style → composition → attempt)
 ├── location               {objectId, variant, revisionHash, renderArtifactId | null, text}
 ├── camera                 {shot, viewpoint, focalActor, text}
 ├── actors[]               {actorId, characterId, role, priority, box, depth, pose, expression,
 │                           action, facing, outfit {apparelId, text, refArtifactId},
 │                           identity {portraitArtifactId, fullbodyArtifactId, text}, policy}
 ├── elements[]             {type, description, box, importance}
 ├── intent                 promptOverride (if any) | null
 ├── strategy               {name, stages[], reason}     ← filled by StrategySelector
 ├── models                 {task → resolved model name, availability-checked}
 ├── compiled               {stage → {prompt, negative}}  ← filled by PromptCompiler
 ├── degradations[]         §19
 └── inputs                 {bindingRole → artifact revision / ref hash}
```

1. **Plans are immutable per attempt.** A retry builds a new plan, which is cheap. Two attempts
   with equal plans (ignoring the seed) are a "re-roll". Unequal plans explain *why* a render
   differs.
2. **The prompt shown is the prompt sent (G-23).** The Ux shows `compiled[stage].prompt` from the
   plan artifact. No other prompt exists to be shown.
3. **Intent override.**
   - TEXT_ONLY and DIRECT_REFERENCE treat `promptOverride` as the main description and still append
     the style and exclusions. "Raw" mode (no style append) is an explicit per-composition toggle,
     for ChapBook lock parity.
   - Inpaint strategies use it as the base/scene description, and actor regions still compile from
     actor rows.

### 13. Strategy selection

`StrategySelector.select(plan, capabilities)` is pure and deterministic. It records its `reason`
string in the plan.

| Strategy | When | Stages |
|---|---|---|
| `TEXT_ONLY` | No referenced actors, or the renderer has no reference support, or forced | BASE |
| `DIRECT_REFERENCE` | referenced actors + location ref + style refs ≤ `maxReferences` for the resolved sceneDirect model | BASE (with references) |
| `SEQUENTIAL_INPAINT` | More referenced actors than `maxReferences`, renderer `supportsMasks` | BASE (location + extras + top-k actors as references) → ACTOR_REGION per remaining referenced actor, in priority order → HARMONIZE |
| `REGIONAL_PROMPT` | Many actors, low priority, or crowds; renderer `supportsRegionalPrompt` | BASE with regional prompt syntax ("Regional Inpaint" in the notes) |
| `CLASSIC_COMPOSITE` | Legacy only (migrated books that asked for it) | canvas paste → img2img |

**Defaults (configurable in `capabilities.json`):**
- `top-k` = `maxReferences - (hasLocationRef ? 1 : 0)`, using actors with `role ∈ {HERO, SUPPORTING}`
  by priority.
- NAMED_BACKGROUND actors are text plus a regional prompt unless the user sets
  `REQUIRE_REFERENCE`.
- EXTRA and CROWD are always text/regional.

**Location as a reference vs. a base:**
- DIRECT_REFERENCE passes the approved location render as a reference image (FLUX.2 today).
- SEQUENTIAL_INPAINT starts from the location render as the **init image** for BASE, at a low
  creativity value, so the actors are painted into the approved place. That is the notes'
  "background-first".
- If the location has no approved render, both fall back to text and record a degradation (G-11).

### 14. Prompt compilers

There is one interface, `PromptCompiler.compile(plan, stage) → {prompt, negative}`, with one
implementation per model family. These replace the scattered builders: `NarrativeUtil.getSDPrompt`,
`buildPortraitDescription`, `SWUtil.buildFlux2ScenePrompt`, `newKontextSceneTxt2Img`, ChapBook's raw
`sdPrompt`.

| Compiler | Used for | Notes |
|---|---|---|
| `Flux2Compiler` | DIRECT_REFERENCE on FLUX.2 | Keeps today's tested positional reference wording, identity rules and "don't draw the references" rules (KI-68). It now lists *all* actors: referenced actors by ordinal, the rest as text |
| `SdxlCompiler` | TEXT_ONLY / BASE / ACTOR_REGION on SDXL | Weighted terms allowed. Realism boilerplate only when `style.photoreal` (G-16) |
| `InpaintRegionCompiler` | ACTOR_REGION, HARMONIZE | Region-local: one actor's identity/outfit/pose/expression plus "matching lighting and style of the surrounding scene" |
| `RegionalPromptCompiler` | REGIONAL_PROMPT | Emits Swarm's `<region:x,y,w,h,strength>` / `<object:…>` syntax from the actor/element boxes. **[verified 2026-10-09]** The installed build (0.9.8.3) exposes the regional-prompting parameter group (`globalregionfactor` 0.5, `regionalobjectcleanupfactor`, `regionalobjectinpaintingmodel`, `maskcompositeunthresholded`) through plain `GenerateText2Image`, so the syntax is available without a custom workflow; box semantics and quality still get a live check in the Phase 5 spike (§36.7) |
| ~~`KontextCompiler`~~ | **Not built — Kontext retired (ratified 2026-10-09, §34/§37)** | No Kontext checkpoint is installed on .39 (§36.1); `flux2Klein_9b` / `flux2_dev` cover the reference-editing role. The `kontext*` config fields (G-21) are deprecated in Phase 0 and dropped in Phase 10 per §37.3 |

Prompt compilers produce text. The **graph** that text goes into is built by the workflow compiler
in §38 — the compilers above are stage-level inputs to it, not alternatives to it.

**Rules shared by every compiler:**
- Style text is appended exactly once.
- Exclusions are always applied.
- Every field is checked with `isMeaningful`.
- Smart quotes are normalized and LoRAs appended (the existing `appendLoras`).
- A configurable token budget per family. On overflow, drop in this order: elements LOW → elements
  MEDIUM → extras → background-actor detail, and record the drop as a degradation.

**The LLM never writes the final prompt.** It writes composition fields (§9). The landscape-prompt
LLM call survives only as **location description authoring**: "describe this location visually" is
offered when the user creates or edits a location. It is not a per-render step. That removes the
wasted scene-image-prompt call (G-23) and the template-override cross-wiring (G-24).

### 15. Renderer adapters and capabilities

```java
interface RendererAdapter {
  RendererCapabilities capabilities(String model);
  StageResult execute(StageRequest req, CancelToken cancel);   // one txt2img/img2img/inpaint call, N images
  List<ModelInfo> listModels();                                // cached, TTL
  boolean cancelActive(CancelToken cancel);                     // best effort
}
```

**`RendererCapabilities`:**
- `maxReferences`, `supportsReferences`, `supportsStyleReferences`
- `supportsInitImage`, `supportsMasks`, `supportsRegionalPrompt`
- `supportsSeedReplay`, `supportsCancel`, `supportsBatch`
- `recommendedSteps`, `cfgRange`, `nativeResolutions[]`, `secondsPerStepPerReference` (for estimates)

Capabilities are **declared per model family in `olio/illustration/capabilities.json`**, keyed by
glob on the model name (`flux2*`, `*xl*`, `*kontext*`), and intersected with what the adapter
supports. Example seed values: FLUX.2 Klein `maxReferences=3`; SDXL `maxReferences=0`,
`supportsMasks=true`. **[verified 2026-10-09]** The "3" is structural today, not a declared
constant: `SceneCompositeUtil.java:191-200` prepares exactly `left`/`right`/`setting` references,
while `SDUtil.buildFlux2References` is uncapped varargs. `secondsPerStepPerReference` has **no
trustworthy seed value**: the only figure in the repo (~40 s/reference at 1024 px / 4 steps, 706 s
for a 3-reference 24-step request — `flux2Defaults.json:22-26`, `SceneCompositeUtil.java:107-111`,
KI-59) was measured on the Strix Halo iGPU, not on the .39 GPU that this work targets. It must be
re-measured on .39 (§36.7).

**`SwarmRendererAdapter`: the one adapter for V3.** The deployed renderer is SwarmUI running on a
ComfyUI backend — **[verified 2026-10-09]** SwarmUI `0.9.8.3.GIT-d9ecb52d` on `192.168.1.39:7801`,
one `comfyui_selfstart` backend (id 0, GPU 0, `OverQueue` 1), anonymous session user `local` with
permissions `*`. Swarm's own parameters cover the simple strategies. Anything beyond them
(multi-stage inpaint, detection, harmonize) runs as a **Comfy workflow executed through Swarm**.
That needs no separate Comfy adapter and no second server.

The adapter wraps today's session handling (`SDUtil.getOrCreateSession` → `POST /API/GetNewSession`,
cached per server, retried once), `GenerateText2Image`, image fetch, seed read-back and
`ListModels`. **It adds** — every parameter below is present in the installed build's
`ListT2IParams` (259 parameters; §36.2) **[verified 2026-10-09]**:
- **Mask inpainting through the plain `GenerateText2Image` call:** `initimage` + `maskimage`
  (white = change), `initimagecreativity` (0–1, default 0.6), `maskshrinkgrow` (default 8 =
  "inpaint only masked"), `maskblur` (4), `maskgrow` (0), `initimagerecompositemask` (true),
  `useinpaintingencode`, `maskbehavior` (Differential | Simple Latent), `unsamplerprompt`. For
  ACTOR_REGION / faces / clothing. No inpainting-specialised checkpoint is installed (§36.1);
  these parameters work with ordinary checkpoints, and whether that quality suffices is a spike
  question, not an API one.
- **Regional prompting** (`<region:…>` / `<object:…>`; parameter group `globalregionfactor` …) for
  REGIONAL_PROMPT, and **segment refinement** (`<segment:text>` via CLIPSeg, `<segment:yolo-…>` via
  a YOLO model file; `segmentmodel`, `segmentsteps` 40, `segmentcfgscale` 7,
  `segmentmaskblur` 10, `segmentmaskgrow` 16, `segmentmaskoversize` 16, `segmentthresholdmax`,
  `segmentsortorder`, `segmentapplyafter` Base|Refiner, `segmenttargetresolution` 1024x1024,
  `savesegmentmask`) for auto-masked face fixes. `face_yolov8m.pt` is installed since the
  2026-10-09 afternoon install (§36.5), so both the YOLO and the CLIPSeg forms are available; the
  graph the YOLO form generates is captured in §38.1.
- Explicit `width`/`height` always (G-18; 64–16384). `images` = variant count (1–10000, plus
  `batchsize`). `variationseed`/`variationseedstrength` for re-rolls close to a selected variant.
  `fluxguidancescale` (default 3.5) for FLUX. `refinerupscale` (0.25–8) + `refinermethod`
  (PostApply | StepSwap | StepSwapNoisy) for the UPSCALE stage. `removebackground` (RemBG) for
  cut-outs. `promptimages` (the hidden reference-image parameter the code already uses),
  `enablereferencelatents`, `textencodedimage`, `usereferenceonly`.
- ~~**Named Comfy workflows** via `comfyuicustomworkflow`~~ **Superseded 2026-10-09 by §38:**
  anything beyond Swarm's own parameters (SEQUENTIAL_INPAINT, HARMONIZE, QA) is a Comfy graph
  that V3 **compiles per stage** from the `RenderPlan` and submits with `comfyworkflowraw`; no
  saved workflows are authored or versioned on the server. The adapter gains
  `generateBaseGraph` (`ComfyGetGeneratedWorkflow`), `nodeCatalogue` (`object_info`) and a
  raw-graph `execute`. The graph hash and save-node id are what `artifact.backendGraph` records.

Confirmed by code read **[verified 2026-10-09]**: `SWTxt2Img` has no mask field — its wire names
are `model, prompt, sampler, scheduler, refinersampler, refinerscheduler, negativeprompt, images,
steps, cfgscale, seed, height, width, refinercfgscale, refinerupscale, refinermodel, refinersteps,
refinermethod, refinerupscalemethod, refinercontrolpercentage, initimage, initimagecreativity,
promptimages`, plus `session_id` from `SWCommon`. Nothing in Objects7 sends `maskimage`,
`comfyuicustomworkflow`, region or segment syntax. `SDAPIEnumType.COMFY` exists with no behaviour
behind it. `PbNodeExecutor` constructs `new SDUtil(SDAPIEnumType.SWARM, swarmServer)` directly at
`:188`, `:473`, `:563` and never references `SWUtil`; that constructor call is the seam
`SwarmRendererAdapter` replaces.

`PictureBook2Plan.md` §6's standalone Comfy client is not needed: Comfy is already reached through
Swarm.

**Byte handling:** adapters return bytes. Persistence goes through `PbPipelineUtil.persistBytes` /
artifact creation using `ByteModelUtil.setValue`, never a raw `FIELD_BYTE_STORE` write (gap doc
Appendix A "Other": every SD write path sets `FIELD_BYTE_STORE` directly today).

### 16. Model resolution

`ModelResolver.resolve(task, plan, adapter)`:
1. explicit attempt override
2. `style.taskModels[task]`
3. style `sdConfig.model` (for SDXL-family tasks) or `flux2Model` (for sceneDirect)
4. the deployment default for the task
5. fail

At each step the candidate must be **installed** (`adapter.listModels()`, cached 5 min) and its
family must **support the task** (capabilities). A missing model never reaches the renderer. The
plan records a degradation ("FLUX.2 Klein not installed; using DIRECT_REFERENCE → SEQUENTIAL_INPAINT
on juggernautXL"), or a hard failure with a clear error if nothing qualifies.

- **One default policy (G-19/G-20):**
  - Deployment defaults per task live in one place: a `/System` config record next to the
    `system.connection` server URLs, or one init-param block, read through one resolver.
    **[verified 2026-10-09]** Today `ServerConfigUtil` resolves **only server URLs** (`sd`, `face`,
    `tag`, `voice.tts`, `voice.stt`, `embedding`; 30 s cache) and knows nothing about models. The
    SD model default is `SDUtil.setDefaultModel`, set once at boot by `RestServiceEventListener`
    (`:327-331`, `sd.default.model` → `sd.model`), while `ChatService.java:1425` and five
    `OlioService` routes read `sd.model` per request, and `olio.sd.config` carries its own `model`,
    `flux2Model`, `kontextModel`. So the resolver is new; it does not extend `ServerConfigUtil`.
  - Reimage, chat, PB and ChapBook all go through the resolver.
  - The three-different-checkpoints problem disappears because nothing reads `sd.model` or the
    schema default directly any more.
- **Ux:** `GET /olio/illustration/models?task=` returns models with availability and suitability
  per task. The style editor shows "FLUX.2 Klein 9B ✓ available".

### 17. Visual QA

QA is a pluggable, optional stage behind one interface. **Ratified 2026-10-09 (§34 Q12): the
default provider is the vision LLM on `.42` with `valkyriesys/eudaimonia-dryad3-vision`; when that
model is not available on the resolved LLM route, QA falls back to the Comfy detection graph
through Swarm.** The paragraph that follows describes the two providers; the earlier text that
said QA "does not go through the LLM stack" described the Comfy provider only and is superseded.

- **Interface:** `QaProvider.check(plan, imageBytes) → QaReport {result: PASS|WARN|FAIL, checks[]}`.
- **`VlmQaProvider` (default):** sends the rendered image plus a compact statement of the plan
  (expected actors with their descriptors and boxes, outfit, location elements) to the vision model
  and asks for a structured report: visible-person count, per-actor presence, per-actor face match
  against the portrait (sent as a second image), and semantic mismatches. Model resolution: the
  book's chatConfig route (§36.4) with model name `valkyriesys/eudaimonia-dryad3-vision`; if that
  name is not in the route's model list the provider reports itself unavailable and the selector
  moves to the Comfy provider. The VLM output is parsed with the same `"null"`-guarding as every
  other LLM-extracted field (`NarrativeUtil.isMeaningful`). Needs no install.
- **`SwarmQaProvider` (fallback):** a V3-compiled QA graph (§38) that returns
  measurements, not images:
  - **Person and face detection.** Count visible people and faces, and compare against the plan's
    expected count (HERO + SUPPORTING + NAMED_BACKGROUND, ± crowd).
  - **Per-actor presence.** Check for a detected person or face inside each referenced actor's box.
  - **Identity.** Compare face embeddings between each detected face in an actor box and that actor's
    portrait artifact. This depends on a face-analysis node set being installed in the Comfy backend;
    without it the check reports `UNAVAILABLE`.
- **What the installed backend can do [re-verified 2026-10-09 after the afternoon install, §36.3]:**
  detection and counting work with built-ins — `SwarmYoloDetection` with `face_yolov8m.pt` (now
  installed), `MediaPipeFaceLandmarker` / `MediaPipeFaceMask`, `RTDETR_detect`, `SAM3_Detect` /
  `SAM3_TrackToMask`, `SwarmClipSeg` — plus the newly installed `sam2` pack. **Identity** has a
  dependency on disk now (`IPAdapterInsightFaceLoader` from the `cubiqipadapterunified` pack) but
  no embedding-*compare* node and an unverified InsightFace Python runtime, so a face-match score
  is still "needs a spike" (§36.7 item 4), not available. The first QA graph ships with the
  detection checks only; identity reports `UNAVAILABLE` until that spike passes.
- **Why the VLM is the default (§34 Q12, ratified):** `.42` serves `qwen3-vl:8b-instruct` and
  `valkyriesys/eudaimonia-dryad3-vision` **[verified 2026-10-09, §36.4]**. The VLM covers count and
  presence *and* the semantic checks (outfit, location elements) that the Comfy route cannot, and
  it can attempt the identity check today, which the Comfy route cannot until the §36.7 item 4
  spike passes. Stephen's choice of the eudaimonia model over `qwen3-vl` is a model preference,
  recorded as the default name in `qualityPresets.json` / the provider config, not hard-coded.
- **Not checked automatically when the Comfy fallback is in use:** outfit, location elements and
  other semantic checks; identity reports `UNAVAILABLE`. The user checks those in review.
- **Storage:** `QA` node → JSON artifact (`role=qa`) bound to the exact variant. The variant shows a
  ✓ / ⚠ / ✗ per check.
- **Behaviour:** QA never deletes, never auto-selects past an approved image, and never blocks.
  A FAIL only orders variants (best first) and raises a review flag.
- **Preset control:** DRAFT = off, STANDARD = on for the canonical variant, FINAL = all variants.

### 18. Quality presets

The presets live in an editable resource, `olio/illustration/qualityPresets.json` (the
`flux2Defaults.json` precedent):

| Preset | Resolution | Steps | Variants | Upscale | QA |
|---|---|---|---|---|---|
| DRAFT | 0.5× page format | model-family low (e.g. FLUX.2 4) | 1 | no | off |
| STANDARD | 1× | family default | 2 | no | canonical |
| FINAL | 1× | family high | 4 | yes (UPSCALE stage) | all |

The preset resolves into the plan (§12). The Ux shows an **estimate** before Generate All:
scenes × stages × variants × `secondsPerStepPerReference`. This matters because FLUX.2 cost scales
with steps and the number of references. **[verified 2026-10-09]** The SD server for this work is
SwarmUI on `192.168.1.39` (§15, §36.1), not the Strix Halo; the only timing figure in the repo was
measured on the Strix Halo iGPU, so the estimate's seed values must be measured on .39 before they
are shown to anyone (§36.7).

### 19. Degradations and transparency (G9 in the notes)

`plan.degradations[]` is the single channel for everything that today happens silently. Each entry
has a `code`, a human `message`, and the `actorId`/`stage` it applies to.

| Code | Example message |
|---|---|
| `ACTOR_TEXT_ONLY` | "Carol rendered from description (reference limit 3)" |
| `ACTOR_UNRESOLVED` | "'the stranger' isn't a known character; drawn as background figure" |
| `ACTOR_FAILED` | "Bob's region failed after 2 attempts; scene kept without him" |
| `LOCATION_TEXT_ONLY` | "No approved render for The Old Tavern; setting described in text" |
| `MODEL_SUBSTITUTED` | "FLUX.2 Klein not installed; used …" |
| `PROMPT_TRUNCATED` | "3 low-importance props omitted to fit the prompt budget" |
| `QA_WARN` | "Bob may be missing" |

The scene card shows a summary ("5 characters: 3 referenced, 2 regional"). The review screen shows
the full list. A degradation never stops the render.

### 20. Failure semantics

| Failure | Behaviour |
|---|---|
| A LOCATION render fails | That LOCATION node is FAILED. Dependent scenes plan with `LOCATION_TEXT_ONLY`; the scene is not failed (G-11) |
| A PORTRAIT fails | The actor degrades to text (`ACTOR_TEXT_ONLY`), plus a warning. The scene renders. The plan and references stay consistent because the selector runs *after* asset resolution (fixes G-6) |
| An ACTOR_REGION fails | Retry once with a new seed; then keep the previous stage's image, `ACTOR_FAILED` |
| BASE/COMPOSITE fails | The node is FAILED with `lastError`; the attempt is FAILED; the approved artifact (if any) is untouched |
| The renderer is unreachable | The attempt is FAILED fast; the job pauses its remaining scenes with a "renderer unavailable" state instead of failing every scene |
| The renderer is reachable but slow | **Not a failure.** There is no per-stage wall-clock limit; the only watchdog is **no progress** (no Swarm progress frame and no node status change) for a long ceiling (default 30 min, from capabilities), after which the stage is FAILED as `RENDERER_STALLED` and the job pauses as above. Small GPUs are expected to take longer (§31) |
| QA fails to run | Variant unaffected; QA artifact records `ERROR` |
| The compiled Comfy graph fails local validation (§38.2) | `GRAPH_INVALID`: the stage node is FAILED with the offending node id and input in `lastError`; no request is sent, no GPU time is spent |
| Swarm refuses the graph (`unsupported node type`) or Comfy reports an execution `error` | `RENDERER_REJECTED` with the node id; treated like BASE/COMPOSITE failure for the stage, and the node catalogue cache is invalidated so the next attempt re-validates against the live backend |
| An exception anywhere | `run` and `node` are closed as FAILED in a `finally` block (gap doc Appendix A "Other": graph runs are left RUNNING when a scene throws — **[corrected 2026-10-09: left PENDING; see the note under the opening paragraph]**) |

### 21. Async execution, durability and cancellation

`AsyncJobRegistry` is in memory only (lost on restart), runs 2 jobs concurrently, and allows 4
active jobs per principal **[verified 2026-10-09: `AsyncJobRegistry.java` `MAX_CONCURRENT_JOBS=2`
`:66`, `MAX_ACTIVE_JOBS_PER_PRINCIPAL=4` `:90`, `COMPLETED_TTL_MS` 30 min `:75`,
`MAX_RETAINED_JOBS=200` `:78`]**. V3 does not make the job registry durable. **The graph already is**:
- **A render job = an `olio.pb.run`** whose `requestedNodeIds` are the terminal nodes requested.
  The async job is just the worker executing that run. Kinds: `pb.compose` (§9.1), `pb.render`
  and `pb.renderAll`, alongside today's `pb.extractScenes`, `pb.retryFailedChunks`, `cb.create`,
  `cb.render` and `chat.chain`. There is **no run endpoint today** (`olio.pb.run` is written by the
  executor but not exposed) **[verified]**.
- **Progress** = node statuses plus the current stage, also mirrored into the job's
  `SummarizeProgress` token (`org.cote.accountmanager.olio.llm.SummarizeProgress`) for polling.
- **Restart recovery:** on boot, any run left RUNNING is marked INTERRUPTED (add this value to
  `PbRunStatusEnumType`; see §1 for its current values). Its RUNNING nodes reset to READY. The Ux offers "Resume", which submits a
  new run for the unfinished requested nodes. Completed stages are reused because their artifacts
  exist and their input hashes match. This mirrors the extraction checkpoint's "the job covers a
  lost connection; the checkpoint covers a lost process".
- **GPU serialization:** a per-SD-server render semaphore (permits from capabilities, default 1)
  inside the adapter, so two concurrent jobs never stack on one GPU. LLM steps (compose, location description) use a
  separate lane.
- **Cancellation** (cooperative, at stage boundaries):
  - stop scheduling stages;
  - call `adapter.cancelActive` (Swarm's interrupt, which stops the Comfy backend job);
  - discard in-flight output;
  - the run becomes CANCELLED (start writing this reserved value);
  - **a cancelled attempt never changes `selected`.**
- **All substantial generation is async:** scene, location and portrait renders, Generate All, and
  ChapBook render. The synchronous `/scene/{id}/generate` routes stay as thin wrappers only during
  the transition.

### 22. Targeted regeneration

| Action | Executes | Keeps (bound by exact revision) |
|---|---|---|
| Regenerate scene | RENDER_PLAN → all stages | Approved location, portraits |
| Re-roll (same plan, new seed) | Same stages, plan reused | Everything |
| Regenerate background | LOCATION (new render) → BASE → downstream | Actor regions re-run on the new base, because a moved background changes the context they need |
| Regenerate *Alice* | ACTOR_REGION[Alice] → HARMONIZE | BASE, other regions |
| Regenerate faces | per-actor ACTOR_REGION with a face-box mask (from the actor box's upper portion, or a detector later) | Everything else |
| Regenerate clothing | ACTOR_REGION with a body mask + outfit-only prompt | Face, others |
| Regenerate region (user box) | ad-hoc ACTOR_REGION-type node over a user-drawn box | Everything else |

- **DIRECT_REFERENCE renders have no regions.** "Regenerate Alice" there means: switch that
  composition to SEQUENTIAL_INPAINT, or inpaint Alice's box over the current canonical image.
- **Whether inpainting over a FLUX.2 output with an SDXL inpaint model keeps the style is unproven.**
  It is a Phase 5 spike with visual inspection (`feedback-visual-inspection-required`).

### 23. Variants and retention

- Variants are N artifacts on the terminal node from one attempt. They share `artifact.attempt`
  (the run objectId) and have their own `variantIndex` and `seed`. **Both are new schema fields**
  — `attempt`, `variantIndex`, `reviewStatus` and `durationMs` do not exist on
  `artifactModel.json` today (verified 2026-10-09, §36.6); they are added in Phase 0 (§32).
- The variant strip shows the current attempt plus the approved artifact. Older attempts sit behind
  "History".
- **Retention:** keep the approved and selected artifacts forever. Keep the last K attempts (default
  3) per node. Move older GENERATED/REJECTED variants to ARCHIVED, and delete their bytes after D
  days (default 30). **This is a new sweep routine**, not an extension of an existing one:
  `PbOrphanUtil` deliberately skips artifacts (`PbOrphanUtil.java:53-54`) and `PbHealthUtil` is a
  stale-graph scan, so neither can be reused as-is. Put it beside them in
  `org.cote.accountmanager.olio.picturebook` (e.g. `PbRetentionUtil`) and expose it through the
  same health routes. Stage intermediates (BASE, regions) keep only those referenced by a retained
  final artifact.

---

## Part IV — Products

### 24. ChapBook on V3

**ChapBook is not a later add-on. It moves with PictureBook phase by phase**, because the two
already share most of the stack:
- the models: `olio.pb.book` (`bookType=CHAPBOOK`), `olio.pb.scene`, and the world/groups/grants
  from `PbBookUtil.createBook`;
- config resolution (`PbConfigUtil.resolveEffectiveConfig`), the scene override route
  (`PUT /picture-book/scene/{id}/config-override`) and `SdConfigPanel`;
- `SDUtil.createImage`, the reader (`readerShell.js`, `bookPageView`), `AsyncJobRegistry`, and
  health/orphan/delete utilities.

Any change to a shared piece changes ChapBook too, so every phase in §30 lists its ChapBook
impact and its ChapBook tests.

**ChapBook is also the simplest V3 consumer.** It has no actors, so it uses the TEXT_ONLY strategy
with no portraits. That makes it the right first product to prove the new core on (style, plan,
compiler, model resolver, async render job, review, auto-approve) before multi-character work
(§30 Phase 2).

**Mapping:**
A ChapBook page = a `CHAPBOOK_PAGE` composition with zero actors:
- `location` is optional (a poem's recurring place can be a location);
- `settingText` comes from the stanza landscape description;
- `promptOverride`/`promptLocked` replaces `sdPrompt`/`promptLocked`.

The existing `chapBook.landscape-prompt` continuity (poem theme/mood/keywords plus the prior 2
prompts) becomes the **ChapBook composer**. It writes `settingText`/`mood`/`cameraText` instead of
a raw prompt.

What this gives ChapBook for free:
- the book style with exclusions (G-25);
- saved book defaults (G-32);
- page overrides that survive book-level renders, because inheritance never flattens children;
- variants, async, and the same review flow.

`imageStale` maps onto node STALE. Character support in ChapBook (G-35) is just "allow actors on
CHAPBOOK_PAGE"; it is off by default.

**Other ChapBook specifics:**
- **Locations:** a ChapBook book has its own world, so the same world-scoped location library
  (§7.1) applies. A poem cycle that keeps returning to "the harbour" can share one approved place.
  Locations stay optional per page.
- **Composition timing:** ChapBook already composes up front, in order, at create time (§9.1).
- **Old path:** ChapBook's current render path (`ChapBookUtil.renderResolvedScene` /
  `renderChapBookSummary`) follows the same disable → verify → remove lifecycle as PB1 (§28.1).

### 24.1 Chat scene renderer: a third consumer of the same code

Chat's scene generator also renders through this code:
- the Ux side is `chat/SceneGenerator.js`;
- the backend is `ChatService.generateScene` (`POST /{objectId}/generateScene`,
  `ChatService.java:1401-1405`; the earlier "`:1348-1393`" pointed at `GET /chain/status`)
  **[verified 2026-10-09]**;
- it shares `SceneCompositeUtil` (`buildSceneRequest` `:1553`), `SWUtil` (FLUX.2/Kontext builders),
  `SDUtil` landscape generation (`generateLandscapeBytes`, called at `:1516`) and the shared
  `SdConfigPanel`;
- it saves to `~/Gallery/Scenes/<label>` (`:1540`, `createSceneImage` `:1567`).

Every change to the composite, compiler and adapter layers therefore changes chat too.

**Where chat differs today:**
- It calls `resolveMode(sdConfig, true)` (`:1490`, "preserves chat's historical default of
  Kontext"), so it defaults to **Kontext** only when `compositeMode` is absent from the config; the
  schema default is `flux2`. PictureBook passes `false`.
- It uses its own landscape prompt and negative prompt. The 1024×576 size is the landscape stage
  (`SDUtil.generateLandscapeBytes`, `SDUtil.java:1409-1410`); the composite size depends on mode
  (FLUX2 1024×768 from `flux2Defaults.json`, Kontext 1024×1024, classic 1024×768).
- It takes its model from the `sd.model` init-param (`ChatService.java:1425`).
- Its SD config does not persist (KI-65), and its form has slider/default problems (KI-64).

**V3 position:**
- Chat is a consumer of the shared illustration service (§2), not of the PictureBook domain.
- A chat scene builds a **transient composition**: actors are the chat's characters, the setting
  comes from the conversation, and there is no persisted location library.
- It renders through `RenderPlan` → strategy → compiler → `SwarmRendererAdapter`, with
  `ModelResolver` choosing the model.
- What chat gains:
  - one model policy (G-19);
  - explicit dimensions;
  - transparent degradations;
  - a resolved-mode default that is the same as everywhere else (the Kontext-by-default
    divergence goes away).
- It needs no graph, review or variants unless those are wanted later. The plan's compiled prompt
  and the degradations are returned with the image.
- **Its style comes from the chat's saved SD config.** That persistence is KI-65; resolve it in
  the same change.
- **Phasing:** chat moves onto the illustration service in Phase 2, alongside ChapBook, because
  Phase 2 is where the shared core (plan, compilers, resolver) is introduced. Its old inline
  composite code follows the §28.1 disable → verify → remove lifecycle with the others.
- **Tests:** every phase that touches `SceneCompositeUtil`, `SWUtil` or `SDUtil` re-runs the chat
  scene tests as well as the PB and ChapBook ones.

### 25. Covers and book art

COVER, TITLE_PAGE, CHAPTER_OPENER and SERIES_ART are compositions with book or series scope. Cover
creation options:
1. **Auto:** the `pictureBook.compose-cover` template proposes a composition from the book summary,
   the hero cast and a signature location. It may include title-text-safe space (top/bottom band
   reserved in the layout; the text itself is overlaid by the reader, never rendered by SD).
2. **From scene:** clone a scene's composition into a COVER composition, then adjust it. The
   cover's own composition is separate, so the scene is not repurposed.
3. **Manual:** an empty composition on the board.

The cover is rendered and reviewed like any composition. The reader's cover page uses the approved
COVER render.

### 26. Ux

**Book workspace** (replaces wizard steps 4–5; the extract steps stay):

```
Book ▸ Style | Characters | Locations | Scenes | Cover & Art | Review queue
```

- **Style:** edit the style, reference images, page format, preset, task models with availability,
  and lock.
- **Characters:** the existing Manage Characters, plus each character's book portrait chain (select
  or approve), and "affected scenes: 3 [Review] [Regenerate]".
- **Locations:** the library grid. Per location: description, variants, render chain,
  approve/lock, "used by scenes 3, 7, 12".
- **Scenes:** a card list with a status chip (stale/review/approved), degradation summary and
  thumbnail.
- **Scene detail:** Story | Composition | Render | Review. The composition board from the notes
  §19/§43:
  - drag, resize and z-order actor and element boxes over the approved location render, or over a
    neutral canvas at the page aspect;
  - a side panel for the actor list (role, outfit picker filtered to that character's apparel, pose,
    expression);
  - camera controls.
- **Render:** preset, estimate, Generate, plus a live stage progress list from job polling.
- **Review:** the variant strip with QA ✓/⚠ per check, the degradations list, the plan inspector
  (the compiled prompt per stage: "prompt used"), Use this / Approve / Reject, and targeted
  regeneration buttons enabled per strategy capability.
- **Review queue:** every node with an unreviewed GENERATED variant, a QA warning, or a STALE
  approved image, across the book.
- **Workflow canvas** (`pictureBookWorkflow.js`): stays as the power-user view of the same graph.
  It needs the A4 fix: `nodeSummary` must return the canvas geometry it saves.

Ux implementation follows Ux752 patterns. Read `features/pictureBook.js`,
`workflows/pictureBook.js`, `pictureBookCharacters.js`, `SdConfigPanel.js` and
`features/pictureBookWorkflow.js` before building (rule: read the reference UI first).
`SdConfigPanel` is reused inside the style editor and the per-composition "Advanced" override, and
**only sends sparse diffs** (fixes the "full entity overwrites children" class, G-32).

### 27. REST surface (all through `PbServiceFacade`; `admin,user`)

| Method + path (under `/olio/picture-book`) | Purpose |
|---|---|
| `GET/PUT /{book}/style`, `POST /{book}/style/lock` | Style CRUD/lock |
| `GET/POST /{book}/locations`, `PATCH/DELETE /location/{id}`, `POST /location/{id}/variants` | Location library |
| `POST /location/{id}/render?variant=` (async) | Render a location |
| `POST /{book}/compose` → 202, `POST /{book}/scene/{id}/compose` | Whole-book, in-order composition job (§9.1); single-scene re-compose. Both respect `userEdited` |
| `GET/POST /character/{id}/wardrobe`, `PATCH /scene-actor/{id}/apparel` | Wardrobe alternates; per-scene outfit (§6) |
| `GET/PUT /{book}/settings/auto-approve` | Auto-approve toggle (§10 rule 5) |
| `GET/PATCH /composition/{id}`, `POST/PATCH/DELETE /composition/{id}/actor[/{aid}]`, same for `/element` | Composition editing |
| `POST /composition/{id}/plan` | Dry-run: build and return the plan (strategy, models, compiled prompts, degradations, estimate) without rendering |
| `POST /composition/{id}/render` → 202 `{jobId, runId}` | Render an attempt (`preset`, sparse `override`, `target`: scene \| actor:{id} \| region:{box} \| faces \| clothing \| background) |
| `POST /{book}/render-all` → 202 | Render all stale/unrendered compositions |
| `POST /{book}/run/{runId}/resume`, `/cancel` | Durable run control |
| `PUT /artifact/{id}/select`, `/approve`, `/reject` | Review |
| `GET /{book}/stale` (with live drift), `GET /{book}/review-queue` | Staleness / review |
| `POST /character/{id}/portrait/render` (async), `PUT .../portrait/{artifact}/select` | Book portraits |
| `GET /olio/illustration/models?task=` | Availability-checked models |

`/rest/job/{jobId}` polling is unchanged. ChapBook routes become thin aliases over the same
composition/render routes.

---

## Part V — Transition

### 28. Migration

Migration is lazy, per book, idempotent and non-destructive. It runs through `PbMigrationUtil` on
first V3 open, plus an optional batch admin route.

1. **Style:** created from (in order of preference) `book.sdConfig` FK → meta-note config →
   `compositeSdConfig` as the sceneDirect task override. `compositionContext` → `style.palette`/
   description prefix. The old fields are left in place, unread.
2. **Characters:** a PORTRAIT node per cast member. The existing `profile.portrait` image is
   imported as revision 1, `selected`, `reviewStatus=APPROVED`, `DONE_UNVERIFIED` (the existing
   status for "inherited, not produced by this graph").
3. **Locations:** cluster scene `setting` strings with an LLM pass (`pictureBook.cluster-locations`:
   "which of these settings are the same place?"). It is offered as a reviewable suggestion, not
   applied blindly. Existing landscape images are imported as unapproved renders.
4. **Compositions:** one per scene.
   - Actors come from `scene.characters` in order: first HERO, second SUPPORTING, rest SUPPORTING.
     Placement is defaulted by `PbLayoutUtil`.
   - Apparel: each `sceneIndex` tag becomes an explicit `sceneActor.apparel` on that scene, and
     carry-forward covers the scenes after it (§6.2).
   - `configOverride` is moved.
   - `sdConfigOverride.description` / ChapBook `sdPrompt` + lock → `promptOverride`/`promptLocked`.
   - `source=MIGRATED`.
5. **Renders:** the existing composite image is imported as an approved COMPOSITE artifact
   (`DONE_UNVERIFIED`), with a synthetic plan artifact `{planVersion:"migrated/1", …known fields}`.
6. **Dependencies:** bindings are created to the imported portraits and locations, with refHashes
   computed at migration time. A later edit marks the scene stale correctly.

PB1 note-blob books (`data.note` scenes) first go through the existing PB2 dual-write and backfill
path (`olio.pb.scene`), then the steps above.

### 28.1 Retiring PB1 and the old ChapBook render path: disable, verify, then remove

Ratified 2026-10-09: PB1 is **disabled** in favour of V3, and it is **not removed** until V3 is known
to work. Removal is a post-completion activity. The same lifecycle applies to the old ChapBook
render path.

| Stage | What happens | PB1 / old-path code | Data |
|---|---|---|---|
| **1. Coexist** (Phases 0 to 2 in §30) | V3 is built behind a switch. PB1 stays the default | Live | Untouched |
| **2. Disable** (when V3 reaches parity for a product) | V3 becomes the only path for new books and for opening existing books. PB1 entry points (`/scene/{id}/generate`, `prepare-images`, PB1 settings writes, wizard steps 4–5; for ChapBook, `chap-book/render` and `scene/{id}/generate`) return a clear "replaced by V3" response. The Ux no longer calls them | **Kept in the build**, unreachable unless re-enabled | PB1 data is **read** by migration and never written, deleted or reshaped. V3 records are written alongside it |
| **3. Verify** | V3 runs as the only path. The exit checks are below | Kept | Kept |
| **4. Remove** (post-completion) | Delete PB1 and old-path code and tests in a dedicated change; drop dead fields through the off-by-default column-drop property only | Removed | PB1-only data is cleaned up by an explicit, logged admin task. Never as a side effect |

**The switch.**
- A boot-pinned setting per product: `picturebook.renderPath` / `chapbook.renderPath` =
  `v1` | `v3`, read once at start-up, with the Ux told through the feature manifest.
- **Rollback** = set it back to `v1` and restart. This works because PB1 data was never modified;
  the cost is that edits made in V3 are not reflected back into PB1.
- This is deliberately not a per-org runtime flag. The previous PB2 flag (`picturebook.v2`, false
  in the deployed `web.xml`) is why the PB2 graph never received data. A switch that defaults to
  the old path keeps the new one unexercised. So the switch goes to `v3` on the day Stage 2 starts.
  **[verified 2026-10-09]** `picturebook.v2` no longer exists anywhere in `src/main`: it was
  retired in W4 (2026-10-07) and graph recording is now unconditional (`TestPictureBookWorkflow`
  class comment, `TestPictureBookFull.java:3044`). The `renderPath` switch is therefore the only
  flag in this area; do not reintroduce a graph-recording flag alongside it.

**Verify-stage exit checks** (all with real tests and visual inspection):
1. Scenarios A–G (notes §70) pass on the Docker stack.
2. Every existing book in the test databases opens and migrates in V3 with no errors, and its
   images still show.
3. A ChapBook created before V3 renders a page in V3 with its locked prompt intact.
4. Two consecutive weeks of normal use with no rollback.
5. You sign off.

### 29. Backward compatibility defaults

| Missing | Default |
|---|---|
| Style | migrated per §28.1, else deployment defaults with STANDARD preset and SQUARE format |
| Composition | the lazy per-scene fallback (§9.1); if the LLM is unavailable, a MIGRATED-style composition from scene fields, flagged for re-compose |
| Location | text-only (`LOCATION_TEXT_ONLY`) |
| Camera | MEDIUM, EYE_LEVEL; WIDE when there are more than 3 actors |
| Portrait | render on first use; while missing → `ACTOR_TEXT_ONLY` |

### 30. Phasing

Phases follow the notes' §60–68, re-cut for AM7. Each phase is shippable and ends with real tests
(live backend, `ensureSharedTestUser()`, LLM/SD tests gated and single-worker, visual inspection
for image output).

| Phase | Scope | ChapBook impact | Exit criteria |
|---|---|---|---|
| **0 — Hygiene** (current code) | G-15 override leak, G-18 explicit width/height, G-24 template override, G-26 null guards, G-8 landscape flag, G-11 landscape failure non-fatal; from the gap doc's Appendix A "Other": `ByteModelUtil` instead of raw `FIELD_BYTE_STORE` writes, and run closure on exception; **Kontext Deprecate stage** (§37.3, ratified) **[Progress 2026-10-09, see `PictureBookV3Implementation.md` §2/§5.3: G-15, G-18, G-24 (Option A — override stays on the landscape slot), G-26, G-8, G-11, `ByteModelUtil`, run closure DONE with live tests. Additions not listed here: G-11 adds `landscapeFailed`/`landscapeError` to `olio.pictureBookResult` and a text-only txt2img fallback for a classic composite with neither landscape nor portraits; G-8 bumps `PB_PIPELINE_VERSION` to `pb2/3` because the schema default is outside the inputHash (§11.1 drift). Kontext Deprecate and the ChapBook column pending.]** | G-25 style, negative and hires; G-32 book settings saved, page overrides not flattened | The gap doc's §4 step 1 plus the notes' §60 criteria; each fix has a test, PB and ChapBook |
| **1 — Graph-first rendering + review** | Rendering through graph execution (PbNodeExecutor converged with v1 behaviour, fixing the G-30 divergences), live drift detection (§11.1), review status, auto-approve toggle (§10), async render jobs, restart recovery. V3 runs behind the `renderPath` switch (§28.1 Stage 1) | ChapBook pages become graph nodes with artifacts; same jobs and review | Leave the page → render continues; restart → Resume; a charPerson edit shows the affected scenes |
| **2 — Style, model resolution, compiler core; ChapBook goes V3** | `olio.pb.style` with inheritance and lock, `ModelResolver`, capabilities/presets resources, models-by-task route, style editor, estimate, `RenderPlan`, TEXT_ONLY strategy, `SdxlCompiler`, plan inspector; **Kontext Disable stage** (§37.3) | **ChapBook is the first product switched to V3** (TEXT_ONLY, zero actors), with up-front composition from the existing continuity composer. **ChapBook old path disabled (§28.1 Stage 2)** | One model policy across reimage/PB/ChapBook/chat; a missing model is reported before rendering; ChapBook verify checks start; chat scene renders through the illustration service (§24.1) |
| **3 — Locations** (world-scoped) | Location/variant models, LOCATION nodes, `approvedRender`, library Ux, approve/lock, clustering migration, background-first for TEXT_ONLY/DIRECT; **POI link** (§7.2 rules 1–3, 5): map-free `olio.pointOfInterest` + `olio.store` per resolved location in book worlds, name-match in game worlds, `PointOfInterestUtil.findByName`, `PbOrphanUtil`/`PbHealthUtil` repair of a dangling `poi` | Optional locations on ChapBook pages | Scenes 3/7/12 share one approved tavern; series chapters share it; a location failure doesn't fail scenes; every resolved location has a POI with a store, and deleting the world removes both |
| **4 — Composition + wardrobe** | Composition/actor/element models (`sceneElement.item` FK, §7.2 rule 4), up-front `pb.compose` job in story order with lazy per-scene fallback and "Quick start" (§9.1, ratified) fed the location's POI store items (§9), layout util, board Ux, `Flux2Compiler`, degradations, wardrobe alternates and carry-forward (§6) | Composition model replaces `sdPrompt`/`promptLocked` (already switched in Phase 2) | Any number of actors; prompt shown = prompt sent; nothing dropped silently; outfit change persists across scenes; a promoted element is an `olio.item` in the POI store and is reused by name in a later scene at the same location |
| **5 — Multi-character strategies** | **Spike first:** the graph compiler against the live backend (§38.5: IP-Adapter and FLUX.2 base graphs captured, `<region:>` vs own inpaint graph decided, text-frame QA outputs, timings on .39), Swarm mask inpaint, and FLUX.2 base + SDXL inpaint style coherence. Then `WorkflowCompiler`, SEQUENTIAL_INPAINT, REGIONAL_PROMPT, HARMONIZE and targeted regeneration. Parameter names and the raw-graph contract are already verified (§36.2, §38.1); what remains unmeasured is behaviour and timing | n/a (no actors unless G-35 is enabled) | Scenario B: 4 characters all rendered and identifiable on visual inspection; GPU memory within limits |
| **6 — Variants** | Variant batches, strip, history, retention sweep | Same | Scenario F |
| **7 — PictureBook goes V3** | Book workspace Ux replaces wizard steps 4–5. **PB1 disabled (§28.1 Stage 2)** | — | Scenarios A–G; PictureBook verify checks start |
| **8 — Visual QA** | `VlmQaProvider` first (default, `valkyriesys/eudaimonia-dryad3-vision` on `.42`, §17), then the Swarm/Comfy detection graph as `SwarmQaProvider` fallback (identity `UNAVAILABLE` until the §36.7 item 4 spike), provider selection by model availability, QA Ux, QA-gated auto-approve | QA on ChapBook pages (detection is mostly n/a; checks gross defects only) | Missing-character detection on a deliberately broken render, through each provider |
| **9 — Cover & book art** | Cover/title/chapter/series compositions and reader integration | ChapBook cover | A book and a ChapBook each have an approved cover |
| **10 — Removal** (post-completion) | §28.1 Stage 4 for PB1, after the verify checks and your sign-off; **Kontext Remove stage** per the §37.4 inventory | Same for the old ChapBook path | Code removed; nothing in the build references PB1 entry points or `MODE_KONTEXT` |

Phase 0 can start immediately and does not depend on the rest. Phases 3 and 4 can overlap. Phase 5
is gated on its spike. Phase 7 can move earlier if single-character and two-character books are
acceptable on DIRECT_REFERENCE with transparent degradations before Phase 5 lands.

### 31. Testing strategy (AM7-specific)

- **Unit (no backend):**
  - inheritance resolution and lock enforcement
  - strategy selection tables
  - each compiler's output for fixed plans (golden strings)
  - layout mapping
  - degradation generation
  - model resolver against a stubbed model list
  - the retention selector

  The illustration package is designed with pure functions at these seams, so they need no SD/LLM.
- **Integration (Objects7 JUnit, live DB/LLM/SD, gated by `LlmTestGate` / `SdTestGate`):**
  - compose → plan → render per strategy
  - stale propagation after a direct charPerson PATCH (proves §11.1)
  - restart recovery (simulate by marking a run RUNNING)
  - cancel mid-stage
  - artifact persistence through `ByteModelUtil`
- **Visual regression:**
  - a canonical fixture set: 1 / 2 / 4 characters, a crowd, a repeated location, 3 styles;
  - use the user's real test content (`feedback-use-real-test-content`);
  - outputs exported and **looked at**;
  - QA detection results recorded as a trend, not a pass gate.
- **E2E (Playwright, Docker stack):** the notes' §69 flow with `ensureSharedTestUser()`, LLM/SD
  specs gated and `--workers=1`.
- **Timings are never test thresholds (Stephen, 2026-10-09: "give them ample time as these are
  small devices so it's expected they'll take longer").** No test asserts that a render, a
  compose step or a QA call finished within N seconds, and no test fails a healthy-but-slow run.
  Concretely:
  - Integration and E2E waits use long ceilings and fail only on an actual error or on **no
    progress**: per stage ≥ 15 min, per multi-stage scene ≥ 30 min, per Generate All fixture
    ≥ 60 min (the nginx `proxy_read_timeout` is already 3600 s for the same reason). A wait loop
    polls the run/job status and the Swarm progress frames; a timeout fires only when nothing
    has advanced for the whole ceiling.
  - JUnit classes that render carry a `@Timeout` at least double the summed ceilings; Playwright
    render specs set `test.setTimeout` the same way rather than relying on the suite default.
  - Measured `durationMs` is **logged and recorded** (§23, §32, and the Q14 calibration record),
    never compared against a bound. A regression in speed is read from the trend, like QA results.

### 32. Observability

Each run, node execution and artifact records the notes' §71 fields:
- the plan artifact (strategy, models, preset, canvas, inputs);
- the artifact (`seed`, `backend`, `generatorRequest`, dimensions, duration — add `durationMs`;
  **[verified 2026-10-09]** `artifactModel.json` has `seed`, `backend` (`SDAPIEnumType`),
  `generatorRequest`, `imageWidth`/`imageHeight`, `byteLength`, `contentHash`, `sdConfigSnapshot`
  and `backendGraph` today, and **no** duration field, so `durationMs` is a new schema field);
- the node (`lastError`, `lastRunAt` — both exist in `nodeModel.json`);
- the run (`executedNodeCount`, `failedNodeCount`, `runStatus`, `startedAt`, `completedAt`,
  `error` — all exist in `runModel.json`).

LLM steps (compose, location description) go through LiteLLM, so they are Langfuse traces;
put the run objectId in the trace metadata so one book render can be followed across LLM and SD.

---

## Part VI — Risks and open decisions

### 33. Risks

| Risk | Mitigation |
|---|---|
| Inpaint/region support on the installed SwarmUI differs from assumptions | **Parameter names are now verified** against SwarmUI 0.9.8.3 on .39 (§36.2): the mask-inpaint, regional and segment groups all exist. **Behaviour is not** — none of them has been exercised with our images. Phase 5 spike before any commitment; DIRECT_REFERENCE + text degradation remain the working fallback |
| The installed Comfy backend lacks assets the strategies assume. **After the 2026-10-09 install** still absent: inpaint checkpoint, ControlNet, Kontext (retired, §37); unverified: InsightFace runtime, IP-Adapter/SAM2 weight auto-download (§36.3, §36.5) | SEQUENTIAL_INPAINT uses a base SDXL checkpoint with `useinpaintingencode`; identity QA is UNAVAILABLE until the §36.7 item 4 spike passes; the model resolver reports "not installed" from `ListModels` rather than assuming |
| The raw-graph carrier is a hidden Swarm pseudo-parameter (`comfyworkflowraw` is not in `ListT2IParams`, is marked `// TODO` in the source, and needs `comfy_dynamic_custom_workflows`) and the generated graph's node ids/shape are an implementation detail of the installed `0.9.8.3` build (§38.1) | Pin the Swarm version in `capabilities.json`; the adapter re-checks `ComfyGetGeneratedWorkflow` output shape (reserved ids `4–30`, `9` = final save) and the `object_info` catalogue at startup and fails with `GRAPH_INVALID` rather than guessing; the inline `PARSED%` form of `comfyuicustomworkflow` is the documented fallback carrier on the same fill path; the plain-parameter path (no raw graph) stays for PORTRAIT / LOCATION / TEXT_ONLY so a Swarm upgrade cannot take the whole product down |
| SDXL inpaint over FLUX.2 base breaks style or identity | Spike with visual inspection; alternative is a FLUX-family inpaint workflow in the Comfy backend |
| Timings in this design were measured on a different GPU | §15/§18 figures (40 s/reference, preset estimates) came from the Strix Halo iGPU; re-measure on .39 before the estimate feature ships (§36.7, Q14) |
| GPU time multiplies (variants × stages × scenes) | Presets, estimate before Generate All, per-server semaphore, reuse of approved assets, DRAFT default for first pass |
| Graph size grows (stage nodes per composition) | Stage nodes rematerialized per plan rather than accumulated; retention sweep; graph queries are already indexed by status |
| Live drift scan cost | Book-scope bindings only, 30 s cache, PB-endpoint `touch` for the common case |
| LLM composition quality (wrong roles/places) | Coarse vocabulary, isMeaningful guards, unresolved names visible not dropped, user board for correction, `userEdited` protection |
| QA detection false alarms | QA never blocks or deletes; warnings only; trend-tracked |
| Migration misclusters locations | Clustering is a suggestion the user accepts, never auto-applied |

### 34. Decisions needed (Stephen)

**Ratified 2026-10-09:**
- **D1/D2:** hash-based versioning on the PB2 graph (current state in records, history in
  artifacts).
- **D3:** reuse `olio.apparel` for outfits, **plus a wardrobe of alternate apparel** for clothing
  changes (§6.1–6.2).
- **Location scope:** the book's Olio world. That makes locations book-local for standalone books
  and series-wide for chapters (§7.1).
- **Approval:** Approve pins, and auto-approve pins too, behind a clear on/off toggle (§10 rule 5).
- **PB1:** disable in favour of V3; remove only after V3 is verified (post-completion). The same
  applies to the old ChapBook render path (§28.1).
- **ChapBook:** moves with PictureBook through every phase and is the first product switched to V3
  (§24, §30).
- **Kontext (Q4): retire.** Deprecate in Phase 0, disable in Phase 2, remove in Phase 10, on the
  §28.1 lifecycle; `ChatService.java:1490` `resolveMode(sdConfig, true)` → `false` in Phase 0.
  No `KontextCompiler` is built. Inventory and stages in §37.
- **Comfy workflows (Q5, Q13): built dynamically by V3, not hand-authored.** V3 generates the
  Comfy graph per render from the `RenderPlan` and submits it raw; the `olio.pb.workflow` /
  `olio.pb.node` graph is a proxy (mirror) of that Comfy graph. No saved Swarm workflows to
  author or version. Contract and verified facts in §38.
- **Auto-approve (Q3, scope): one general switch.** `olio.pb.book.autoApprove` only; no
  per-asset-type breakdown (§10 rule 5). The *default value* is still item 3 below.
- **Visual QA provider (Q12): the vision LLM is the default**, model
  `valkyriesys/eudaimonia-dryad3-vision` on `.42`; when that model is not on the resolved route,
  fall back to the Comfy detection graph (§17, Phase 8).
- **Olio linkage (item 2): use the POI link** — "it will allow for object/item references." My
  drop-it recommendation is withdrawn; the POI's `store.items` is the home for scene objects.
  Map-free in book worlds (nullable `location`, `east`/`north` −1), linked to the placed POI in
  game worlds; `olio.pb.sceneElement.item` FK; rules in §7.2, §8, §9.
- **When composition runs (item 1): up front** — its own `pb.compose` job in story order after
  extraction, whole-book location pass, lazy per-scene fallback recorded as a degradation,
  "Quick start" toggle for early DRAFT images (§9.1).
- **Olio events/interactions (item 15): skipped for V3.** Sequence stays `sceneIndex` +
  composition graph; a future events → scenes importer is the bridge if ever needed.

**Still open:**
1. ~~**When composition runs**~~ **Ratified 2026-10-09: up front** (see the block above and §9.1).
2. ~~**Olio linkage**~~ **Ratified 2026-10-09: use the POI link** (see the block above and §7.2).
3. **Auto-approve default value:** off (proposed) or on. (The single-switch shape is ratified —
   see the block above.)
4. ~~**Kontext**~~ **Ratified 2026-10-09: retire** (see the Ratified block and §37).
5. ~~**Comfy workflows**~~ **Ratified 2026-10-09: dynamically built** (see the Ratified block and
   §38). The earlier fact stands — the only saved workflow on .39 is `Examples/Basic SDXL` — but
   V3 does not add to it.
6. ~~**Face identity QA node install**~~ **Done 2026-10-09 (by you)** — see item 10/11. What is
   still open is the *approach*, which is Q12.
7. **Default preset — the question, plainly:** when a user presses Generate All on a brand-new
   book and has not touched the preset selector, which of §18's three presets (DRAFT: half-size,
   low steps, 1 variant, no QA / STANDARD: full size, default steps, 2 variants, QA on the
   canonical variant / FINAL: full size, high steps, 4 variants, upscale, QA on all) should be
   pre-selected? Recommended **DRAFT**, because the first pass of a book is for checking
   composition and casting, and FINAL on a 40-scene book is 4 × the GPU time before anyone has
   looked at anything.
8. **Verify-stage bar — the question, plainly:** §28.1 keeps the old PB1 render path (and the old
   ChapBook render path) in the tree but disabled while V3 is used, and deletes it only after a
   "verify" period. What ends that period? Proposed: **two weeks of V3 in use with no rollback to
   the old path, plus your sign-off**. Alternatives are a fixed date, or a checklist (every
   feature in §28.1's parity table exercised once on V3).

**New, from the grounding pass (2026-10-09):**

9. ~~**Missing gap document.**~~ **Resolved 2026-10-09:** `PictureBookImageGenerationDesign.md`
   is in the tree. Its numbering differed from what this design cited; reconciled in §36.8.
10. ~~**Approve the node-pack install.**~~ **Done 2026-10-09 (by you):** the backend now reports
    `ipadapter`, `cubiqipadapterunified`, `sam2` and `yolov8` features; 51 new Comfy node types
    (§36.3). Nothing has been *rendered* with them yet.
11. ~~**File drops that no API can do.**~~ **Done 2026-10-09 (by you):** `face_yolov8m.pt` is
    listed by `yolomodelinternal` and `CLIP-ViT-H-14-laion2B-s32B-b79K` by `ListModels` ClipVision.
    IP-Adapter weights are not a file drop after all — Swarm's `useipadapter` dropdown
    auto-downloads them on first use. **Still absent and still optional:** an SDXL inpaint
    checkpoint, any ControlNet model, Kontext (§37).
12. ~~**Vision-LLM QA as the first provider.**~~ **Ratified 2026-10-09:** vision LLM is the default,
    model `valkyriesys/eudaimonia-dryad3-vision`; Comfy detection graph is the fallback when that
    model is not available (§17, Phase 8).
13. ~~**Who authors the Comfy workflows**~~ **Resolved 2026-10-09:** nobody authors them; V3
    generates them (§38). The Phase 5 spike builds the graph builder, not saved workflows.
14. **Re-measure on .39 — which timings, and what the question is.** The timings are the
    **seconds-per-render figures that feed the "estimate before Generate All" feature** (§18:
    scenes × stages × variants × `secondsPerStepPerReference`). The only numbers in the repo are
    `~40 s per reference image at 1024 px / 4 steps` and `706 s for a 3-reference, 24-step FLUX.2
    request` (`flux2Defaults.json:22-26`, `SceneCompositeUtil.java:107-111`, KI-59), and both were
    measured on the Strix Halo iGPU, not on `.39`. The one `.39` data point so far is from the §38
    probe: a 4-step 512×512 SDXL render took **19.6 s** end to end, and an identical re-run was
    2.5 s (Comfy's node cache), which is why a single sample is not a calibration. The question
    is **how the estimate gets its numbers**: (a) a Phase 5 spike measures BASE + ACTOR_REGION
    + HARMONIZE + UPSCALE per model family on `.39` once and the results are typed into
    `capabilities.json`; or (b) the feature ships with a per-server calibration record
    (`olio.sd.config` or a sibling) that starts from (a)'s numbers and is updated from every
    completed job's measured `durationMs` (§23), so the estimate tracks the actual server, model
    and step count over time. **Recommended: (b), seeded by (a).** It costs one small record and
    an update at job completion; (a) alone goes stale the first time a model or GPU changes.
    **Answered in part 2026-10-09:** whatever the estimate's source, timings are **never test
    thresholds** — tests and the job watchdog give renders ample time because these are small
    devices and are expected to be slow (§31 last bullet, §20 "reachable but slow"). Still open:
    (a) vs (b) for the estimate's source.
15. **Olio events + interactions for sequences and state changes — considered 2026-10-09;
    ratified 2026-10-09: skipped for V3** ("skip 15 for v3"). The analysis is kept as the record
    of why, and of where they would come back (an events → scenes importer).
    Raised with: "the model supports sequences and state changes with events + interactions. At
    this point you've built a separate dedicated model so I'm not sure it's worth using."
    What the Olio model offers, grounded in `eventModel.json` / `interactionModel.json`:
    `olio.event` is a time-bound record (`eventStart`/`eventProgress`/`eventEnd`, `timeType`,
    `inProgress`, `state` = `ActionResultEnumType`) with **sequence** (`precedingEvents`,
    `subsequentEvents`, parent/child via `EventUtil.getChildEvents`), **cast** (`actors`,
    `participants`, `observers`, `influencers`, `groups`), **state change** (`entryTraits` →
    `exitTraits`, `data.trait` participations), place (`location`, `realm`), `epoch`, and a list of
    `olio.interaction` — each an actor/interactor pair with claim, instinct, personality,
    statistic, role, reason, alignment, outcome and threat on both sides. Two things make it a
    poor fit for what V3 needs, and one makes it unnecessary:
    - **Its sequence is clock-driven.** `EventUtil` is built around epochs and time increments
      (`getLastEpochEvent`, `findNextIncrement`, `getCreateIncrement`, `edgeHour/Day/Month`) and
      `location`/`realm`; book worlds have no map, no realm and no usable clock
      (`BookWorldInitializationRule.java:119`: "No location and no realm: a book world has
      neither"; `ctx.clock()` is unusable there, §7.2). A book world does get one root
      `CONSTRUCT` event (`BookWorldInitializationRule.java:111-123`), so the container exists —
      but every child-event helper assumes the increment machinery.
    - **Its state is behavioural, not visual.** `entryTraits`/`exitTraits` and the interaction
      fields describe disposition, claim and outcome — what the simulation needs to decide what a
      character does next. V3's per-scene state is *what the picture shows*: which outfit
      (`sceneActor.outfit`), pose/expression/placement (`sceneActor`), which location variant
      (`composition.locationVariant`), which items (`sceneElement.item`). None of that has a home
      on `olio.event` or `olio.interaction`, so adopting them would still require the composition
      records; the events would be a second, parallel structure.
    - **V3 already has sequence and per-scene state, in the dedicated model.** Order is
      `olio.pb.scene.sceneIndex` (+ `sceneNode` for the workflow graph); per-scene state is the
      `olio.pb.composition` → `sceneActor`/`sceneElement` graph (§8), and the one cross-scene
      state change V3 cares about — what a character wears — is the outfit carry-forward by
      `sceneIndex` (§6.2), with staleness/dependency on the versioned graph (§1, §11). That is
      the "separate dedicated model" — it is what the composer writes and what the render plan
      (§12) and QA (§17) read.
    **Decision: events/interactions are not adopted in V3.** No `olio.pb.scene.event` FK, no
    interaction records written by the composer. Keep the one place they already touch PB: the
    world's root `CONSTRUCT` event that `BookWorldInitializationRule` creates stays as is.
    **Where they would come back:** if a *simulated* story (a game world with real events) is ever
    illustrated, the bridge is an **events → scenes importer** — one `olio.pb.scene` per selected
    `olio.event`, cast from `actors`/`participants`, location from the event's `location`'s POI
    (§7.2 rule 2), `action`/`mood` text from the interactions' outcomes. That is a V4 input
    adapter on the book side, not a change to the V3 model. Precedent for map-free event use
    exists (`ChatEventUtil` binds one `INTERACT` event per chat under `~/Events`, no location, no
    epoch), so the importer would not need the increment machinery either. Noted for the §35
    traceability table as "not mapped".

### 35. Traceability: notes → this design

| Notes § | Design § |
|---|---|
| 5 Scene intent model-independent | 8, 12, 14 |
| 7 Book Style, 33, 52 inheritance | 4 |
| 8 Character model / versioned portraits | 5, 1 (D2) |
| 9 Outfit | 6 (D3) |
| 10–12 Locations, variants, approval | 7, 10 |
| 13–20 Composition, camera, actors, roles, priority, placement, board, elements | 8, 9, 26 |
| 21 Render plan | 12 |
| 22, 48 Prompt architecture | 14, 9 |
| 23–26, 78 Strategies, sequential/regional | 13, 15 |
| 27 Targeted regeneration | 22 |
| 28 Variants | 23 |
| 29–32, 57 Versioning, dependency, staleness, approval | 1, 10, 11 |
| 34 Model selection | 16 |
| 35–36, 74, 76 Backend abstraction, capabilities | 2, 15 |
| 37–40 Async, jobs, cancel, failure | 20, 21 |
| 41 Visual QA | 17 |
| 42–47 Ux, transparency, auto composition | 19, 26, 9 |
| 49 Cover/book art | 25 |
| 50–51 ChapBook, shared service | 24, 2 |
| 53–54 Resolution, presets | 4, 18 |
| 56 Data ownership | 4 (one style store), 1 |
| 58–59 Migration, compatibility | 28, 29 |
| 60–68 Phases | 30 |
| 69–71 Testing, observability | 31, 32 |
| 72–73 Performance, VRAM | 13, 18, 21, 33 |

**Where this design departs from the notes:**
- Versions are content hashes plus artifact chains, not version-record copies (D2).
- Outfit reuses `olio.apparel` (D3).
- Review state is split from freshness (§10).
- Composition is a separate LLM step rather than part of extraction (§9).
- A Phase 0/1 is added: hygiene, plus "graph-first rendering" before the new models, because V3
  depends on one rendering path.
- Olio `olio.event` / `olio.interaction` are **not** used for scene sequence or per-scene state
  (§34 item 15); `olio.pb.location.poi` **is** used, for object/item references (§7.2).

---

## Part VII — Grounding pass (2026-10-09)

### 36. What was checked, against what, and what it changes

This section records the facts the rest of the design now cites. Everything here was read from the
live servers or the working tree on 2026-10-09; nothing was implemented. Raw probe output is in
`C:\tmp\swarm_session.json`, `C:\tmp\swarm_t2iparams.json` (259 params) and
`C:\tmp\comfy_object_info.json` (1037 nodes) on the dev machine; they are scratch, not repo files.

#### 36.1 SD server `192.168.1.39:7801` — inventory

SwarmUI **0.9.8.3.GIT-d9ecb52d**, one backend (`comfyui_selfstart`, id 0, GPU 0). Anonymous
sessions get user `local` with permission `*` (includes `install_features`, `download_models`,
`comfy_edit_workflows`, `restart_backends`), so V3's adapter needs no credential on this host.

| Type | Installed |
|---|---|
| Checkpoints | `flux2Klein_9b`, `flux2_dev`; SDXL family: `sd_xl_base_1.0`, `chromagenIL_somni`, `cyberrealisticPony_v160`, `juggernautXL_ragnarokBy`, `lustifySDXLNSFW_endgame`, `ponyRealism_V22`, `realmixXL_v10`; SD1.5: `chilloutmix_Ni`; other: `Z-Image-Turbo-FP8Mix`, `chroma_v10HD`, `krea2TurboOfficialComfy` |
| VAE | `Flux/flux2-vae`, `QwenImage/qwen_image_vae` |
| Clip | `qwen3vl_4b`, `qwen_3_8b` |
| LoRA | 10 |
| Saved Comfy workflows | `Examples/Basic SDXL` only |
| ClipVision | `CLIP-ViT-H-14-laion2B-s32B-b79K` **(added 2026-10-09 afternoon)** |
| YOLO | `face_yolov8m.pt` (`yolomodelinternal`) **(added 2026-10-09 afternoon)** |
| Backend features (`ListBackends`) | `comfyui, refiners, controlnet, endstepsearly, seamless, video, variation_seed, freeu, yolov8, comfy_latent_blend_masked, comfy_just_load_model, comfy_loadimage_b64, comfy_saveimage_ws, sam2, ipadapter, cubiqipadapterunified, cubiqipadapter` **(sam2/ipadapter/yolov8 added 2026-10-09 afternoon)** |
| **Still absent** | Kontext, any inpaint checkpoint, ControlNet models (`ControlNet` list is `[]`; the `controlnet` *feature* and the SAM2 autosegment preprocessors are present), Embeddings, Redux, GLIGEN weights |

The morning probe (before the install) had no ClipVision, no YOLO weights, no `sam2`/`ipadapter`
features. Consequences already applied: §14 (`KontextCompiler` not built; Kontext retired, §37),
§16 (model resolver must report "not installed" from `ListModels`, not assume), §33.

#### 36.2 Swarm API and parameters [verified against `ListT2IParams` and the Swarm source]

Routes V3 will use: `GetNewSession`, `GenerateText2Image` / `GenerateText2ImageWS` (with
`comfyworkflowraw`, §38), `ComfyGetGeneratedWorkflow`, `/ComfyBackendDirect/object_info`,
`ListModels`, `ListT2IParams`, `ComfyInstallFeatures`, `DoModelDownloadWS`. `ComfyListWorkflows` /
`ComfySaveWorkflow` / `ComfyReadWorkflow` exist but are **not used** (no saved workflows, §38).
`InterruptAll` is present but V3 cancels between stages (§21).

What `SWUtil`/`SWTxt2Img` send today (confirmed by code read): `model, prompt, sampler, scheduler,
refinersampler, refinerscheduler, negativeprompt, images, steps, cfgscale, seed, height, width,
refinercfgscale, refinerupscale, refinermodel, refinersteps, refinermethod, refinerupscalemethod,
refinercontrolpercentage, initimage, initimagecreativity, promptimages` plus `session_id`.
`SDAPIEnumType.COMFY` exists but is inert; `PbNodeExecutor` constructs
`new SDUtil(SDAPIEnumType.SWARM, swarmServer)` (`:188`, `:473`, `:563`).

Parameters that exist on the installed build and that V3 adds (all names exact, defaults from the
server):

| Group | Parameters |
|---|---|
| Mask inpaint | `initimage`, `maskimage`, `initimagecreativity` (0.6), `maskshrinkgrow` (8), `maskblur` (4), `maskgrow` (0), `initimagerecompositemask`, `useinpaintingencode`, `maskbehavior`, `unsamplerprompt` |
| Regional | `globalregionfactor` (0.5), `regionalobjectcleanupfactor`, `regionalobjectinpaintingmodel`, `maskcompositeunthresholded`. Prompt syntax (Swarm `docs/Features/Prompt Syntax.md`): `<region:x,y,width,height,strength> prompt` (fractions of the canvas) and `<object:x,y,width,height,strength,strength2> prompt`, where `object` also inpaints back over the region with `strength2` as creativity — the latter is the natural carrier for SEQUENTIAL_INPAINT's per-actor pass |
| Segment (auto-mask) | `segmentmodel`, `segmentsteps` (40), `segmentcfgscale` (7), `segmentmaskblur` (10), `segmentmaskgrow` (16), `segmentmaskoversize` (16), `segmentthresholdmax`, `segmentsortorder`, `segmentapplyafter`, `segmenttargetresolution`, `savesegmentmask`. `<segment:text,creativity,threshold>` uses CLIP segmentation by default (Swarm auto-downloads `clipseg-rd64-refined` on first use); a `yolo-` prefix selects a YOLOv8 model (`face_yolov8m.pt` installed, §36.5; generated subgraph captured in §38.1) |
| Batch / variants | `images` (1–10000), `batchsize`, `variationseed`, `variationseedstrength` |
| Canvas | `width`/`height` (64–16384), `fluxguidancescale` (3.5), `refinerupscale`, `refinermethod`, `removebackground` |
| References | `promptimages`, `enablereferencelatents` (`none` / `index_timestep_zero` / `index`), `textencodedimage` (`auto` / `none` / `small` / `large`), `usereferenceonly` (note: "prevents Batch from being used properly") |
| IP-Adapter **(present since the afternoon install)** | `useipadapter` dropdown: `None`, `LIGHT - SD1.5 only`, `STANDARD`, `VIT-G`, `PLUS`, `PLUS FACE (portraits)`, `FULL FACE - SD1.5 only`, `FACEID`, `FACEID PLUS - SD1.5 only`, `FACEID PLUS V2`, `FACEID PORTRAIT (style transfer)`, `FACEID PORTRAIT UNNORM - SDXL only`; `ipadapterweight` (1), `ipadapterstart` (0), `ipadapterend` (1), `ipadapterweighttype` (`standard` / `prompt is more important` / `style transfer`). Swarm **auto-downloads the adapter weights on first use** of each dropdown value; the installed ClipVision is the ViT-H encoder these SDXL adapters expect. The `FACEID*` entries additionally need InsightFace at runtime (node `IPAdapterInsightFaceLoader` exists; the Python package and its models are unverified until a FACEID run) |
| ControlNet | `controlnetmodel` (none installed), `controlnetstrength` (1), `controlnetpreprocessor`: `None`, `Segment Anything 2 Global Autosegment base_plus / large / small` — i.e. SAM2 is exposed as a preprocessor, not as a segment model |
| Routing / provenance | `comfyworkflowraw` (hidden; the raw-graph carrier V3 uses, §38.1), `comfyuicustomworkflow` (by saved name or `PARSED%…`; unused), `exactbackendid`, `webhooks`, `forwardrawbackenddata`, `outputintermediateimages` |

**Not yet done:** none of these has been sent with a real image. The Phase 5 spike (§30) is still
required for behaviour and timing; it is no longer required for names.

#### 36.3 ComfyUI backend — nodes

**After the 2026-10-09 install: 1088 node types** (1037 before; 51 new). Usable built-ins for V3:
`MediaPipeFaceLandmarker`, `MediaPipeFaceMask`, `RTDETR_detect`, `SAM3_Detect`,
`SAM3_TrackToMask`, `SwarmClipSeg`, `SwarmYoloDetection` (now with `face_yolov8m.pt`), the Swarm
mask set (`SwarmMaskGrow`, `SwarmMaskBlur`, `SwarmMaskThreshold`, `SwarmMaskBounds`,
`SwarmSquareMaskFromPercent`, `SwarmCleanOverlapMasks`, `SwarmExcludeFromMask`,
`SwarmLatentBlendMasked`, `SwarmImageCompositeMaskedColorCorrecting`), `DifferentialDiffusion`,
`InpaintModelConditioning`, `ControlNetLoader`, `CLIPVisionLoader`, `StyleModelLoader`,
`GLIGENLoader`.

**New from the install** (cubiq `ComfyUI_IPAdapter_plus` and a SAM2 pack): `IPAdapter`,
`IPAdapterAdvanced`, `IPAdapterUnifiedLoader`, `IPAdapterUnifiedLoaderFaceID`, `IPAdapterFaceID`,
`IPAdapterInsightFaceLoader`, `IPAdapterRegionalConditioning`, `IPAdapterPreciseComposition`,
`IPAdapterStyleComposition`, `IPAdapterTiled`, `IPAdapterEmbeds`/`IPAdapterEncoder`/
`IPAdapterCombineEmbeds`, `IPAdapterWeights`, `PrepImageForClipVision`; `DownloadAndLoadSAM2Model`,
`Sam2Segmentation`, `Sam2AutoSegmentation`, `Sam2VideoSegmentation`. The SAM2 node name says what
it does: the SAM2 weights are **not** on disk yet and download on first use, like the IP-Adapter
weights.

Still absent: a standalone face-*analysis* pack (ArcFace embedding/compare). So the QA provider
can do **detection** (face/person where the composition said) via YOLO/MediaPipe/SAM, and
**identity-conditioned rendering** via IP-Adapter FaceID, but a post-render "is this the same
face" *score* still needs either an InsightFace embedding node authored into a QA workflow (the
`IPAdapterInsightFaceLoader` node proves the dependency is loadable, if the Python package is
present) or the vision-LLM provider (§34 Q12). §17 identity QA moves from UNAVAILABLE to
"needs a spike".

#### 36.4 LLM server `192.168.1.42:11434` — models

Re-probed 2026-10-09 afternoon: Ollama **0.35.0**, nothing loaded (`/api/ps` empty). Models (name
| family/size/quant | GB):
`qwen3:8b` (qwen3 8.2B Q4_K_M, 5.2), `qwen3:32b` (32.8B Q4_K_M, 20.2), `qwen3-27b` (qwen35 27.3B
Q8_0, 31.5), `gpt-oss:120b` (116.8B MXFP4, 65.4), `goekdenizguelmez/JOSIEFIED-Qwen3:8b` (5.0) and
`:30b` (qwen3moe 30.5B, 18.6), `qwen3-coder:30b` (18.6), `qwen3-coder-next` (qwen3next 79.7B,
51.7), `creat` (llama 8B Q8_0, 8.5), `way-local` (llama 12.2B BF16, 24.5). Vision:
`qwen3-vl:8b-instruct` (qwen3vl 8.8B Q4_K_M, 6.1), `valkyriesys/eudaimonia-dryad3-vision` (same
base, 6.1). Embeddings: `nomic-embed-text` (0.3), `bge-m3` (1.2). No model's `modified_at` is
later than 2026-09-25, so "updated" on .42 means the server, not the model set.

`192.168.1.39:11434` does **not** answer from the dev machine (connection refused/timeout), so an
Ollama on .39 could not be inventoried; nothing in `src/` references one. The local container
(`127.0.0.1:11435`, Ollama 0.32.14) has only `qwen3:8b`, JOSIEFIED 8b and `nomic-embed-text`.

The composition step (§9) and location descriptions use the analysis model through the existing
chatConfig/LiteLLM route. The §17 QA provider defaults to `valkyriesys/eudaimonia-dryad3-vision`
(ratified §34 Q12; `qwen3-vl:8b-instruct` is the same base and remains a valid alternate name),
available on `.42` without any install. Neither vision model is on the local container
(`127.0.0.1:11435`) or, as far as can be seen from the dev box, on `.39`, so a test run that
resolves to those tiers takes the Comfy fallback — the `[LLM-GATE]` line says which.

#### 36.5 Install list — status after 2026-10-09

| Item | Mechanism | Status (afternoon probe) |
|---|---|---|
| IP-Adapter node pack + SAM2 node pack | `ComfyInstallFeatures` (`features` = comma list from Swarm `src/Core/InstallableFeatures.cs`: `ipadapter`, `controlnet_preprocessors`, `frame_interpolation`, `gimm_vfi`, `comfyui_tensorrt`, `sam2`, `bnb_nf4`, `gguf`, `extramodels`, `nunchaku`, `teacache`; clones the repos and restarts the backend) | **Installed** — backend features `ipadapter`, `cubiqipadapterunified`, `cubiqipadapter`, `sam2`; 51 new nodes (§36.3). Done by Stephen; my own call was blocked by the tool-permission gate |
| ClipVision encoder `CLIP-ViT-H-14-laion2B-s32B-b79K` | `DoModelDownloadWS` (`type` must be a `T2IModelSets` key: `Stable-Diffusion`, `LoRA`, `VAE`, `Embedding`, `ControlNet`, `ClipVision`, `Clip`) or a file drop | **Installed** — `ListModels` ClipVision lists it |
| YOLO face weights `face_yolov8m.pt` | File drop into `(SwarmUI)/Models/yolov8/` (not a `T2IModelSets` type) | **Installed** — `yolomodelinternal` = `['face_yolov8m.pt']`; backend feature `yolov8` |
| IP-Adapter SDXL weights (`ip-adapter-plus_sdxl_vit-h`, `ip-adapter-plus-face_sdxl_vit-h`, FaceID variants) | **No install needed**: Swarm's `useipadapter` dropdown downloads each model on first use | Will fetch on the first render that selects a value; expect the first such request to be slow |
| InsightFace (for `FACEID*` and any identity-score workflow) | Python package + `antelopev2`/`buffalo_l` models inside the Comfy venv; not reachable by any Swarm API | **Unverified** — only a FACEID render will tell |
| SDXL inpaint checkpoint | `DoModelDownloadWS` type `Stable-Diffusion` | **Absent, optional** — SEQUENTIAL_INPAINT can start with a base SDXL checkpoint + `useinpaintingencode` |
| ControlNet model (pose/depth for REGIONAL_PROMPT) | `DoModelDownloadWS` type `ControlNet` | **Absent, optional** — `ControlNet` list is `[]` although the feature flag and SAM2 preprocessors are present |
| Kontext checkpoint | Gated on Hugging Face | **Absent; recommend retire** (§37) |

Nothing in this table has been rendered with yet. The first `GenerateText2Image` that sets
`useipadapter` or `<segment:yolo-face_yolov8m.pt>` is the Phase 5 spike's first step (§36.7).

#### 36.6 Code audit — where the design text was wrong or imprecise, and what was changed

| Claim in the design | Fact | Applied |
|---|---|---|
| Pinned nodes are rejected in Service7 | `PbServiceFacade.requestRegenerate` (Objects7 `:299-308`) rejects; Service7 only maps the exception | §1 |
| Runs have an `INTERRUPTED` status | `PbRunStatusEnumType` = `UNKNOWN, PENDING, RUNNING, COMPLETED, FAILED, CANCELLED`; `CANCELLED` is reserved and never written today | §1, §21 |
| Chat's scene route is at `ChatService:1348-1393` | It is `generateScene` at `:1401`; `:1348-1393` is `GET /chain/status` | §24.1 |
| 1024×576 is the chat composite size | It is the landscape stage (`SDUtil.generateLandscapeBytes:1409-1410`); composite size depends on mode | §24.1 |
| `picturebook.v2` is false in the deployed `web.xml` | The flag was deleted 2026-10-07; graph recording is unconditional | §28.1 |
| `ServerConfigUtil` resolves models | It resolves URLs only (`sd, face, tag, voice.tts, voice.stt, embedding`, 30 s cache); the default model is `SDUtil.setDefaultModel` from `RestServiceEventListener:327-331` (`sd.default.model` → `sd.model`), and `ChatService.java:1425` plus five `OlioService` routes read `sd.model` directly | §16 |
| `AsyncJobRegistry` limits (unstated) | `MAX_CONCURRENT_JOBS=2` (`:66`), `MAX_ACTIVE_JOBS_PER_PRINCIPAL=4` (`:90`), `COMPLETED_TTL_MS` 30 min (`:75`), `MAX_RETAINED_JOBS=200` (`:78`); kinds `pb.extractScenes, pb.retryFailedChunks, cb.create, cb.render, chat.chain`; **no run endpoint exists** | §21 |
| `attempt`, `variantIndex`, `reviewStatus`, `durationMs` on artifacts | None exist in `src/main`; all four are new schema fields | §10, §23, §32 |
| Retention can reuse the orphan sweep | `PbOrphanUtil` deliberately skips artifacts (`PbOrphanUtil.java:53-54`) and `PbHealthUtil` is a stale-graph scan; the retention sweep is a new routine | §23 |
| Composite capability "3 references" is a renderer limit | It is structural in `SceneCompositeUtil.java:191-200` | §15 |
| Timings | All from the Strix Halo iGPU, none from .39 | §15, §18, §33, Q14 |
| Code package | PictureBook code lives in `org.cote.accountmanager.olio.picturebook` (not `olio.pb`); the model names are `olio.pb.*` | — |

Confirmed as written (no change needed): the PB2 model fields in §1/§11 (`artifactModel.json:16-124`,
`bindingModel.json`, `nodeModel.json`, `runModel.json`, `sceneModel.json`, `bookModel.json`);
`PbNodeStatusEnumType` and `PbNodeTypeEnumType` values; `PbWatchedFields` v2 lists;
`PbArtifactUtil.setSelected:233-285` (one selected per node); `PbGraphUtil.markStaleDownstream:835`
BFS; `driftedRefBindings:577-599` is test-only; `PbConfigUtil.resolveEffectiveConfig:296` /
`resolveDeclaredConfig:324` / `mergeTiers:342-349`; `PbNodeExecutor` executable types
`PORTRAIT, LANDSCAPE, SCENE_PROMPT, LANDSCAPE_PROMPT, REFERENCE_STRIP, COMPOSITE`;
`PbSeriesUtil:147-149` / `PbBookUtil.createBook:91` / chapter overload `:292` world ownership;
`SWUtil.buildFlux2ScenePrompt:249,:276`, `newKontextSceneTxt2Img:428-462`, `flux2Defaults.json`
(cfg 2.0, steps 4, 1024×768, euler/simple, referenceSize 1024, `initImageCreativity` 0.6);
`ChapBookUtil.renderResolvedScene` (private, `:1730`), `renderChapBookSummary:1445`,
`assemblePriorContext:933`; the `PictureBookService` route list (`/olio/picture-book`: `regenerate
:1314`, `pin :1335`, `artifact/{aid}/select :1673`, `migrate-v1 :1825`, health/orphan routes, no
run route) and `ChapBookService` (`/olio/chap-book`); prompt templates
`promptTemplate.pictureBook.{extract-scenes, extract-chunk, extract-character, reduce-character,
scene-blurb, landscape-prompt, scene-image-prompt}.json` and
`promptTemplate.chapBook.{landscape-prompt, poem-analysis}.json` via
`PictureBookUtil.resolvePrompt:4596`; `PbPipelineUtil.persistBytes:523`; Ux752 files
`features/pictureBook.js`, `workflows/pictureBook.js`, `workflows/pictureBookCharacters.js`,
`components/SdConfigPanel.js`, `features/pictureBookWorkflow.js`, `chat/SceneGenerator.js`,
`components/readerShell.js`.

#### 36.7 Prep checklist before Phase 0 starts (notes only; nothing implemented)

1. Decide the open items in §34: **3 (default value), 7, 8, Q14 (source (a) vs (b))**. None
   blocks Phase 0–2; each has a recorded default (off, DRAFT, two weeks + sign-off, (b) seeded
   by (a)) that implementation uses until overridden.
   Closed 2026-10-09: item 1 (composition up front, §9.1), item 2 (POI link kept — object/item
   references, §7.2), item 15 (events/interactions skipped), Q3's shape (one
   general switch, §10), Q4 (Kontext retired, §37), Q5/Q13 (dynamic graphs, §38), Q6 and Q9–Q11
   (installs done), Q12 (VLM default, Comfy fallback, §17), Q14's threshold half (timings are
   never test thresholds, §31).
2. ~~Run §36.5 A/B/C~~ Done by Stephen 2026-10-09; §36.1/36.3/36.5 re-probed and updated.
3. Spike script (Phase 5, no product code): one `GenerateText2Image` per group in §36.2 — a
   masked inpaint over a FLUX.2 base render with `juggernautXL_ragnarokBy`, one `<region:>` and
   one `<object:>` prompt, one `<segment:yolo-face_yolov8m.pt>` and one CLIPSeg `<segment:>`, one
   `useipadapter = PLUS FACE (portraits)` render against a stored portrait (this also triggers the
   weight auto-download) — timed on .39, outputs looked at. Record the timings against §15/§18 and
   replace the Strix Halo figures.
4. During step 3, try one `FACEID PLUS V2` render; if it fails on InsightFace, record that §17
   identity QA needs the vision-LLM provider (Q12) rather than a Comfy workflow.
5. ~~Reconcile the G-n ids~~ Done 2026-10-09 (§36.8).
6. Phase 0 starts with the hygiene items in §30 and their tests; nothing in Part VII changes that.

#### 36.8 G-number reconciliation against `PictureBookImageGenerationDesign.md`

The design was written against a draft numbering. Corrected 2026-10-09 (old → new, where in this
document):

| Was | Is | Where | Gap-doc text |
|---|---|---|---|
| G-10 (two-store drift) | G-15, Appendix A | §4 | "book config is also stored in two places … and they drift" |
| G-9/G-15 (override leak) | G-15 | §4 | "A per-scene override can change the whole book" |
| G-17/G-21 (`kontext*` fields ignored) | G-21 | §14 | "Kontext, if chosen, runs with the wrong settings … the `kontext*` fields are never read" |
| G-21/G-23 (wasted prompt call) | G-23 | §14 | "The AI-written scene prompt isn't used by the default engine" |
| G-12/G-24 (template cross-wiring) | G-24 | §14 | "A prompt template choice can apply to the wrong step" |
| G-14 (explicit width/height) | G-18 | §15 | "Image size and aspect ratio are mostly ignored" |
| G-20 (ByteModelUtil rule) | Appendix A "Other" | §15 | "every SD write path sets `FIELD_BYTE_STORE` directly" |
| G-19/G-22 (one default policy) | G-19/G-20 | §16 | "Different parts … use different default models" / "model choice isn't validated" |
| G-19 (run closure) | Appendix A "Other" | §20 | "Graph runs are left RUNNING when a scene throws" |
| G-27/G-32 (sparse diffs) | G-32 | §26 | "render dialog overwrites each page's own overrides" |
| G-24 (PbNodeExecutor divergence) | G-30 | §30 Phase 1 | "The workflow node runner … diverges from the main pipeline" |
| Phase 0 row | rewritten | §30 | now G-15, G-18, G-24, G-26, G-8, G-11 + the two Appendix A items; ChapBook G-25, G-32 |

Unchanged and confirmed correct: G-1, G-4, G-5, G-6, G-8, G-11, G-16, G-18 (§4), G-19 (§16/§24),
G-23 (§12), G-25, G-26, G-32, G-35. The gap doc's §4 "suggested order" step 1 (G-15, G-32, G-25,
G-8) is exactly §30 Phase 0's core; its steps 2–5 map onto Phases 3 (G-9–G-12), 1/4 (G-3/G-4,
G-1/G-2), 9/1/6 (G-13, G-27, G-28) and 2/18/19 (G-16, G-17, G-19–G-22, G-31).

### 37. Kontext: deprecate or keep [added 2026-10-09]

Your note: "Kontext tests and code seem like they should be deprecated with this change, or leave in
if supported under new v3 design." This section gives the facts, the criteria under which V3 would
keep Kontext, the recommendation, and the full touch list so the decision can be executed without
re-surveying the tree. **Ratified 2026-10-09: retire** ("Yes to retire kontext"). §37.2 is kept
for the record; §37.3's retire row and §37.4 are the executable plan.

#### 37.1 What Kontext is in this codebase today (facts)

- **Role.** FLUX.1 Kontext is a reference-*editing* model: one stitched `[left|center|right]` panel
  strip (`SDUtil.stitchSceneImages`, 1024 px panels) is sent as the single prompt image and the
  model is asked to compose a scene from it. It was the first "reference" compositor, before FLUX.2
  multi-reference (`compositeMode=flux2`) replaced it as the default.
- **No checkpoint.** `.39` carries no `flux1Kontext*` checkpoint (§36.1); the weights are gated on
  Hugging Face and `DoModelDownloadWS` cannot fetch them without a token. `flux2Klein_9b` and
  `flux2_dev` — which cover the same reference-editing role and accept *separate* reference images
  without stitching — are installed.
- **Nothing selects it by default any more.** `compositeMode` defaults to `flux2`
  (`configModel.json:318-322`); `useKontext` defaults to `false` (`:312-316`); `kontextModel` has no
  default (`:390-393`). `SceneCompositeUtil.resolveMode(sdConfig, legacyKontextDefault)` (`:82-100`)
  returns `kontext` only when the config explicitly says `compositeMode=kontext`, or carries
  `useKontext=true` with `compositeMode` *absent*, or — chat only, via
  `ChatService.java:1490` `resolveMode(sdConfig, true)` — carries neither field at all. Because a
  schema default is never null on a record built through `RecordFactory`, the chat fallback is
  reachable only for hand-built or pre-2026-08 configs.
- **Four config fields are dead.** `kontextSteps`, `kontextCfg`, `kontextSampler`,
  `kontextScheduler` (`configModel.json:395-421`) are never read anywhere in `src/main`;
  `SWUtil.newKontextBase` (`:540-569`) hardcodes 28 steps / cfg 1 / euler / simple / 1024×1024
  instead. This is the "Kontext wrong settings" half of gap-doc G-21.
- **Tests are already off.** `TestKontext` is excluded in `AccountManagerObjects7/pom.xml:154`, its
  one live test (`testKontextSceneWithOlioCharacters`) is `@Ignore`d with a comment saying it
  "needs a flux1Kontext checkpoint that is not installed", and `TestPictureBookPipeline` (the other
  live Kontext caller) is excluded at `pom.xml:142`. `test.swarm.kontextModel` is blank
  (`resource.properties:115-119`).
- **No UI offers it.** Both Ux752 features pin `compositeMode='flux2'`; the `kontext*` fields exist
  in `modelDef.js` only as a mirror of the schema (`:10312-10316`, `:10383-10414`) and no form shows
  them.
- **One shared piece.** `SDUtil.stitchSceneImages` (`:1546-1590`) was written for Kontext but is now
  also used by `PbNodeExecutor.executeReferenceStrip` (`:329`) to record the reference-strip
  artifact for every mode. It is **not** Kontext-only.

#### 37.2 When V3 would keep it

V3's compiler model (§14) makes "supported model family" a concrete thing: a `*Compiler` that turns
a `RenderPlan` into a request, a `ModelResolver` capability entry, and a strategy that exercises
it. Kontext would stay **only if all three hold**:

1. A Kontext checkpoint is installed on the target Swarm and listed by `ListModels`
   (`Stable-Diffusion` set) — the gating item.
2. `KontextCompiler` is written and reads the `kontext*` fields (fixing G-21) instead of
   `newKontextBase`'s hardcoded values.
3. A `DIRECT_REFERENCE` variant uses it for something FLUX.2 cannot do. Today there is no such
   case: FLUX.2 takes multiple references natively, so the stitched-strip trick that Kontext needs
   is a disadvantage, not a feature.

None of the three holds, and (3) is unlikely to hold even after (1). Keeping Kontext would mean
writing a compiler and a strategy branch for a checkpoint nobody has installed, in order to
reproduce what `Flux2Compiler` already does with cleaner inputs.

#### 37.3 Recommendation: retire, on the §28.1 lifecycle

**Retire.** Not delete-now: the same disable → verify → remove sequence as PB1 (§28.1), so a
rollback to `v1` still has a working composite path and nothing is removed before V3 is verified.

| Stage | When | What happens to Kontext |
|---|---|---|
| **Deprecate** | Phase 0 (hygiene) | Mark `useKontext`, `kontextModel`, `kontextSteps/Cfg/Sampler/Scheduler` as deprecated in `configModel.json` descriptions and the `modelDef.js` mirror. Change `ChatService.java:1490` to `resolveMode(sdConfig, false)` so chat's absent-field fallback is classic like everything else (also closes Q4's second half). Log a WARN when `resolveMode` returns `kontext`. Delete the `kontext-test-output/` scratch dir and its tracked `kontext-request.json`; point `TestPictureBookFull.java:3128-3130` at `target/`. **No behaviour change for a config that explicitly asks for Kontext** |
| **Disable** | Phase 2 (compilers land; `SceneCompositeUtil` is superseded by `RenderPlan` + compilers) | `compositeMode=kontext` is rejected with the same "replaced by V3" response PB1 entry points return at §28.1 Stage 2; `isSupportedMode` drops it from `SUPPORTED_MODES`. V3 ships no `KontextCompiler`. Code kept in the build |
| **Verify** | §28.1 Stage 3 | Nothing extra: the verify checks already cover every composite path |
| **Remove** | Phase 10, with PB1 | Delete the code and tests in §37.4; drop the six fields through the off-by-default column-drop property only; remove `pom.xml:154` and `resource.properties:115-119` |

If you would rather **keep** it, the Phase 0 line changes to: install a checkpoint on `.39`, set
`test.swarm.kontextModel`, re-enable `TestKontext` in the POM, and add `KontextCompiler` to the §14
table as a Phase 2 deliverable with the `kontext*` fields as its inputs. Everything else in this
design is unaffected either way — Kontext is not on any phase's critical path.

#### 37.4 Deprecation inventory (what the Remove stage touches)

Kontext-only means "delete"; shared means "keep, strip the Kontext branch/comment". Line numbers
are as of 2026-10-09.

**Main code**

| File | Lines | Kontext-only? | Remove-stage action |
|---|---|---|---|
| `olio/sd/swarm/SWUtil.java` | `defaultKontextModel` :144-147; `newKontextSceneTxt2Img` ×4 :420-502; `newKontextBase` ×2 :540-569 | Yes | Delete |
| `olio/sd/swarm/SWUtil.java` | `styleClause` :507-537 | Only prod caller is :494 inside the Kontext builder; `TestPictureBookFull.java:2013-2045` asserts PictureBook does **not** use it | Delete with the builder |
| `olio/sd/swarm/SWUtil.java` | comments :135, :163-175, :209, :230, :240 (the FLUX.2 builder explains itself by contrast to Kontext) | Shared | Rewrite the comments to stand alone |
| `olio/sd/SceneCompositeUtil.java` | `MODE_KONTEXT` :35, `SUPPORTED_MODES` :39, `KONTEXT_PANEL_SIZE` :42, `isSupportedMode` :55, `resolveMode` useKontext fallback :96-100, `includesLandscapeReference` :105, KONTEXT branch :226-239 | Shared (class survives until Phase 2 replaces it) | Strip the branch; `resolveMode` loses its `legacyKontextDefault` parameter |
| `olio/sd/SDUtil.java` | `stitchSceneImages` :1538-1590 | **Shared** — `PbNodeExecutor.executeReferenceStrip:329` | Keep; retitle the javadoc (it says "for FLUX Kontext") |
| `olio/sd/SDUtil.java` | comments :1748, :1941, :2084 | Shared | Rewrite |
| `olio/picturebook/PictureBookUtil.java` | mode decision :10063-10074; landscape warn :10080-10086; `sceneCreativity` :10090; KONTEXT PIPELINE block :10196-10249; classic gate :10251; `SceneGenerationParams.compositeSdConfig` :1589-1591 | PB1 — removed wholesale at §28.1 Stage 4 anyway | Nothing extra |
| `olio/picturebook/PbNodeExecutor.java` | javadoc :283-284; `resolveMode(effectiveConfig, false)` :456 | Shared | Follows `SceneCompositeUtil` |
| `olio/llm/Chat.java` | comment :2408 | Shared | Rewrite |
| Service7 `ChatService.java` | `isSupportedMode` 400 gate :1419-1422; `resolveMode(sdConfig, true)` :1488-1490; branch comment :1539 | Shared | Phase 0: `true`→`false`; Remove: follows `SceneCompositeUtil` |
| `models/olio/sd/configModel.json` | `useKontext` :312-316, `compositeMode` description :322, `kontextModel` :390-393, `kontextSteps/Cfg/Sampler/Scheduler` :395-421 | Fields | Phase 0: deprecate in descriptions; Remove: drop six fields (column-drop property), reword `compositeMode` |
| `pictureBookMetaModel.json:74`, `pictureBookRequestModel.json:53`, `flux2Defaults.json:18`, `web.xml:45` | descriptions / comments | Text | Reword |

**Tests**

| File | Lines | Action |
|---|---|---|
| `TestKontext.java` | whole class (pom-excluded) | Delete — **except** `testStripSDXLWeighting` (:298), which tests the shared SDXL-weighting strip used by the FLUX.2 builder: relocate to `TestFlux2Composite` first |
| `TestFlux2Composite.java` | `promptOverrideReplacesKontextComposedPrompt` :629, `kontextAndClassicAlwaysConsumeTheLandscape` :746, `kontextAndClassicAreNeverAutoSkipped` :848, `landscapeGateFollowsTheLegacyKontextFallback` :861; assertions :398-402, :414-423, :444-453, :458-462, :468-477, :815 | Delete the four methods; rewrite the mode-vocabulary assertions to `flux2\|classic` |
| `TestPictureBookPipeline.java` | :297-319 (pom-excluded, PB1) | Goes with PB1 |
| `olio/TestPictureBookUtilE2E.java` :171-182, :237-253; `TestPictureBookUtilPersistenceE2E.java` :644-668, :723-745, :785 | PB1 Kontext branches | Go with PB1 |
| `TestPictureBookFull.java` | :2013-2045 (asserts `styleClause` is *not* used), :3128-3130 (writes to `kontext-test-output/`) | Drop the negative assertion when `styleClause` is deleted; redirect the output path in Phase 0 |
| `TestPbGraph.java:438` | lists `kontextModel` among fields a sparse diff must not carry | Keep until the field is dropped, then remove from the list |
| Comments: `TestPictureBookCustom.java` :659, :667; `SdTestGate.java` :31, :298; `TestChatDuelLong.java` :221 | — | Reword |
| `AccountManagerObjects7/pom.xml:154`; `src/test/resources/resource.properties:115-119`; `media/flux/bad.composite.png` fixture (if only `TestKontext` reads it — check before deleting) | — | Remove |
| `AccountManagerObjects7/kontext-test-output/` (tracked `kontext-request.json`, untracked `portrait-fresh-render-*.png`) | scratch dir, not gitignored | Phase 0: delete and redirect writers |

**Ux752**

| File | Lines | Action |
|---|---|---|
| `src/core/modelDef.js` | `useKontext` :10312-10316, `compositeMode` description :10322, `kontextModel/Steps/Cfg/Sampler/Scheduler` :10383-10414 | Mirror of the schema — follows `configModel.json` |
| `e2e/chatChainSceneReimageRoutes.spec.js` | :186 expects `flux2\|kontext\|classic`; :196 loops `['flux2','kontext','classic','KONTEXT']` | Disable stage: expect `flux2\|classic`, drop the two Kontext modes from the loop |
| `e2e/pictureBookScenePromptUx.spec.js:18`; comments in `chat/SceneGenerator.js` :49-50, `workflows/pictureBook.js` :225-230, :2700, `workflows/sceneExtractor.js` :357, :364, :496 and their tests | comments | Reword |

**Docs** mentioning Kontext (counts of lines): `KnownIssues.md` 34, this design 17,
`PictureBookSdConfigRefactor.md` 14, `PictureBook2Plan.md` 8, `PictureBookImageGenerationDesign.md`
5. History stays as history; only forward-looking text (§14, §16, G-21 in the gap doc) needs a
"retired" note at the Disable stage.

#### 37.5 Effect on the rest of this design

- §14: `KontextCompiler` row stays as "not built — retired (§37)"; the §14 compiler set is
  `Flux2Compiler`, `SdxlCompiler`, `InpaintRegionCompiler`, `RegionalPromptCompiler`.
- §16 `ModelResolver`: no `kontext` family in the capabilities resource; a style that names a
  `flux1Kontext*` checkpoint resolves to "not installed" like any other missing model.
- §30 Phase 0 gains the Deprecate line of §37.3; Phase 2 gains the Disable line; Phase 10 the
  Remove line.
- §34 Q4: ratified 2026-10-09 (retire).

### 38. Dynamic Comfy workflow compilation — the PB workflow as a proxy [added 2026-10-09]

**Direction (Stephen, 2026-10-09): "Comfy workflow should be dynamically built, making the PB
workflow a proxy of sorts."** This replaces the saved-workflow model that §15 and §34 Q5/Q13
assumed. V3 does not author, save or version Comfy workflows on the Swarm server. For every
attempt it **generates the ComfyUI graph from the `RenderPlan`**, submits it raw through Swarm's
normal generate route, and records the graph it sent on the artifact. The `olio.pb.workflow` /
`olio.pb.node` graph is the durable, user-visible **proxy** of that Comfy graph: each executable PB
node corresponds to a named subgraph in the Comfy graph, and each PB artifact to one Comfy output
node. Everything below was verified against the installed `0.9.8.3` build on `.39` and its
source (`src/BuiltinExtensions/ComfyUIBackend/*.cs`, saved under `C:\tmp\swarm_src_*.cs`), with
two live renders.

#### 38.1 The Swarm contract (verified)

| Capability | Fact | Where |
|---|---|---|
| **Get Swarm's own graph for a parameter set** | `POST /API/ComfyGetGeneratedWorkflow` with the T2I params **flat at top level** next to `session_id` (not wrapped — a `rawInput:{}` wrapper fails with `No model input given. Did your UI load properly?`). Returns `{workflow: "<API-format JSON string>"}`. For `model + prompt + steps + width/height + seed` on `juggernautXL_ragnarokBy` the graph is 7 nodes: `4` CheckpointLoaderSimple, `5` EmptyLatentImage, `6`/`7` CLIPTextEncode, `8` VAEDecode, `9` SwarmSaveImageWS, `10` SwarmKSampler | `ComfyUIWebAPI.cs:150-167`; `WorkflowGeneratorSteps.cs:58-61`; `C:\tmp\swarm_generated_workflow_sdxl.json` |
| **Reserved node ids** | Swarm's generator uses fixed ids `4–30`, dynamic `100+`, LoRA `1500+`, intermediate saves `50000+` | `WorkflowGeneratorSteps.cs:26-51` |
| **Prompt-syntax expansion is visible** | Adding `<segment:yolo-face_yolov8m.pt,0.6,0.5> detailed face` to the prompt made the generator emit a 17-node subgraph (`100–116`): `SwarmYoloDetection` → `SwarmMaskBlur` → `GrowMask` → `SwarmMaskThreshold` → `SwarmMaskBounds` → `SwarmImageCrop`/`CropMask` → `SwarmImageScaleForMP` → `VAEEncode` → `SetLatentNoiseMask` → `DifferentialDiffusion` → `CLIPTextEncode` → `SwarmKSampler` (`start_at_step` 8) → `VAEDecode` → `ImageScale` → `ThresholdMask` → `SwarmImageCompositeMaskedColorCorrecting` → `9`. This is the exact detect → crop → inpaint → recomposite pattern V3's ACTOR_REGION stage needs, and it comes from the generator for free | `C:\tmp\swarm_generated_workflow_ipa.json` |
| **Submit a raw graph** | `POST /API/GenerateText2Image` with `comfyworkflowraw` = the graph as a JSON **string**, plus the usual `session_id`, `images`, `model` (the generator's model check still runs). `comfyworkflowraw` is a hidden pseudo-parameter: absent from `ListT2IParams`, `HideFromMetadata`, always accepted at top level | `ComfyUIBackendExtension.cs:88, :189, :202-204`; `GetRawWorkflowFrom` `ComfyUIAPIAbstractBackend.cs:813-816` |
| **Permission** | `comfy_dynamic_custom_workflows` (`PermDynamicCustomWorkflows`, default POWERUSERS). The `.39` `local` user has `*` | `ComfyUIBackendExtension.cs:27, :198` |
| **Alternative carrier** | `comfyuicustomworkflow` = a saved name **or** inline `PARSED%<name>%<graph json>`; same fill path. Not needed — V3 uses `comfyworkflowraw` | `ComfyUIAPIAbstractBackend.cs:797-812`; `:739-740` |
| **Tag filling** | `${tag}` / `${tag:default}` are filled by Swarm before submission: built-ins `prompt`, `negative_prompt`, `seed` (`seed+N` for offsets; `-1` → random), `steps`, `width`, `height`, `cfg_scale`, `subseed`, `subseed_strength`, `init_image` (base64), `init_image_strength`, `sampler`, `scheduler`, `model`, `prefix`, `loras`; any other registered T2I param id by name; unknown tag without a default → `Unknown param type request` error | `CreateWorkflow` `ComfyUIAPIAbstractBackend.cs:821-921`, switch at `:886-905` |
| **Per-request custom inputs** | `${comfyrawworkflowinput<type><name>}` with the value posted under that **exact key**; `<type>` is a lower-cased `T2IParamDataType` (`text`, `integer`, `decimal`, `boolean`, `dropdown`, `image`, `model`, `list`, `imagelist`, …) or `seed` (`-1` → random). The dynamic param is only recognised when `comfyworkflowraw` or `comfyuicustomworkflow` is in the request | `ComfyUIBackendExtension.cs:234-269`; `T2IParamTypes.cs:17-47` |
| **Images into the graph** | The generator itself inlines images as `SwarmLoadImageB64 {image_base64}` when the backend has the `comfy_loadimage_b64` feature (`ComfyUIBackendExtension.cs:46`; `.39` has it — it is a Swarm-Comfy node). V3 does the same — no upload step. (Swarm's fallback for `${image-param}` tags is an upload to Comfy `/upload/image` plus filename substitution, `ComfyUIAPIAbstractBackend.cs:935-995`; not needed) | `WorkflowGenerator.cs:514-580` |
| **Outputs** | Every `SwarmSaveImageWS` node produces one output per image. Swarm reads them from the Comfy websocket (`progress.max == 12345` marks an output frame) and then `/history/{promptId}`, then deletes the history entry. Numeric node ids `< 100` other than `9`, and `≥ 50000`, are flagged *intermediate* (`IsReal=false`, saved only when `outputintermediateimages` is set); **non-numeric ids are always real**. Real outputs get batch index `imageIndex + n`, overflow beyond `batchsize` goes to `images*batchsize + extra`; the HTTP response lists them in that order | `ComfyUIAPIAbstractBackend.cs:444, :503-531, :551-573`; `T2IAPI.cs:164-189, :423-439` |
| **Node-type pre-check** | Before dispatch Swarm fills every `${tag}` with `null`, parses the graph and refuses it if any `class_type` is not in the backend's node list (`The custom workflow contains an unsupported node type '…'`) | `TryIsValid` `ComfyUIAPIAbstractBackend.cs:1025-1048` |
| **Raw Comfy API** | `/ComfyBackendDirect/{*path}` proxies the backend: `object_info` (1088 node types with input schemas), `object_info/<Node>`, `system_stats` all return 200 on `.39`. Use it to validate node *inputs* (names, types, enum values) before submission — Swarm only checks the class name | `ComfyUIBackendExtension.cs:1006` (`/ComfyBackendDirect/{*Path}`); live 2026-10-09 |

**Live render 1 (2026-10-09 13:02).** Generated SDXL graph, edited: node `6` text →
`${comfyrawworkflowinputtextscene}`, `5` → 512×512, `10.steps` →
`${comfyrawworkflowinputintegersteps:4}`, `10.noise_seed` → `${comfyrawworkflowinputseedv3seed}`,
plus an extra node with the **string id** `v3_save_preview` (`SwarmSaveImageWS` on `["8",0]`).
Posted with `comfyrawworkflowinputtextscene = "a red lighthouse on a cliff, dusk, painterly"`,
`…integersteps = 4`, `…seedv3seed = 1234`. **19.6 s**, one 512×512 PNG, content matched the
prompt on inspection (`C:\tmp\swarm_rawworkflow_probe.png`). Only one image came back because
both save nodes carried the same bytes.

**Live render 2 (13:10).** Same graph, but the second save node fed by an `ImageScale` to
256×256 (`v3_scale_small` → `v3_save_small`). Response: `images` = two paths
(`…1310001--juggernautXL_ragnarokBy.png` 512×512, `…-1.png` 256×256), in execution order.
**2.5 s** — ComfyUI served the sampler from its node cache because seed, prompt and sampler inputs
were identical to render 1; only the new scale/save nodes ran. Both facts matter below.

#### 38.2 What V3 builds

A new layer in `olio/illustration/`, between the prompt compilers (§14) and the adapter (§15):

```
RenderPlan ──► WorkflowCompiler.compile(plan, stage) ──► ComfyGraph (value object)
                 │  uses PromptCompiler per stage for the text
                 │  uses the node catalogue (object_info snapshot) for input schemas
                 ▼
SwarmRendererAdapter.execute(StageRequest{graph, inputs}) ──► comfyworkflowraw + ${…} inputs
                 ▼
StageResult {outputs[] keyed by save-node id, promptId, graphHash, timings}
```

- **`ComfyGraph`** is a map of node id → `{class_type, inputs}` exactly as Comfy's API format,
  plus a map of *named outputs* (`"final"`, `"base"`, `"region.<actorId>"`, `"mask.<actorId>"`,
  `"qa.<check>"`) to save-node ids. It is serialised to JSON for `comfyworkflowraw` and hashed
  for `artifact.backendGraph`.
- **Node ids are strings with a `v3_` prefix** (`v3_base_sampler`, `v3_region_<actorId>_ksampler`,
  `v3_save_final`). This avoids every Swarm-reserved numeric range and makes every V3 save node
  "real" by Swarm's rule (§38.1 Outputs). Graphs are deterministic for a given plan: same plan →
  same ids → Comfy's node cache can skip unchanged subgraphs (render 2).
- **Base graph source.** The compiler **starts from `ComfyGetGeneratedWorkflow`** for the stage's
  Swarm parameters (model, prompt, size, steps, cfg, sampler, LoRAs, `initimage`/`maskimage`,
  `<segment:>` / `<region:>` syntax, `useipadapter`, refiner) and then **patches and extends** that
  graph. Reasons: Swarm encodes the per-model-family plumbing (SDXL vs FLUX.2 loaders, text
  encoders, VAE, `fluxguidancescale`, reference-latent nodes) that V3 should not re-derive, and it
  already emits the detect→inpaint→recomposite subgraph. V3's own nodes are **appended** (extra
  samplers, masks, composites, scale/crop, save nodes); Swarm's nodes are only re-wired, never
  renumbered. The generated graph is fetched per attempt (cheap, no GPU) and recorded with the
  patch in the plan artifact so a render is reproducible from the artifact alone.
- **Inputs that vary per attempt** are tags, not literals: prompt text, negative, seed, steps,
  cfg, init/mask images, reference images. They go in as `${comfyrawworkflowinput<type><name>}`
  with values under those keys, so the graph JSON stored on the artifact contains no image bytes
  and no prompt text — the plan artifact has those. (Images: inline `SwarmLoadImageB64` nodes
  whose `image_base64` is a `${comfyrawworkflowinputimage<name>}` tag; the fill returns base64.)
- **Validation before submit.** The compiler validates every V3-authored node against a cached
  `object_info` snapshot (`/ComfyBackendDirect/object_info`, refreshed with the model list TTL in
  §15): class exists, required inputs present, enum values legal, link targets exist and expose
  the slot. A failure is a `PLAN_INVALID` (§20) *before* the GPU is touched, with the node and
  input named. Swarm's own `TryIsValid` is the backstop, not the check.
- **One Comfy graph per stage, or per strategy?** Per **stage** (§13: BASE, each ACTOR_REGION,
  HARMONIZE, UPSCALE, QA), because §11 makes each stage a PB node with its own artifact, §22 wants
  "regenerate Bob" to re-run one stage with the others bound by artifact revision, and §21 cancels
  *between* stages. A multi-stage graph in one submission would defeat all three. The cost — one
  queue round-trip per stage and re-sending the base image into the region stage — is small next to
  sampling, and ComfyUI's node cache (render 2) removes the model-load cost between consecutive
  stages on one backend. A later optimisation can fuse stages when nothing in between is
  user-visible, but it is not the default.

#### 38.3 The PB workflow as proxy

| PB graph (`olio.pb.*`) | Comfy graph |
|---|---|
| `olio.pb.node` of an executable type (BASE, ACTOR_REGION[i], HARMONIZE, UPSCALE, QA, PORTRAIT, LOCATION) | One `ComfyGraph` compiled for that stage; the node's `generatorRequest` is the sanitised request body (graph hash, tag names, parameter values; no bytes) |
| `olio.pb.binding` (input artifact revision → node) | A `SwarmLoadImageB64` node (`v3_in_<bindingRole>`) whose image tag is filled from that artifact's bytes at submit time |
| `olio.pb.artifact` (output of a node) | One named `SwarmSaveImageWS` node (`v3_save_<role>`); the artifact's `backendGraph` = graph hash + the save-node id it came from; `backend` = `swarm/comfyworkflowraw` |
| Node status RUNNING / COMPLETED / FAILED | Swarm's websocket progress for that one submission (`GenerateText2ImageWS`), `/history/{promptId}` on completion, Swarm's `error` object on failure |
| Stale propagation (`markStaleDownstream`) | Not mirrored in Comfy; Comfy graphs are rebuilt from the plan each attempt. The PB graph is the source of truth for *what depends on what*; the Comfy graph is the source of truth for *how one stage was rendered* |

So the PB workflow is a proxy in the sense that it is the durable, inspectable record and control
surface — every box in the Ux plan inspector (§26) is one Comfy subgraph, and "show me the graph"
on an artifact returns the exact `comfyworkflowraw` string with its filled tag values, which can be
dropped into the ComfyUI editor or re-submitted verbatim for a repro. There is **no** second
persisted copy of the Comfy graph beyond the artifact.

#### 38.4 What this changes elsewhere in this design

- **§15 `SwarmRendererAdapter`:** the "Named Comfy workflows via `comfyuicustomworkflow`"
  bullet is superseded. The adapter gains `generateBaseGraph(params)` (→
  `ComfyGetGeneratedWorkflow`), `nodeCatalogue()` (→ `object_info`, cached) and
  `execute(StageRequest)` sends `comfyworkflowraw`. The plain-parameter path stays for PORTRAIT /
  LOCATION / TEXT_ONLY where Swarm's generator already produces the right graph — in that case the
  compiler's output *is* the generated graph plus V3 save-node renaming, which keeps one code
  path.
- **§14:** prompt compilers are unchanged; they feed text into the graph compiler.
- **§17 `SwarmQaProvider`:** a QA "workflow" is a V3-compiled graph whose outputs are
  `SwarmSaveImageWS` mask/crop images plus text frames — Swarm's websocket also carries **text
  outputs** (`progress.max == 12347`, `metadata` `key:value` frames routed to
  `user_input.ExtraMeta["custom_<key>"]`, `ComfyUIAPIAbstractBackend.cs:471-501`). Whether a
  built-in node can emit such a frame for a detection count is a spike question (§38.5); the
  fallback is to save the mask image and count on the Objects7 side.
- **§20 failure semantics:** two rows added — `GRAPH_INVALID` (local validation failed; no GPU
  time spent) and `RENDERER_REJECTED` (Swarm's `unsupported node type` refusal or Comfy's `error`
  websocket message, with the node id).
- **§21:** cancel stays between stages; `InterruptAll` is still not used.
- **§32:** `artifact.backendGraph` holds `{graphHash, saveNodeId, swarmVersion, backendId}`;
  the plan artifact holds the full graph string. Add a `graphHash` dimension to the render
  timings.
- **§36.2 route list:** add `ComfyGetGeneratedWorkflow` and `ComfyBackendDirect/object_info`;
  `ComfySaveWorkflow` / `ComfyReadWorkflow` / `ComfyListWorkflows` are no longer used by V3.
- **§30 Phase 5 spike** becomes "build the graph compiler against the live backend", not "author
  workflows" — see §38.5. Phase 2 (`SdxlCompiler`, TEXT_ONLY) can ship on the plain-parameter
  path and still record `backendGraph` from `ComfyGetGeneratedWorkflow`, so the proxy mapping
  exists from the first V3 render.

#### 38.5 Still to verify in the spike (not guesses — unmeasured)

1. **IP-Adapter graph shape.** `useipadapter` with an empty `promptimages` emitted no IP-Adapter
   nodes (ignored, not an error). Re-run `ComfyGetGeneratedWorkflow` with a real portrait in
   `promptimages` and `useipadapter = PLUS FACE (portraits)`, capture the subgraph, and confirm the
   weights auto-download on the first `GenerateText2Image`.
2. **`<region:>` / `<object:>` graphs.** `<object:>` is handled *outside* the generator
   (`T2IMultiStepObjectBuilder.CreateFullImage`, `T2IEngine.cs:240-263`): Swarm renders the object
   passes as separate generations and feeds the composite back as `initimage`. So REGIONAL_PROMPT
   with `<object:>` is **not one graph** today; V3 either compiles its own per-actor inpaint graph
   (the §38.1 `<segment:>` subgraph is the template) or uses `<region:>` only (which the generator
   does emit as conditioning). Decide after capturing both.
3. **Text output frames** for QA counts (§38.4, §17).
4. **FLUX.2 base graph** from the generator (`flux2Klein_9b` with `promptimages` references), to
   see the reference-latent nodes V3 must preserve when it extends that graph for a region pass
   with an SDXL checkpoint.
5. **Timings on `.39`** for a compiled BASE + one ACTOR_REGION pair, to replace the Strix Halo
   figures (§34 Q14).
6. **Websocket route** (`GenerateText2ImageWS`) for per-stage progress; the HTTP route was used
   for both probes.

Nothing in this section needs an online research prompt: the contract was taken from the running
server's source and confirmed by two renders. The spike items are measurements, not unknowns.
