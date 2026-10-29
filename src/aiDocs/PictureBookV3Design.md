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
(§34 item 15)**, **Q14 source (b) seeded by (a) (ratified 2026-10-24 with P5-0)**. Open: §34 items 3
(default value only), 7, 8 — none blocks Phase 0–2; the design records a default for each.
**Inputs:** `archive/PictureBookV3Notes.md` (target architecture), `PictureBookImageGenerationDesign.md`
(current behaviour and gaps G-1…G-36), `PictureBook2Plan.md` (workflow graph, §6 ComfyUI),
`archive/PictureBookWorkflowOverhaul.md`, `PictureBookAsyncJobDesign.md`, `archive/PictureBookSdConfigRefactor.md`.
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
| Run tracking | `olio.pb.run` (`requestedNodeIds`, counts, `runStatus`). `PbRunStatusEnumType` = `UNKNOWN, PENDING, RUNNING, COMPLETED, FAILED, CANCELLED`; `CANCELLED` is declared "reserved and currently unwritten"; there is no `INTERRUPTED` yet **[verified 2026-10-09]**. **Updated 2026-10-10 (P1-1):** `INTERRUPTED` now exists after `CANCELLED`; only the P1-7 boot sweep stamps it |
| Sparse overrides | `node.configOverride` / `scene.configOverride`, resolved by `PbConfigUtil` |

**Decision D1:** V3 models are added to the `olio.pb.*` family. Rendering *is* graph execution.
"Render", "variant", "render manifest" and "version" are expressed as artifacts and bindings rather
than parallel entities. PB1's `generateSceneImage` and its note-blob scene state are retired (this
is the end state that `archive/PictureBookWorkflowOverhaul.md` already recommends).

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

> **Built (P4-5, 2026-10-19):** `PbWardrobeUtil` (Objects7) over `PbServiceFacade`. **From the story:** the
> `pictureBook.compose-scene` reply row gained an `outfit` key ("a short phrase for what the figure is wearing
> ONLY when the scene text states a change of outfit or describes the figure's clothing, else null"); for each cast
> actor whose row carries one and that holds no pin, the compose step builds the alternate (or reuses one — same
> store + normalised description) **and pins the actor row to it**, so a story-detected change is an explicit pin on
> that scene and carry-forward (§6.2) covers the scenes after it. The alternate is built by the shipped
> `pictureBook.guess-apparel` → `ApparelUtil.constructApparel` / `designApparel` / `applyGuessedColors` sequence, as
> the **olio principal** in the book world's Apparel group (so the compose and user routes need the deployment's
> `datagen.path`), `inuse=false`; the store membership is written **as the caller** through `AccessPoint.member`
> after a `canUpdate(store)` pre-check (403 before the LLM call). (2026-10-20: the guess receives the character's
> **physical** description only — `physicalAppearance`: the narrative's `physicalDescription`, fallback `actorDescription` —
> and the request is prefixed with `REQUEST_FRAME` so it outranks the clothing in the character's own description; live
> run 4 had dressed the character from her default outfit — implementation doc §6.C 191 (b).) The LLM's item names are normalised onto the
> catalog (exact, else the longest catalog name contained as whole words), asked once more with a format hint when
> nothing resolves, then refused with 502 — **no `randomApparel` fallback** for a described outfit; names the catalog
> could not express come back as `unmatched[]`. A wardrobe failure inside a compose is a warning + `outfitsSkipped`,
> never a failed composition. **By the user:** `POST /{book}/character/{id}/wardrobe {description, chatConfigName?}`
> (201 new / 200 reused; 409 at 24 outfits); the existing apparel wizard is untouched. **Default:**
> `PUT …/wardrobe/{apparel}/default` flips `inuse` as the caller (`AccessPoint.update`, `copyRecord([id, objectId,
> inuse])`, new default first then the old ones). **Reference image:** `POST …/wardrobe/{apparel}/reference` → 202 job
> (`pb.apparelReference`, one per apparel) running `SDUtil.generateMannequinImages` with the book's effective
> PORTRAIT-task config into `~/Gallery/Apparel/<name>`; the images land in `apparel.gallery` and the last is the
> wardrobe entry's `referenceObjectId`. **Not built:** the "same review/approve flow as other assets" — they are gallery
> images, not `olio.pb.artifact` rows, so §10 does not see them (implementation doc §6.C 190 (b)). Judgement calls: §6.C 190.

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

> **Built (P4-5, 2026-10-19):** `PbWardrobeUtil.pinActorApparel` / `resolveOutfits` / `setDefault` /
> `migrateSceneTags`. **Validation:** a pin must name an apparel in the actor's character's own store (400 otherwise;
> `requireApparelOfCharacter`), and the character must belong to the book (world Population group, chapter shadow
> group, or legacy `<bookGroup>/Characters`; 404 otherwise). **Carry-forward** is resolved at read time over the
> book's compositions in `sceneIndex` order: pinned → `(pinned)`; an earlier scene's pin for the same character →
> `(from scene N)`; else the store's `inuse` default → `(default)`; none → `(none)`. `GET
> /{book}/composition/{id}/outfits` returns it per actor with `source` + `label`. A pin (or unpin — empty body) marks
> the scene and every later scene that carried from it stale; it does **not** set `composition.userEdited` and a
> re-compose (including `force`) preserves pins. **Rendering never mutates `inuse`:** nothing in P4-5 flips it;
> the legacy render-time flip in `PbNodeExecutor`/`selectSceneApparel` is left for P4-7, whose `RenderPlan` reads the
> resolved outfit from the freshly loaded apparel (G-5). **Default change** stales only the scenes that resolved to
> `(default)` for that character — computed before the flip — via `markSceneNodeStale` + `PbStaleUtil.touch`, since the
> `watched/v4` set cannot see store membership or another record's `inuse`. **Migration:** `POST
> /{book}/wardrobe/migrate-scene-tags` (also the P4-10 one-off's utility) turns each `sceneIndex` tag into a pin on
> scene N's actor; idempotent, never overwrites a pin, marks nothing stale, **keeps the attribute** until the legacy
> flip goes with P4-7; a chapter book's shared-Population characters are counted `ambiguous`, not guessed.
> `tagApparelSceneIndex` is deprecated, not deleted. Judgement calls: §6.C 190.

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
  **[2026-10-16 (P3-4), as built:** one LOCATION node per (location, variant) with `scope=location|locationVariant` and
  `scopeRef` the record's objectId; the node is materialized on the first render / approve / proposal-accept, never on a read;
  its artifacts carry role `landscape` and go through the §10 review; approve promotes the artifact to the record's
  `approvedRender`, reject clears it; the lock is a facade-only flag that refuses every facade write (edits, approve,
  reject-the-approved, render, new variants, delete, merge) with 409 and is not consulted by generic `PATCH /rest/model`;
  render is async (202 job) and refuses before planning when the subject is locked, pinned or in flight — `force` bypasses
  none of them. The binding that makes dependent scenes STALE is written by the RENDER_PLAN node on every execution
  (location ordinal 0, variant ordinal 1); other chapters see STALE on their next scan after the TTL or a forced read.**]**
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
| `WorldUtil.deleteWorld` already cleans the world's POI and store groups, so book deletion needs nothing new | `WorldUtil.java:555, 567` (cite moved 2026-10-16: P3-1 added two `cleanupLocation` sweeps above these lines; was `:551, 563`) |
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
   > **Built (P4-6, 2026-10-20):** `PbComposeUtil.promoteElement(user, book, compositionObjectId, elementObjectId)` over
   > `PbServiceFacade`, `POST /{book}/composition/{id}/element/{elementId}/promote`. **One correction to the wording above:**
   > `ItemUtil.addNewInventory` writes an `olio.inventoryEntry` into `store.inventory` (quantities for the game economy); the
   > composer reads `store.items`, so the promoted item is enrolled in **`store.items`** (`AccessPoint.member(user, store, "items",
   > item, null, true)`, audited; no `MemberUtil` fallback — a refusal is a 500 and the retry reuses the item by name) and no inventory
   > entry is written (tracking 192 a). **Containment:** book update → composition of the book → element of the composition → the
   > composition's location, else the scene's (none → 409) → of the book's world (`PbLocationUtil.sameWorld`, 404) → the POI store
   > (none → 409) → `canUpdate(store)`; nothing is written before the last check. **Reuse before create:** an already-linked element
   > returns `reused` and writes nothing; else the description is matched whole-word against the store's items (`matchItem`) and by
   > exact name (`type="prop"`) in the world Items group; else a minimal `olio.item` is created as the olio principal (`name` = the
   > description minus its leading article, 64 chars; `description` 512; `type="prop"`; group = world Items; no colours/materials
   > invented — catalogue `type="template"` rows are never reused). The scene node goes STALE on every link write. **Prompt text:**
   > `PbComposeUtil.elementText(user, element)` = `"<colour> <materials> <name>, <description> with <c1> and <c2> accents"` from a
   > fresh load of the linked item (never "null"; a description that is just the name plus its article is not repeated), falling
   > back to the element's own description; its compile-side caller is P4-7's `PbRenderPlanUtil.sceneElements` — until then no
   > prompt reads `sceneElement.item`. Items are still not reference images.
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

> **Built (P4-8, 2026-10-21):** the composition and its rows are edited **row by row** over REST (§27) through
> `PbComposeUtil`'s row API: `patchCompositionFields` (editable: `settingText`, `action`, `mood`, `shotType`,
> `viewpoint`, `cameraText`, `props`, `configOverride`, `promptOverride`, `promptLocked`, `focalActorObjectId`;
> `purpose`/`scene`/`book`/`location`/`locationVariant`/`source`/`userEdited`/`revision` are refused with the
> route that owns them), `createActorRow` (`characterObjectId` **or** `description`; the handle is
> `actor.<composition>.<max suffix + 1>`, the box from `PbLayoutUtil.defaultPlacement`), `patchActorRow`
> (`role`, `priority`, `pose`, `expression`, `action`, `facing`, `x/y/w/h`, `depth`, `description`, `count`,
> `referencePolicy`; `character` and `apparel` are refused — the character is fixed for the row's life, the
> outfit is pinned through the P4-5 apparel route), `deleteActorRow` (a focal actor is unfocused first),
> `createElementRow`/`patchElementRow`/`deleteElementRow` (`item` only through the P4-6 promote route). Every
> write is one PATCH against the row itself (the PATCH caveat above holds; nothing cascades), then
> `touchEdited` sets `userEdited`, `source=USER`, `revision`+1 on the composition and marks the scene node
> STALE. Over-length text is a 400, not truncated; a null or blank enum on PATCH is a 400, never a reset to
> UNKNOWN. A `CHAPBOOK_PAGE` composition refuses actor rows (G-35 off) but accepts elements. The camera fields
> are editable here but not yet compiled into the plan (Implementation §6.C 193 p), and neither are `settingText`,
> `action`, `mood`, `props` or `configOverride` — the planner still reads those from the scene (`PbRenderPlanUtil`
> `locationText`), so an edit here is persisted and marks the scene STALE but does not change the next render until
> the planner is wired to the composition (Implementation §6.C 194 jj — ruled 2026-10-21 "fix as suggested": P4-10, with
> the scene-copy retirement; until then P4-9's inspector shows the compiled `/plan` prompt beside the fields). Implementation
> §6.C 194, 195.
>
> **Built (P4-10, 2026-10-22):** the planner now reads the composition — `settingText`/`action`/`mood` per field with the
> scene as fallback, `props` appended, the camera fields (`shotType`, `viewpoint`, `cameraText`) compiled into
> `RenderPlan.Camera` and the SDXL camera stage (the composer also writes its shot/viewpoint words into `cameraText` —
> `PbComposeUtil.cameraText(Proposal)`, "medium, eye_level" — so the planner drops any comma-separated piece of the free
> text that is just one of those two words; found by the P4-10 live proof, §6.C 198 v) — and `configOverride` is the composition's, applied between the scene and
> node tiers by mirroring it onto the node (`PbBookUtil.mirrorSceneOntoNode`; `PbConfigUtil.mergeTiers` unchanged). A
> composition `configOverride` PATCH also mirrors onto `scene.configOverride`; a page composition's `promptOverride`/
> `promptLocked` PATCH mirrors onto `scene.sdPrompt`/`promptLocked`; a SCENE composition's `promptOverride` never reaches the
> scene (board direction, not scene text). The scene's `sdPrompt`, `promptLocked`, `configOverride` are the mirror, the
> composition the authority (§24 P4-10 block; Implementation §6.C 198, §12.10).
>
> **Built (P4-13, 2026-10-23) — where a composition row lives:** every `olio.pb.composition`, `olio.pb.sceneActor` and
> `olio.pb.sceneElement` is created in the group of the book its `book` FK names (`ctx.bookGroupId` at `PbComposeUtil:1966`, the
> book's group at `:2529`, the composition's own group for its actor / element rows at `:2557` `ownRow`), never in another book's
> group. The stale-graph purge (`PbHealthUtil.scanStale`, olio-run) relies on this when it sweeps a composition found in this book's groups whose `book` names another book — today only a dead row matches. A future SERIES_ART
> composition that must live in a shared or chapter group with `book` naming another chapter changes that scan first
> (security-reviewer P4-14 F4; Implementation §6.C 202).

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
| Review | `artifact.reviewStatus` (**new** enum `PbReviewStatusEnumType`) | GENERATED, IN_REVIEW, APPROVED, REJECTED, ARCHIVED (+ UNKNOWN as the first constant, **added 2026-10-10 in P1-1**: `EnumValueType.setValue` maps a SQL NULL to the literal `UNKNOWN` before validating against the class, so without it every pre-Phase-1 artifact row threw `ValueException` on read; it is never written on purpose and `PbArtifactUtil.reviewStatusOf` folds null/UNKNOWN into GENERATED — every reader goes through that helper. Also recorded 2026-10-10: `artifact.approvedAt` is a zonetime column with DDL `default now()`, so it is never NULL and is **not** an approval test; approval is `reviewStatus == APPROVED` only. `durationMs` 0 means unrecorded.) |
| Canonical | `artifact.selected` (exists) | exactly one per (node, role) |

Rules:
1. A new attempt's variants arrive as GENERATED, `selected=false`. **No attempt ever changes
   `selected` on its own if the current selected artifact is APPROVED (G10 in the notes).** If
   nothing is approved yet, the first variant is auto-selected so the book has something to show.
2. "Use this image" selects that variant. "Approve" marks the selected variant APPROVED and **pins
   the node**. That is the existing `pinned` semantics: propagation still marks it STALE, and
   regeneration needs an explicit unpin.
   **Made precise 2026-10-11 in P1-4** (architect-ratified; `PbArtifactUtil.select/approve`,
   `TestPbReview`): *approve implies select* — approving a variant that is not the selection selects
   it, and any other APPROVED sibling is demoted to GENERATED with `approvedBy` cleared, so there is
   exactly one APPROVED artifact per (node, role). "Use this image" writes `selected` **only** (never
   `reviewStatus`, never `pinned`), and it is refused with **409** while the chain's selection is
   APPROVED: a selection click reads as "show this one" and must not silently discard a human approval
   and its pin — approve the other variant, or reject the approved one. Every approve step guards on
   its own terminal state (demote → select → pin → stamp), so re-approving re-pins an unpinned node and
   a human approval over `AUTO` re-stamps `approvedBy`; protecting a human approval from the
   auto-approver is rule 5's caller guard, not approve's.
3. A STALE node keeps showing its approved artifact. The reader shows a subtle stale badge; nothing
   disappears.
4. REJECTED variants are kept until cleanup (§23). ARCHIVED is used for history beyond the
   retention window.
   **Made precise 2026-10-11 in P1-4** (`PbArtifactUtil.reject`, `TestPbReview.case05`): rejecting
   the selected variant clears `approvedBy` and hands the selection to the **newest** sibling that is
   neither REJECTED nor ARCHIVED (never promoting it); with no such sibling the rejected artifact stays
   selected so the chain keeps exactly one selection, and the next attempt then auto-selects (rule 1
   only protects an APPROVED selection). **Reject does not unpin** — a rejection is a verdict on one
   variant, not a regeneration request; only an explicit unpin releases a pin. *(Default chosen by
   the implementation, not yet ratified by Stephen — `PictureBookV3Implementation.md` §6.C.)*
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

   **Made precise 2026-10-11 in P1-5** (architect-reviewed; `PbArtifactUtil.autoApprove`,
   `PbNodeExecutor.applyAutoApprove`, `PbServiceFacade.get/setAutoApprove`, `TestPbAutoApprove`):
   - **Default is off** (Phase 1 start prompt, decided default; closes §34 item 3 for Phase 1). It is
     per-book state, nothing more — the one column `olio.pb.book.autoApprove`, read off the book record
     the executor is handed (`bookRequest()` projects it), so a caller holding a stale book must re-read
     it after `setAutoApprove`.
   - **Hook point:** `executeNodeRun` only, after the run is closed COMPLETED. **Test mode**
     (`PbServiceFacade.testNode` → `executeNode`, no run) is **excluded**: a test render is a look, not
     a result. *(Default chosen by the implementation; Test-mode inclusion would be a one-line
     ratification — `PictureBookV3Implementation.md` §6.C.)* The auto-approver **never throws**: a
     guard or a failed approve is reported on the executor DTO as `autoApproveSkipped`
     (`"off"`, `"degraded: …"`, `"existing-approved: <name>"`, `"failed: …"`) beside
     `autoApproved:true|false`, and `/regenerate` returns both verbatim; on success the DTO's
     `reviewStatus` is `APPROVED`.
   - **The degraded guard keys on the executor's `degradations[]`**, which every V3 executor now emits
     (empty when the attempt consumed everything the graph bound). On V3 the v1 `landscapeFailed` flag
     is vacuous, so "QA FAIL / `ACTOR_FAILED` / `MODEL_SUBSTITUTED`" above are the §19 names for
     entries in this list; Phase 1 emits `<role>: no selected artifact`, `<role>: source node
     unreadable`, `<role>: artifact has no image bytes`, `scenePrompt|landscapePrompt: scene has no
     description; empty prompt recorded`, `landscape: scene has no sdPrompt; generic fallback prompt
     used`. **A skipped attempt stays GENERATED, not IN_REVIEW** —
     `IN_REVIEW` is declared but nothing in Phase 1 writes it (the review queue lists GENERATED as
     pending); *recorded, not ratified* (§6.C).
   - **Existing-approved** is checked over the chain before `approve` (which would demote the
     sibling); the window between that read and the write is one query and is inherited unchanged by
     the async layer (P1-6). The skipped newcomer is left exactly as rule 1 left it (unselected behind
     an APPROVED selection).
   - **Transport:** `GET/PUT /{book}/settings/auto-approve` → `{bookObjectId, autoApprove}`; the PUT
     body must be a JSON **boolean** (`Boolean.parseBoolean(asText())` in the generic deserializer would
     read `"yes"` as false, so the route parses the raw body and 400s anything else); 403 under the same
     `requireBookUpdate` gate as the review routes; `GET /workflow` and `/review-queue` carry
     `autoApprove`.

   **Amended 2026-10-11 in P1-6 planning** (architect ruling Q3, `PictureBookV3Implementation.md`
   §8.6 / §6.C item 44 — *recorded deviation, not yet ratified*): the "hook point" above stays exact
   for the synchronous `/regenerate` path (`executeNodeRun`, once, after `closeRun(COMPLETED)`). The
   **async render worker** (§21, `PbRenderJobUtil`) instead applies the same `autoApprove` **after each
   stage** that closes DONE/DONE_UNVERIFIED, while the node claim is still held — so a multi-stage
   render-all approves and pins as it goes rather than leaving everything pending until the last stage,
   and a cancel part-way leaves the finished stages approved. Never on a cancelled or failed stage;
   Test mode still excluded; the three guards and the never-throws contract are unchanged.

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

**As implemented 2026-10-10 (Phase 1, P1-2) — `PbStaleUtil` in `olio/picturebook/`.** The
contract above holds with these bindings to the code:

- **Why a separate scan.** `canonicalInput`'s ref token is `ref:<model>:<objectId>:<STORED refHash>`,
  so `recomputeStatus` cannot see an edit until `binding.refHash` is re-persisted, and only
  `PbPipelineUtil.bindRecord` (a run) does that. `PbStaleUtil.scan(user, book, workflow, force)`
  recomputes each bound record's hash through `PbWatchedFields.computeRefHash` and compares it to the
  stored `refHash`. **The scan never rewrites `refHash`** — that stays a run-time write — so a drifted
  binding keeps reporting drift until the next run re-binds it. Only `nodeStatus = STALE` is persisted,
  through `PbGraphUtil.persistStatus` as the viewing user (PBAC-checked, idempotent, never admin, never
  a create). A viewer who may not update the nodes gets the same report with `markPersisted:false`;
  nothing is thrown from a read.
- **Eligibility.** Only nodes with `inputHash != null` whose stored status is not FAILED / SKIPPED /
  RUNNING are ever marked. A node that has never run cannot be stale; a running node is left to its
  run; terminal failures stay terminal.
- **Reasons (per stale entry).** `hashChanged` (recomputeStatus says STALE), `refDrift` (a bound
  record's live hash differs from the stored `refHash`; `driftedBindings[]` lists role/model/objectId),
  `propagated` — any eligible non-root node downstream of a `hashChanged`/`refDrift` root in **this**
  scan via the binding adjacency (`markStaleDownstream` semantics, applied as a closure) — and `marked`
  (stored STALE that nothing in this scan explains: no live root upstream). Priority
  `hashChanged` > `refDrift` > `propagated` > `marked`, so a stored STALE downstream of a still-drifted
  root reads `propagated` on every scan, not `marked` on the second *(edited 2026-10-10 after the P1-2
  verifier's Q4: the first implementation let a persisted mark outrank propagation, which flipped the
  reason between scans; `TestPbStale.case02` pins `propagated` on both scans and `case09` pins
  `marked` for a stored STALE with no live root)*. Every entry carries `affectedSceneIndexes`: the
  `sceneIndex` of each eligible `scope == "scene"` node in its downstream closure.
  `PbServiceFacade.affectedByRecord(user, book, refModel, refObjectId)` answers "which scenes does
  this character touch / which are stale because of it" from the same scan.
- **Undetermined.** If a bound record cannot be read (`computeRefHash` → null) its bindings are
  skipped with one WARN per record; its nodes are *not* marked, and the ref key is recorded in
  `Scan.undeterminedRefs` (surfaced as `staleUndeterminedRefs` on `GET /{book}/workflow`). Deleted
  records are therefore caught by `touch` from the PB delete paths, not by the scan. Reading a bound
  charPerson follows the dynamic foreign rule on `olio.charPerson.statistics` and reading the book
  follows `olio.pb.book.world`, so a collaborator needs Read on those records too, not only on the
  book/workflow groups (`TestPbStale.case07`).
- **Cache + invalidation.** One in-process scan per workflow, TTL 30 s (`DEFAULT_SCAN_TTL_MS`), at most
  256 cached. **Only a complete scan is cached** — `complete = markPersisted && undeterminedRefs.isEmpty()`
  (surfaced as `scanComplete`) — so a read-only or partially-sighted viewer's narrower result is never
  served to a user who could see or persist more; an incomplete scan is that caller's own view and is
  recomputed by the next caller *(edited 2026-10-10, P1-2 security-reviewer Finding 1; `case07`/`case08`
  assert `cached(wfId) == null` after the viewer's scan)*. Each workflow carries an invalidation
  epoch: `scan` captures it before computing and `put` refuses the result if the epoch moved, so a
  scan overtaken by a write is cached by nobody (`TestPbStaleCacheEpoch`). The scan's own STALE
  marks are written through `persistStatus(user, node, STALE, invalidateScan=false)` — they are already
  reflected in the result and do not count as foreign writes — and an incomplete scan that wrote any
  mark invalidates the workflow so no older cached scan outlives it. Every other PB graph write in the
  JVM invalidates its workflow's entry (`addNode`, `addBinding`, `addRecordBinding`, `persistRefHashes`,
  `persistStatus`, `patchField`, `patchNodeScope`, `bindNode`, `bindRecord`, `failNode`,
  `stampLastRunAt`, `renameHandle`, `detachCharacterFromGraph`, `deleteBinding` *(added 2026-10-10,
  verifier must-fix)*; `saveCanvas` does not — the scan carries no geometry). `PbStaleUtil.touch(record)`
  / `touch(model, objectId)` invalidates every workflow with a binding to that record and is called from
  `mergeCharacters`, `deleteCharacter`, `tagApparelSceneIndex`, `mergeChapter` (each shadow, by model +
  objectId) and `recopyChapter` (`touchBook`). `listStale` always forces a fresh scan; `workflowView` /
  `nodeView` serve the cache.
- **Propagation bound, stated.** `touch` is one-JVM. An edit through a generic `PATCH /rest/model`, the
  Olio character editor, Console7, or a second Service7 instance is seen by the next `listStale`
  (immediate — forced) or by `workflowView` after the TTL. There are no hooks in `AccessPoint.update`.
- **Watched set unchanged (`watched/v2`).** `description`, `narrative.sdPrompt` and `statistics` are
  not watched, so editing them never drifts a binding; the `pbDescription` attribute and apparel legs
  of `computeRefHash` are present but currently dead (nothing writes them). Whether to widen to a
  `watched/v3` is an open question for Stephen before P1-10 (tracking §6.C).

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
4. **[2026-10-17 (P3-5), as built — `location`:** the member is `{locationId, variantId, revisionHash,
   renderArtifactId, description}`. `locationId`/`variantId` are the objectIds of the scene's bound
   `olio.pb.location`/`olio.pb.locationVariant` (both null when the scene names none); `revisionHash` is the
   location's watched-field hash as read for this plan (`PbWatchedFields.refHashOf` on the one record read —
   never a second read); `renderArtifactId` is the approved render the plan carries (the variant's when a
   variant is named, else the location's; null → `LOCATION_TEXT_ONLY`, §19); `description` is the location's
   subject text (description, then the variant's qualifiers) followed by the scene's own setting text —
   composed, not replaced, because the setting is the compiled prompt's MAIN clause and dropping the scene's
   action/mood for a located scene would be a silent loss until the Phase 4 composition model. Key names
   deviate from the sketch above (`objectId, variant, text`) to follow the other members' `*Id` idiom; null
   members are omitted from the JSON, so a plan for a scene without a location serialises exactly as before.
   `inputs` keys: `location` (binding role `location`, ordinal 0) and `locationVariant` (the same role's
   ordinal 1 — a key naming that binding, not a role of its own). A pre-P3-5 SCENE manifest carried the
   **scene's** objectId in `locationId`; the first re-plan of such a scene is a one-time hash inequality, not
   a re-roll. Built by `PbRenderPlanUtil.resolveSceneLocation`; Implementation §6.C 178 (D1, D6).]**
5. **[2026-10-20 (P4-7), as built — `actors`, `elements`, `intent`:** `actors[]` carry the shape above plus `name` (the compilers and
   the scene card name people; a row named only by its P4-5 handle `actor.<composition>.<n>` carries no `name`, and a row `count` is
   spoken only when > 1) and an explicit `referenced` boolean decided by `PbRenderPlanUtil.actorsOf`, not derived from
   `identity.portraitArtifactId`; `role` is one of `HERO, SUPPORTING, NAMED_BACKGROUND, EXTRA, CROWD` with the Phase 2
   `FOREGROUND`/`BACKGROUND` kept as legacy aliases; a pre-P4-7 manifest's `description` reads into `identity.text` and is never
   written back (one-time hash inequality, as the P3-5 `location` precedent). `elements[]` are `{type, name, description, box, depth,
   importance, itemId}` — `description` is `PbComposeUtil.elementText` (the promoted item's text when one is linked, P4-6), legacy
   `priority` reads into `importance`. `intent` is `{promptOverride, raw}`: the composition's `promptOverride` when the request names
   none; `raw` is still a request-level toggle (no composition field yet — `promptLocked` does not map to it). `camera` is not yet
   filled from the composition's shot/viewpoint fields (P4-8). Rows are read as the planning user through the book's `groupId`; a
   character the user cannot read is planned from the row's own text as `ACTOR_UNRESOLVED`; a composition that cannot be read at all is
   one `ACTOR_UNRESOLVED` with no actorId. On the wire an empty `actors`/`elements`/`degradations` is absent, not `[]` (every
   PictureBook route serialises `NON_EMPTY`) — consumers default to `[]`. Implementation §6.C 193 (a)–(d), (j), (o), (p), (ff), (kk),
   (ss), (tt).]**

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
- **[2026-10-17 (P3-5), as built:** `TEXT_ONLY` **carries** the approved render
  (`plan.location.renderArtifactId`) and does not consume it — the init-image / reference uses above belong to
  `SEQUENTIAL_INPAINT` / `DIRECT_REFERENCE`, which arrive in Phase 5; `PbNodeExecutor` and `StageRequest` are
  unchanged. A render carried but not used by the stage is **not** a degradation and does not change the
  strategy name or reason. Implementation §6.C 178 (D4, option A).]**
- **[2026-10-20 (P4-7), as built — top-k:** the slot count and the reference decision live in `PbRenderPlanUtil.actorsOf`, not the
  selector: `maxReferences − (hasLocationRef ? 1 : 0)` slots (`hasLocationRef` = the scene's location has an approved render) are
  filled by HERO / SUPPORTING (and legacy FOREGROUND) actors with an approved PORTRAIT and a policy other than TEXT_ONLY —
  NAMED_BACKGROUND only under `REQUIRE_REFERENCE` — ordered REQUIRE_REFERENCE first, then priority, then row order. Every candidate left
  out is `ACTOR_TEXT_ONLY` with its reason (`no approved portrait`, `reference limit N`, `REQUIRE_REFERENCE not met` — a degradation,
  not a refusal); EXTRA and CROWD are always text and raise nothing. The selector keeps its Phase 2 role (strategy name, stages, reason)
  and is TEXT_ONLY-only until Phase 5. Implementation §6.C 193 (f), (x), (aa), (bb), (mm).]**

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

> **Built (P4-7, 2026-10-20):** `PromptCompilers.forPick(pick)` picks the compiler by the pick's recorded family (`flux2*` →
> `Flux2Compiler`, else `SdxlCompiler`; `RendererCapabilities.forModel` only for a legacy pick without one); `PbNodeExecutor`,
> `ChatSceneRenderUtil` and the plan route all go through it. `Flux2Compiler` is prose: an opening that names the referenced actors by
> ordinal ("the first reference image" …, the location's ordinal after the actors) **only** under `DIRECT_REFERENCE` and only for actors
> the planner marked `referenced`; otherwise "Place the people described below in …" and every actor as text. Every actor gets a
> sentence by role — a referenced actor in a background role keeps its ordinal there, in the full and the detail-dropped sentence alike;
> elements are grouped HIGH / MEDIUM / LOW; style once (suppressed when the main text already ends with it);
> `SWUtil`'s identity-fidelity and "do not draw the reference images" rules verbatim (ASCII hyphen, KI-68) and only when a reference is
> spoken of; "No extra people." only when no extras/crowds were asked for; exclusions always; LoRAs appended outside the budget; `raw`
> = the override + LoRAs. Budget 512 tokens for the FLUX.2 family; the drop order above, then a MAIN cut at sentence then word
> boundaries, each step one `PROMPT_TRUNCATED` naming what was dropped (`"ELEM_LOW dropped: gulls (20 tokens over budget 512)"`); a
> prompt still over budget with nothing left to drop is recorded uncut. `SdxlCompiler`'s drop messages now name the drop the same way.
> The shared helpers (`isMeaningful` on every field, smart quotes, `appendLoras`, ordinals, `recordDrop`) are `CompilerSupport`,
> package-private and DB-free. No compiler carries policy or bias wording (tracking 189 c) and none logs a prompt. The ordinal wording
> is exercised by `TestFlux2Compiler` alone until Phase 5 — the selector is TEXT_ONLY-only in Phase 4. `InpaintRegionCompiler` and
> `RegionalPromptCompiler` are not built. Implementation §6.C 193 (e), (l), (n), (nn), (u).

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
are shown to anyone (§36.7). **[2026-10-24 (P5-1): measured — the `.39` figures are §38.5 item 5
and the `flux2*` seed (5.0 s per step per reference) is in `capabilities.json`; SDXL and Kontext
still have none. The estimate's live source is the per-server calibration record designed under
§34 Q14 (b) and built in P5-2; until then `RenderEstimate` does not read the seed either, so no
estimate is shown.]**

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

**[2026-10-17 (P3-5), as built — `LOCATION_TEXT_ONLY`:** emitted by `PbRenderPlanUtil.resolveSceneLocation` on
the BASE stage (where the render would have been used) when the scene **names** a location
(`olio.pb.scene.location`) whose render subject has no approved render. The subject is the variant when
`locationVariant` is named — the location's own render is never a substitute for a missing variant render, and
the message names the variant ("No approved render for variant Night; setting described in text") — else the
location; a subject the planning user cannot read counts as no render (`… (not readable)`). A scene that names
no location gets **no** degradation: plain text-only is the §29 default, not a shortfall. The `approvedRender`
pointer is taken as truth; the artifact's own review status is not re-read (Implementation ⚠ 175 e). The same
degradation is the **auto-approve veto**: `PbArtifactUtil.autoApprove` skips a degraded attempt
(`"degraded: LOCATION_TEXT_ONLY: …"`) so a located scene rendered without its approved place waits for a
human — transitional until the Phase 4 composition model decides which degradations are review-worthy.
Implementation §6.C 178 (D2, D3, D5).]**

**[2026-10-17 (P3-5 hardening), as built — refused references:** the resolver trusts no pointer it did not
check. A `location` outside the book's Olio world (`PbLocationUtil.sameWorld` false — reachable only through the
generic `POST /rest/model` route, which gates a foreign key on the caller's READ right, not on the parent) is
treated exactly as an unreadable one: no revision hash, no text, no render, the variant falls with it, and the
BASE degradation reads "Location <name> is not in this book's world; setting described in text". A
`locationVariant` whose own `location` is not the scene's location is refused by itself: the location keeps its
hash and text, `inputs.locationVariant` is present and null, no render is carried (D3: the location's render is
never the variant's), and the message reads "Variant <variant> is not a variant of location <location>; setting described in text". A variant
the planning user cannot read is reported as unreadable before the parent check. Both are `LOCATION_TEXT_ONLY`
on BASE — no new degradation code — so the auto-approve veto above covers them. `PbNodeExecutor.bindSceneLocation`
still binds whatever the scene points at; the binding is a watched-set pointer, not a render decision. The
same-world rule and the facade routes (`requireLocationOfBook`, `requireVariantOfBook`) are the write-side
guards; this is the read-side one. Implementation §6.C 178 (t).]**

**[2026-10-20 (P4-7), as built — the closed set, the actor codes, the summary, the veto:** `RenderPlan.Degradation.CODES` is the
seven codes above plus `COMPOSED_OUT_OF_ORDER` (P4-4); an entry whose prefix is not one of them is "unknown" and vetoes auto-approve.
`ACTOR_TEXT_ONLY` reads `"<name> rendered from description (no approved portrait | reference limit N | REQUIRE_REFERENCE not met)"`;
`ACTOR_UNRESOLVED` reads `"<row name or text> rendered from the row's text (character <objectId> could not be read)"` (the example
above was written before the composition model gave every row its own text — a row with no character is a text row, not an
unresolved one) and, for a composition or actor-row read that throws, `"The … could not be read; the scene is planned without its
actors"`; `ACTOR_FAILED` is defined and never raised before Phase 5; `PROMPT_TRUNCATED` names the drop (§14). The scene-card line is
`RenderPlan.actorSummary()`: `"N characters: R referenced, T text[, U unresolved]"` (`"no characters"` for none; "text" rather than
"regional" — there is no regional strategy yet), exposed as `planSummary` on the plan DTO and the planned node's describe map (with
`"; strategy <name>"`). The auto-approve veto is one constant, `PbArtifactUtil.AUTO_APPROVE_VETO_CODES` = `{ACTOR_UNRESOLVED,
ACTOR_FAILED, LOCATION_TEXT_ONLY, PROMPT_TRUNCATED, COMPOSED_OUT_OF_ORDER}` (182 (13) + the P4-4 code); `ACTOR_TEXT_ONLY`,
`MODEL_SUBSTITUTED` and `QA_WARN` are waived. `REQUIRE_REFERENCE` not met is filed as `ACTOR_TEXT_ONLY` and therefore waived — open
question, Implementation §6.C 193 (mm). Implementation §6.C 193 (g), (h), (i), (k), (v), (kk).]**

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
| Swarm refuses the graph (`unsupported node type`) or Comfy reports an execution `error` | `RENDERER_REJECTED` with the node id; treated like BASE/COMPOSITE failure for the stage, and the node catalogue cache is invalidated so the next attempt re-validates against the live backend **[2026-10-24 (P5-1), as built in `SwarmRendererAdapter.executeGraph`: `GRAPH_INVALID` carries every error the catalogue found (`NodeCatalogue.describe`) with `durationMs 0`; `RENDERER_REJECTED` is any Swarm `error` mentioning an unsupported node / node type, "workflow", "invalid" or "comfy" **other than a session error** (those are retried once by the client and then reported as a plain backend error, without touching the catalogue); a cancelled render stays `CANCELLED`; an `error` that is none of these is `ERROR_PREFIX_BACKEND + error`. Neither stage result carries a Comfy node id yet — Swarm's HTTP `error` string is all the adapter has; per-node error surfacing is P5-2. The node→FAILED mapping is unchanged because no executor calls the graph path in P5-1]** |
| An exception anywhere | `run` and `node` are closed as FAILED in a `finally` block (gap doc Appendix A "Other": graph runs are left RUNNING when a scene throws — **[corrected 2026-10-09: left PENDING; see the note under the opening paragraph]**) **[implemented 2026-10-10, Phase 1 P1-3, for executor runs: `PbNodeExecutor.executeNodeRun` stamps the run RUNNING (`PbGraphUtil.markRunning`) and the node RUNNING before dispatch, closes COMPLETED on success, FAILED + `error` in the catch, and a `finally` guard closes any run left open as FAILED ("Execution abandoned before the run could be closed"); `TestPbNodeRun.case01` observes RUNNING mid-call and FAILED after the throw. PB1 scene runs opened by `openSceneGraph` are still PENDING → closed, never RUNNING — recorded in the implementation state §5.5 item 25 / §6.C item 30]** |

### 21. Async execution, durability and cancellation

`AsyncJobRegistry` is in memory only (lost on restart), runs 2 jobs concurrently, and allows 4
active jobs per principal **[verified 2026-10-09: `AsyncJobRegistry.java` `MAX_CONCURRENT_JOBS=2`
`:66`, `MAX_ACTIVE_JOBS_PER_PRINCIPAL=4` `:90`, `COMPLETED_TTL_MS` 30 min `:75`,
`MAX_RETAINED_JOBS=200` `:78`]**. V3 does not make the job registry durable. **The graph already is**:
- **A render job = an `olio.pb.run`** whose `requestedNodeIds` are the terminal nodes requested
  **[2026-10-11 (P1-6): as built, `requestedNodeIds` is the execution set — the terminals' upstream
  closure filtered to executable, unpinned, PENDING/READY/STALE/FAILED nodes, in topological order —
  so a resume re-plans from exactly what this run was asked to execute; the terminals are in the
  submit response, not on the run (Implementation §6.C item 49)]**.
  The async job is just the worker executing that run. Kinds: `pb.compose` (§9.1), `pb.render`
  and `pb.renderAll`, alongside today's `pb.extractScenes`, `pb.retryFailedChunks`, `cb.create`,
  `cb.render` and `chat.chain`. There is **no run endpoint today** (`olio.pb.run` is written by the
  executor but not exposed) **[verified]**.
- **Progress** = node statuses plus the current stage, also mirrored into the job's
  `SummarizeProgress` token (`org.cote.accountmanager.olio.llm.SummarizeProgress`) for polling.
- **Restart recovery:** on boot, any run left RUNNING is marked INTERRUPTED (add this value to
  `PbRunStatusEnumType`; see §1 for its current values) **[2026-10-10, Phase 1: `INTERRUPTED` added in P1-1; RUNNING is
  now actually written — by `PbNodeExecutor.executeNodeRun` at execution start (P1-3), so a process lost mid-render
  leaves a RUNNING run + RUNNING node for this sweep to find. Before P1-3 nothing wrote RUNNING (P0-8 finding) and the
  sweep would have had nothing to recover]**. Its RUNNING nodes reset to READY. The Ux offers "Resume", which submits a
  new run for the unfinished requested nodes. Completed stages are reused because their artifacts
  exist and their input hashes match. This mirrors the extraction checkpoint's "the job covers a
  lost connection; the checkpoint covers a lost process".
  **[2026-10-11 (P1-7), as built — five refinements:**
  **(a) PENDING runs are swept too.** `PbRenderJobUtil.submit` writes the run PENDING before the job
  is accepted, so a stop between those two writes (or a job still queued behind the 2-wide pool)
  leaves a PENDING run that no worker will ever close; the sweep marks it INTERRUPTED with the text
  "the server stopped before this run was dispatched". **(b) Workers close their own runs on a
  graceful stop.** `RestServiceEventListener.shutdown` raises `AsyncJobRegistry.beginShutdown()` as
  its first act; a worker that sees the flag at a stage boundary, while waiting for a render lane, or
  as the cause of a thrown stage, returns the node to READY and closes the run INTERRUPTED
  ("Interrupted by shutdown after N of M stage(s)") while the database is still reachable — shutdown
  outranks cancel. The boot sweep is for the process that did **not** stop gracefully (`kill -9`,
  `docker restart -t 0`, power loss) and for stages the flag could not interrupt (an in-flight SD
  call is a blocking Jersey client). **(c) The sweep (`PbRecoveryUtil.sweepInterrupted`) runs after
  `provisionDefaultOrganizations()` in the boot listener**, reads the RUNNING/PENDING runs and RUNNING
  nodes with principal-less raw queries (the same idiom as `PbOrphanUtil.raw`), and writes each one
  **as its owner through `AccessPoint`** — never as the org admin, never via `RecordUtil`; an owner it
  cannot resolve, or a write PBAC refuses, is counted and reported, not forced. It skips any run or
  node that is in flight in this JVM (`PbRunCancelRegistry` / `PbNodeInflight`), which makes it safe
  to call from a test while jobs run. **It assumes exactly one Service7 JVM per database**: a second
  JVM's live runs are indistinguishable from this JVM's dead ones, and a second sweep would interrupt
  them. **(d) Resume is a new run of kind `pb.resume`** (`POST /{book}/run/{runId}/resume`, 202):
  the interrupted run's recorded `requestedNodeIds` are re-planned by the same drift scan the
  original submit used, so DONE stages are reused and only the unfinished ones execute; the
  interrupted run is never rewritten — it stays as the record of what happened, and the response
  carries `resumedFromRunObjectId`. Only an INTERRUPTED run with a recorded execution set is
  resumable (409 otherwise; 409 "Nothing to resume" when every requested node is already current).
  **(e) The run is now readable:** `GET /{book}/run/{runId}` (200) returns status, counters, error,
  the execution set with each node's current status, `inFlightInThisProcess` and `resumable` — the
  predicate the Ux's Resume affordance (P1-10) keys on.]**
- **GPU serialization:** a per-SD-server render semaphore (permits from capabilities, default 1)
  inside the adapter, so two concurrent jobs never stack on one GPU. LLM steps (compose, location description) use a
  separate lane. **[2026-10-11 (P1-6): built as `PbRenderLanes` — one fair semaphore per SD server
  key, permits boot-pinned by `pb.render.sdPermits` (default 1) since there is no adapter/capabilities
  object until Phase 2; acquired per SD-backed stage, not per run, so concurrent runs interleave;
  the wait observes cancel. LLM stages take no permit. Per-JVM only — the DB's RUNNING run/node is
  the cross-JVM signal, which P1-7's sweep reconciles.]**
- **Cancellation** (cooperative, at stage boundaries):
  - stop scheduling stages;
  - call `adapter.cancelActive` (Swarm's interrupt, which stops the Comfy backend job)
    **[2026-10-11 (P1-6): NOT in Phase 1 — there is no adapter yet and `InterruptAll` is not called
    (architect ruling Q7, Implementation §8.6). An in-flight Swarm call runs to completion; the worker
    discards its bytes on return (`PbNodeExecutor.discardIfCancelled`) and the stage throws
    `PbStageCancelledException`. An in-flight LLM stream IS aborted through the job's
    `LLMConnectionManager` cancel scope. Adapter-level interrupt is a Phase 2 item with the adapter.]**;
  - discard in-flight output;
  - the run becomes CANCELLED (start writing this reserved value) **[2026-10-11 (P1-6): written by
    `PbRenderJobUtil` — at the pre-stage check, on a cancelled stage, and by
    `AsyncJobWork.onCancelledBeforeStart` for a job cancelled while still queued. The node the
    cancelled stage had stamped RUNNING is returned to the status `recomputeStatus` derives from its
    pre-stamp handle. Cancel is reachable two ways that flip the same token:
    `POST /{book}/run/{runId}/cancel` (book-gated, `PbRunCancelRegistry`) and
    `POST /rest/job/{jobId}/cancel` (submitter only).]**;
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
  **[2026-10-29 (P6-1 / P6-2), as built: `reviewStatus` / `durationMs` arrived in Phase 0–1; `attempt`
  (string 64), `variantIndex` (int) and `archivedAt` (zonetime) were added in P6-1 by the boot DDL
  patch — `archivedAt` carries the DDL default `now()` on every row and is trusted only while
  `reviewStatus = ARCHIVED`. N comes from the preset only (`qualityPresets.json` `variants`: DRAFT 1,
  STANDARD 2, FINAL 4; no per-request override yet). `PbNodeExecutor.renderPlanned` runs the plan's
  whole stage sequence N times sequentially, variant i seeded `seed0 + i` from the renderer's reported
  seed for variant 0; when the renderer reports none the later variants are random and the attempt
  carries `VARIANT_SEED_UNKNOWN` (accepted by auto-approve). A later variant's failure does not fail
  the node: what rendered stands, the index gaps, and `VARIANT_FAILED` vetoes auto-approve. Only
  `variantIndex 0` is auto-selected (rule 1). ChapBook pages and LOCATION renders stay at 1 variant.
  Implementation §6.C 212 (a)–(e).]**
- The variant strip shows the current attempt plus the approved artifact. Older attempts sit behind
  "History". **[2026-10-29 (P6-4), as built: the node detail panel's "Artifact Revisions" strip
  groups each role's chain by `attempt`, newest attempt first (by its highest revision), tiles
  `v{n} · s{seed}` with a status tint; rows with no `attempt` (Test mode, pre-P6) are one group each.
  ARCHIVED rows hide behind "Show archived (n)" rather than a separate History view; a two-up compare
  pane replaces per-tile zoom; an archived tile whose bytes are gone says "bytes deleted". The server
  emits `seed: 0` for rows without one (long column) — the strip shows a seed only when > 0.]**
- **Retention:** keep the approved and selected artifacts forever. Keep the last K attempts (default
  3) per node. Move older GENERATED/REJECTED variants to ARCHIVED, and delete their bytes after D
  days (default 30). **This is a new sweep routine**, not an extension of an existing one:
  `PbOrphanUtil` deliberately skips artifacts (`PbOrphanUtil.java:53-54`) and `PbHealthUtil` is a
  stale-graph scan, so neither can be reused as-is. Put it beside them in
  `org.cote.accountmanager.olio.picturebook` (e.g. `PbRetentionUtil`) and expose it through the
  same health routes. Stage intermediates (BASE, regions) keep only those referenced by a retained
  final artifact. **[2026-10-29 (P6-3a / P6-3b), as built: `PbRetentionSelector` is the pure policy
  (K per (node, role) — not per node — attempts ordered by their highest revision; IN_REVIEW is kept
  as well as selected / APPROVED; an ARCHIVED row's bytes are due at `archivedAt + D`; stage roles kept
  only while a kept final references them; K = 3 / D = 30 are its constants). `PbRetentionUtil.scan` /
  `apply` run as the caller through `AccessPoint`; `apply` archives via `PbArtifactUtil.archive`
  (409 on a selected / APPROVED row) and deletes bytes by removing the `data.data` row as the caller,
  then clearing the artifact's `data` FK — the artifact row stays, so the strip can still show the
  slot. Not on the health routes: `GET /{book}/retention?keepAttempts=&deleteBytesAfterDays=` (dry
  run) and `POST /{book}/retention/apply` (`admin,user`; 400 outside 1..K-max / 0..D-max), plus
  `GET|POST /retention/org[/apply]` (admin only) — manual, no scheduler; no un-archive. The Ux752
  canvas toolbar's "🗄 Retention" dialog drives dry run → Apply (Apply enabled only for a dry run of the
  current K / D with ≥ 1 ARCHIVE / DELETE_BYTES item). Implementation §6.C 212, §13.6.]**

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

**[2026-10-21 (P4-8), as built — the page composition exists and carries the prompt; the scene copy stays until P4-10. Superseded by the P4-10 block below where the two differ (write order, partial-write body, the STORY-scene edge).**
A page's `CHAPBOOK_PAGE` composition is created lazily on the first prompt write, as the request user through
`AccessPoint`, named `page.<sceneObjectId>` **in the book's group** (not the scene's — a chapter-group scene would
otherwise put it where the planner and the board never look), with `source` USER (a human set) or LLM (regenerate);
no GET, planner or executor creates one. `ChapBookUtil.setSceneLandscapePrompt` and `regenerateSceneLandscapePrompt`
write **scene first** (`sdPrompt`/`promptLocked`, the pre-P4-8 fields), mark the page's node STALE, then write the
composition's `promptOverride`/`promptLocked` (a human set also sets `userEdited`); a second write that fails is
reported, never hidden (ruled 2026-10-21 "richer body"): the routes answer `200 {updated:true, compositionUpdated:false,
error:"Scene <id> sdPrompt persisted but its page composition was not: …"}` (`PUT …/prompt`; regenerate adds `sdPrompt`), a
complete write `{updated:true, compositionUpdated:true, compositionObjectId}`, nothing written `{updated:false,
compositionUpdated:false}` — never a bare `200 {updated:false}` for a partial; the in-process `setSceneLandscapePrompt` /
`regenerateSceneLandscapePrompt` keep the 500 so a render never follows a partial write (Implementation §6.C 194 ee, 195 a, b).
`PbNodeExecutor.executeChapBookPage` reads the composition's `promptOverride` when the scene copy is not meaningful
(the state a board edit or the P4-10 migration leaves behind) and refuses with 409 "Page N has no prompt; set or
generate its landscape prompt before rendering it" when neither carries one; the plan's `location.description` is
then the composition text verbatim. On a page, `promptLocked` is only the regeneration guard; the text renders
either way. The ChapBook recovery path and a board PATCH of the page's `promptOverride` still move only one copy
(scene, resp. composition) — the mirror is re-synced on the next set/regenerate, and P4-10 retires the scene copy.
`bindSceneLocation` (P3-6) now catches a binding failure and records it as a `location:` degradation on the
result instead of failing a rendered page. Verified by `TestChapBookPageComposition` (Objects7, `am7db` + the
in-stack LLM; the executor case against a dead renderer), `TestChapBookServiceRenderPath` (Service7 H2) and the
render-path suites against live `.39` — `PictureBookV3Implementation.md` §12.8, §6.C 194 (b), (c), (g), (k), (x), (ee).
Two edges found by the (ee) live proof, both open: the prompt routes have no ChapBook check, so on a STORY scene they create an
unread `CHAPBOOK_PAGE` row beside the SCENE one (the board reads SCENE for a STORY book) — left for P4-10 with the scene-copy
retirement; and `…/prompt/regenerate` with no `chatConfig` in the body uses the library `contentAnalysis` connection, which on the
Docker stack has no LiteLLM key, so callers pass the user's config name as the Ux752 editor does — `PictureBookV3Implementation.md`
§6.C 196, §6.G 4. **2026-10-22 (P4-9):** the composition board follows the same rule — its header's Chat Config control sends the
pick's name as `chatConfigName` on every compose path (empty-state compose, Re-compose, Compose book…); without it the compose job
ended `source=MIGRATED` with no error on the page (`PictureBookV3Implementation.md` §12.9, §6.C 197 ee).]**

**[2026-10-22 (P4-10), as built — the composition is the authority; the scene's `sdPrompt` / `promptLocked` / `configOverride`
are its server-maintained mirror, kept (not deleted) for the readers that still consult them.** "Retire" was taken as an
authority flip, not a field deletion (`PictureBookV3Implementation.md` §6.C 198 a): `chapBook.js`, the §28.1 v1 render path,
`PbWatchedFields` and `PbConfigUtil.mergeTiers` read the scene fields, so each composition write is followed by one PATCH of the
differing scene fields (`PbBookUtil.mirrorCompositionOntoScene`, as the request user; a refused mirror is reported, never hidden,
and never fails the authoritative write). **Write order flips:** `setSceneLandscapePrompt` / `regenerateSceneLandscapePrompt`
and the recovery path write the `CHAPBOOK_PAGE` composition FIRST, then mirror `sdPrompt`/`promptLocked` onto the scene, then
mark the node STALE; the body is `{updated, compositionUpdated (= updated), sceneUpdated, promptLocked[, compositionObjectId][,
error]}` where `updated` now means the composition was written and a partial is `updated:true, sceneUpdated:false, error:"Page
composition <oid> persisted but scene <id> sdPrompt was not: …"`; the in-process setters keep the 500 on a partial. A scene with
no readable book is 500 before any write; **a STORY scene on these routes is 409** "belongs to a story book; edit its composition
board instead" (closes the P4-8 edge). `PATCH …/composition` mirrors `configOverride` onto the scene for either purpose and
`promptOverride`/`promptLocked` for a page only — a SCENE composition's `promptOverride` is a board direction the planner feeds
the STORY prompt node, never scene text — and answers `sceneMirrored` (+ `sceneMirrorError` when refused). `setSceneConfigOverride`
writes the composition's `configOverride` first; the composition tier reaches `mergeTiers` through the node mirror
(`mirrorSceneOntoNode(user, scene, node, book)`: node `configOverride` = the composition's when set, node `promptText` = the page
composition's `promptOverride` on a ChapBook), so the order is book → scene → composition → node as §8 asks with no lookup on the
hash path. **Readers are composition-first:** `executeChapBookPage`, `executeLandscape`, the LANDSCAPE_PROMPT node and
`executeComposite` read the composition's `promptOverride` and fall back to the scene; the facade's render gates use the same
order. **Migration (§9.1 / §28):** the first compose job on a story book creates a `source=MIGRATED` SCENE composition per legacy
scene (setting/action/mood/`sdPrompt`→`promptOverride`/`promptLocked`/`configOverride`) in the book's group, idempotent, never
touching an existing or user-edited one, before the LLM loop so the recompose keeps the legacy prompt/lock/config; the job result
carries `migration` counts. **Planner:** with a SCENE composition `locationText` takes setting/action/mood per field from the
composition with the scene as fallback, appends `props`, and excludes the scene's `sdPrompt` (it is the migrated direction);
`cameraText` (shot type, viewpoint, free text) reaches `RenderPlan.Camera` and the SDXL camera stage; row handles
(`actor.<oid>.<n>` / `element.<oid>.<n>`) never reach compiled text (197 jj). **Four hardenings from the P4-10 reviews (2026-10-23):**
the v1 recovery path overlays a page's locked composition `promptOverride` onto the in-memory scene before it decides to regenerate
(`ChapBookUtil.overlayPageComposition`), so a stale or refused scene mirror can never cause a locked prompt to be regenerated, and it
skips the scene mirror when the composition write was refused (the mirror never carries text the authority did not take); a board
`promptOverride` is sanitised exactly like the scene route (`ChapBookUtil.sanitizeText`) before it is mirrored; a scene whose migration
failed is skipped by the LLM loop (`hardFailure`, `status:"migrationFailed"`) so a fresh LLM row cannot drop its legacy lock; and the
render gate judges the **plan**, not the compiled string — `PbNodeExecutor.hasSubject` requires a meaningful intent, location text, or an
actor/element with a name or description, because a MIGRATED composition's default shot/viewpoint (and the style/palette/photoreal
boilerplate) make the compiled SDXL string non-blank for a scene with nothing to depict (§6.C 198 o–r). `Flux2Compiler` still ignores
`RenderPlan.Camera` — Phase 5. Results, tests and the live proof: `PictureBookV3Implementation.md` §12.10.]**

**[2026-10-23 (P4-12), as built — migrate-first on every compose path, and a written compose stales the render.** The lazy
render hook (`PbComposeUtil.ensureComposition`) and the per-scene route (`PbServiceFacade.composeScene` → `composeSceneMigratedFirst`)
now do what the job's up-front pass does: a story scene with no SCENE composition gets its `MIGRATED` row (`sdPrompt`→`promptOverride`,
`promptLocked`) written **before** the LLM compose, so no path can create a fresh LLM row that drops a legacy lock; when the LLM then
produces nothing the MIGRATED row stands and is reported `written:true, migratedFallback:true, migratedFirst:true` (a persist-phase
failure stays `hardFailure`; a cancel after the migration keeps the row). Compositions are not bound node inputs, so **every LLM-written
compose marks the scene's node STALE explicitly** (`PbComposeUtil.staleAfterCompose` → `PbBookUtil.markSceneNodeStale`: in the compose
job before its Quick-start render, and on the per-scene route — never in the lazy hook, which is itself the render; a MIGRATED row that
stands after an LLM miss stales nothing, exactly as the job's up-front pass does, and the result still reports `nodeStatus`) and **every wardrobe
pin stales the chain** — `migrateSceneTags` snapshots `resolveOutfits` before and after the pass and stales every scene whose resolved
outfits changed (`affected`/`staled`, surfaced as `migration.tagsAffected`/`tagsStaled`); the migration's own MIGRATED write never stales
(nothing rendered from it yet). Results carry `nodeStale`/`nodeStatus` per scene and `staled` per job. Bound stated: a compose from
another JVM (Console7, generic `PATCH /rest/model`) does not stale; the next explicit compose or edit does. `PictureBookV3Implementation.md`
§6.C 200, §12.12.]**

**Other ChapBook specifics:**
- **Locations:** a ChapBook book has its own world, so the same world-scoped location library
  (§7.1) applies. A poem cycle that keeps returning to "the harbour" can share one approved place.
  Locations stay optional per page.
- **Composition timing:** ChapBook already composes up front, in order, at create time (§9.1).
- **Old path:** ChapBook's current render path (`ChapBookUtil.renderResolvedScene` /
  `renderChapBookSummary`) follows the same disable → verify → remove lifecycle as PB1 (§28.1).

**[2026-10-11 (P1-9), as built — the Phase 1 subset of this section.** Phase 1 puts ChapBook *rendering*
on the graph; the composer / `CHAPBOOK_PAGE` composition / `settingText` mapping above are Phase 2
(§30) and are unchanged by P1-9. What runs today when `chapbook.renderPath=v3`:
- **One LANDSCAPE node per page**, created on first render by `PbServiceFacade.ensureChapBookPageNode`
  with the deterministic handle `PbPipelineUtil.landscapeHandle(sceneObjectId)`, `scope=scene`,
  `scopeRef=` the scene objectId, `sceneIndex` = the page index, in the book's existing workflow
  (`/…/Worlds/<slug>/Workflow`, which `/chap-book/create` already builds). The **scene stays the
  authority**: `scene.sdPrompt` is mirrored to `node.promptText` and `scene.configOverride` to
  `node.configOverride` (`PbBookUtil.mirrorSceneOntoNode`) so the review views show the page's own
  prompt, and `scene.sceneNode` is linked to the node. `sdPrompt`/`promptLocked` are the Phase 1 stand-in
  for `promptOverride`/`promptLocked`.
- **Same job, same executor.** A page render is `PbRenderJobUtil.submit(KIND_RENDER, force=true)` and a
  bulk render one `KIND_RENDER_ALL` job (`PbServiceFacade.requestChapBookPageRender` /
  `requestChapBookRenderAll`); `PbNodeExecutor.executeChapBookPage` renders the page from
  `scene.configOverride → book sdConfig → defaults` with no actors, writes the artifact into the node's
  version chain (`PbArtifactUtil`, role `landscape`, `supersedes` the previous selection), then points
  `scene.imageObjectId` at it and **clears `scene.imageStale`** as the requesting user. Review
  (select/approve/reject), auto-approve (`APPROVED_BY_AUTO` + pin) and the review queue apply unchanged.
- **`imageStale` ↔ node STALE, both directions.** A prompt edit (`ChapBookUtil.setSceneLandscapePrompt`
  → `PbBookUtil.markSceneNodeStale`) or a config-override write re-mirrors the scene onto the node and
  persists `STALE`; a successful render clears both the node status and `imageStale`. The v1 merge-up
  still writes `imageStale=true` on text edits; on v3 that flag is also what the stale scan reads.
- **The old entry points are the v3 front door, not a parallel path.** `POST /chap-book/scene/{id}/generate`
  and `POST /chap-book/render/{book}` keep their URLs; on v3 `ChapBookUtil.renderResolvedSceneV3` /
  `renderChapBookSummaryV3` submit the job and either wait (page ≤ 30 min, book ≤ 55 min — both under
  nginx's 3600 s) and answer 200 with the settled result, or answer **202 `PENDING`** with
  `jobId`/`runObjectId` for the client to poll `GET /rest/job/{jobId}`; `?async=true` on the bulk route
  answers 202 immediately. The prompt pre-step (`resolveAndPersistScenePrompt`: locked → stored → LLM
  recovery) runs before the submit, so an un-prompted page is `SKIPPED_NO_PROMPT` (409 inside the facade,
  mapped to the skip) and never gets a node; a bulk request with no prompted page at all is a 409.
- **Not on v3 (carried to P1-10):** the per-request client `sdConfig` overlay (dropped with a WARN; persist
  it as `scene.configOverride` or the book sdConfig instead), and a re-render of a pinned (approved) page
  (409 — unpin first). With `chapbook.renderPath=v1` (the production default) nothing above runs and
  `renderResolvedScene` / `renderChapBookSummary` behave exactly as before.
Verified by `TestChapBookV3Render` (8 cases, SD-live on `.39`), `TestChapBookServiceRenderPath` (Service7
H2 contract) and `e2e/chapBookRenderPathV3.spec.js` (Docker stack) — `PictureBookV3Implementation.md`
§5.5 items 43–47, §6.C 85–99, §8.9.]**

**[2026-10-14 (P2-6), as built — ChapBook goes V3.** `chapbook.renderPath` now defaults to `v3` at every
surface (§28.1 Stage 2 block); `v1` is the rollback. What changed on the v3 path since P1-9:
- **Pages compile through TEXT_ONLY.** `PbNodeExecutor.executeChapBookPage` no longer runs the v1 SD block;
  it shares `renderPlannedScene` with the RENDER_PLAN node. `PbRenderPlanUtil.buildScenePlan` builds the
  page's `RenderPlan` as a zero-actor scene (strategy `TEXT_ONLY`, the book's `olio.pb.style` chain, the
  `ModelResolver` pick, the preset's steps); the plan is persisted as the node's `renderPlan` artifact
  **before** anything is checked (§12 rule 1 — a refused page still leaves its plan); an unresolved BASE
  model is a 409 (nothing installed matches) or 503 (inventory unreadable) naming the resolver's reason;
  `SwarmRendererAdapter` sends exactly the BASE stage. The page's `sdPrompt` is the plan's `location.text`,
  compiled verbatim (whitespace-trimmed) as the MAIN clause — the prompt shown is the prompt sent. The image
  lands under role `landscape` on the page's LANDSCAPE node (node type and role unchanged from P1-9;
  `CHAPBOOK_PAGE` above is the Phase 4 composition kind, not a node type); the scene's
  `imageObjectId`/`imageStale` write-back and the DONE_UNVERIFIED close are as before; the result adds the
  plan keys (`planArtifactObjectId`, `planContentHash`, `strategy`, `stagesPlanned`, `stagesRendered=[BASE]`).
- **Composer up front, unchanged.** The continuity composer (`chapBook.landscape-prompt`: theme/mood/keywords
  plus the prior two prompts) still runs in order at create time (§9.1) and still writes `sdPrompt`; the
  `settingText`/`mood`/`cameraText` mapping above arrives with the composition model (Phase 4), as does the
  `promptOverride`/`promptLocked` rename — `sdPrompt`/`promptLocked` remain the fields.
- **What the v1 page path applied and the plan path does not** is reported per render under
  `configNotApplied` (`HIRES`, `STYLE_CLAUSE`, `DEFAULT_NEGATIVE`), never dropped silently: the `sd.config`
  tier's `style` clause (the compiled style is the book's `olio.pb.style` chain only), `hires` (BASE only, no
  refiner pass) and the v1 default negative prompt (the negative is the style's exclusions plus the config's
  `negativePrompt`; a book with neither sends none). These are *not* degradations: a degradation is the
  render falling short of its own plan (a substituted model, a planned stage not rendered) and vetoes
  auto-approve (§10 rule 5), and every deployment-default `sd.config` carries `hires` and a `style`, so
  counting them would leave auto-approve unreachable on the plan path. Steps come from the preset, not from
  the book-tier or scene-override `steps` (`PictureBookV3Implementation.md` §6.C 164 x / 165). The
  per-request client `sdConfig` overlay stays dropped (P1-9).
- **Review queue:** the `renderPlan` artifact every plan-driven node carries is excluded from
  `listReviewQueue` — a plan is the record of a render, not a reviewable output.
- **Rollback proof:** `TestChapBookRenderStyle` pins `chapbook.renderPath=v1` (`:79`) and asserts the v1 SD
  block byte-for-byte; `PbRenderPath.configure` logs `legacy ChapBook render path re-enabled (rollback)` at
  WARN whenever `v1` is applied.
Verified by `TestChapBookV3Render`, `TestPbRenderPlanExecutor`, `TestPbRenderPath` (Objects7, SD-live on
`.39`), `TestChapBookRestContract` / `TestChapBookServiceRenderPath` / `TestFeatureConfigService` (Service7
H2) and the ChapBook Playwright group (Docker stack) — `PictureBookV3Implementation.md` §10.6, §6.C 165.]**

**[2026-10-17 (P3-6), as built — ChapBook optional locations.** A page is an `olio.pb.scene`, so it already carries
the nullable `location` / `locationVariant` FKs of §7.2, and the plan path treats a page exactly as a scene:
`PbNodeExecutor.executeChapBookPage` now projects both FKs when it reads the page, and `PbRenderPlanUtil.buildScenePlan`
→ `resolveSceneLocation` (§19, P3-5) fills `plan.location` for it. What that means per case:
- **Unset (the default, and every page today):** unchanged from P2-6 — `plan.location` is `{description: sdPrompt}`,
  no `inputs.location`, no degradation, no binding; `TestChapBookV3Render`'s verbatim prompt pins still hold.
- **Set:** the location must be in the book's world (D4 — the ChapBook's own world, §7.1; another book's location
  is refused with "Location <name> is not in this book's world" and the page compiles text-only). The compiled
  MAIN clause is the location's subject text (description, then the variant's qualifiers) joined to the page's
  `sdPrompt` by `joinSentences`, so **the page's `sdPrompt` is no longer the whole prompt sent**: the composed
  prompt is visible on the review screen's plan inspector and the plan route, and the page card's
  "located: <name>" chip is P3-7 (§26). `inputs.location` carries the location's revision hash (and
  `inputs.locationVariant` the variant's), so the page's input hash changes when the location is edited. The
  subject's `approvedRender` is carried in `plan.location.renderArtifactId` (TEXT_ONLY still sends text only; the
  reference/init-image strategies that consume it are Phase 5), and a subject without one adds `LOCATION_TEXT_ONLY`,
  which **vetoes auto-approve for the page** — D5 applies to pages with no page-specific code, because
  `applyAutoApprove` folds the plan's degradations for every plan-driven node (resolves Implementation §6.C 178 ask 2).
- **Binding — the node binds what the plan accepted, never the raw pointer.** After a successful render the page's
  LANDSCAPE node binds the location record at role `location` ordinal 0 (the variant at ordinal 1) through
  `bindSceneLocation(graph, node, plan)`, the same bindings a PictureBook scene node gets, so `PbStaleUtil.scan`
  reports the page as `refDrift` when the location's watched fields change — and marks it STALE even when
  auto-approve pinned it (a pin stops the runner, not the scan; §10). The binder reads the plan's verdict, not the
  scene's FK: a refused location (`revisionHash` null) binds nothing, a refused variant (`inputs.locationVariant`
  null) leaves ordinal 1 unbound, and a re-pointed or un-pointed page is re-bound or unbound on its next render
  (`syncRecordBinding` rebinds a changed reference and drops a cleared one), so the stored `inputHash` is always the
  recompute over the bindings the plan actually used. A failed render binds nothing and leaves no input hash; the
  plan artifact still records what was attempted (§12 rule 1). The page's landscape artifact is never promoted to
  the location's `approvedRender` — promotion is reserved for LOCATION-scoped nodes (§7.1), so a page render cannot
  become the place's canonical image.
- **Pointing a page at a location** has no route or Ux in P3-6: the proposal pass (§9) skips pages (they carry no
  `setting`), so until P3-7's Locations tab the only write path is generic `PATCH /rest/model` on
  `olio.pb.scene.location`. **A pointer edit is not a staleness event** — nothing marks the page STALE when
  `location` is set or cleared; the bound is the next render. PictureBook scenes have the same gap on
  proposal-accept and merge (Implementation §6.C 179 c), and P3-7's pointing Ux must go through a facade route that
  calls `markSceneNodeStale`, not generic PATCH.
- **Unreadable pointer:** a page whose `location` the caller cannot read is itself unreadable (nested-foreign PBAC,
  Implementation §6.C 177), so the page render 404s rather than rendering without its place.
- **v1** (`chapbook.renderPath=v1`) ignores the pointer entirely; the rollback is untouched.
No model, constant, route, prompt or degradation code is added. Verified by `TestChapBookPageLocation` (Objects7,
SD-live on `.39`: a located page renders with the location in the sent prompt, auto-approves, binds, and drifts when
the location is edited; an unrendered location degrades and vetoes auto-approve; a failed render keeps the plan and
binds nothing; an unlocated page plans as before; another book's location is refused and, rendered, binds nothing;
a page at a variant binds both ordinals, re-pointed re-binds ordinal 0 and drops ordinal 1, un-pointed drops both
and auto-approves), the P2-6 regressions,
`TestChapBookRestContract` / `TestChapBookServiceRenderPath` (Service7 H2) and the ChapBook Playwright group (Docker
stack) — `PictureBookV3Implementation.md` §11.6, §6.C 179.]**

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
  approve/lock, "used by scenes 3, 7, 12". **[2026-10-17 (P3-7), as built:** `features/pictureBookLocations.js` at
  `/picture-book/:bookObjectId/locations` (header link `pb2-locations-link`, v3 books), fed by one `GET /{book}/locations` and
  refetched after every write. Per card: name, kind, description, aliases, lock, `usedByScenes` from the DTO (no client join),
  variants with their own chain, and the render chain reusing `pictureBookReview.js`'s exported `orderNewestFirst` /
  `reviewStatusOf` / `isAutoApproved` / `artifactActions(a, node.pinned)` with Use this / Approve / Reject — `renderArtifact` is
  not extracted. New / Edit (inline) / Lock / Delete / Add variant / Merge; Render → `202 {jobId}` polled with `pollJob` and the
  P1-10 `deriveStageList` progress, an AbortController stopping the poll on route change; a pinned LOCATION node's 409 is answered
  in place with "Unpin and render", never a silent unpin. Proposed-locations panel: Propose (synchronous), status filter,
  Accept / Merge-into (`sameAs`) / Dismiss. The canvas gives LOCATION nodes their own rank and accent; `PbPlanInspector` shows the
  location/variant names and the location input kind. ChapBook page cards carry the `cb-page-location` chip ("located: <name>[ /
  <variant>]" or "unlocated") that opens a picker Dialog (Cancel / Clear / Apply) calling `PUT /{book}/scene/{id}/location` (§27)
  and reporting the `nodeStale` / `pinned` outcome; the reader page chip reads `locationName` / `locationVariantName` from
  `/{book}/pages`.]**
- **Scenes:** a card list with a status chip (stale/review/approved), degradation summary and
  thumbnail. **[2026-10-22 (P4-9), as built:** the reader's existing scene cards (`features/pictureBook.js`) carry the
  degradation chip `pb2-scene-degradations` and the `PROMPT_TRUNCATED` "dropped: …" lines `pb2-scene-truncated`, read from the
  scene's last `renderPlan` artifact (`pb2EnsureSceneDegradations`); the separate board-side Scenes card list is not built — the
  board's header selects the scene (prev / select / next) — `PictureBookV3Implementation.md` §6.C 197 (f), (m).]**
- **Scene detail:** Story | Composition | Render | Review. The composition board from the notes
  §19/§43:
  - drag, resize and z-order actor and element boxes over the approved location render, or over a
    neutral canvas at the page aspect;
  - a side panel for the actor list (role, outfit picker filtered to that character's apparel, pose,
    expression);
  - camera controls.
  **[2026-10-22 (P4-9), as built:** `features/pictureBookCompose.js` at `/picture-book/:bookObjectId/compose[/:sceneObjectId]`
  (a sub-route reached from the reader's `pb2-compose-link`, not a Scene-detail tab — Mithril has no optional route parameter, so
  two route entries), one board per scene: header (scene prev / select / next; one "Chat Config" control whose pick's NAME travels
  as `chatConfigName` on every compose call — see L1364–1368; "Compose book…" dialog with Quick start + force, the job followed on
  the page with per-scene status rows; "Re-compose" with the `userEdited` warning, `{force:true}`), status chips (revision, source,
  purpose, prompt locked, location), camera (shot / viewpoint / focal actor / `cameraText`), setting / action / mood / props,
  prompt override + lock, the sparse `configOverride` editor (parsed, diffed, sent only as the delta), actor rows (role, priority,
  pose, expression, action, facing, outfit chip `(from scene N)` / `(default)` with the wardrobe picker → `PUT …/actor/{id}/apparel`,
  x / y / w / h / depth), element rows + "Promote to item", a neutral drag-to-move canvas at the plan's aspect (no resize / z-order
  handles — numeric fields for w / h / depth), and the compiled `/plan` beside the fields (headline prompt per stage, degradations)
  so the edits the planner does not yet read (§8 / P4-10) are visible. **Every edit is one row-level PATCH of the changed keys
  only** against §27's routes; an unchanged Save sends nothing; the board re-reads the composition after each write. The wardrobe
  panel sits on the character detail (`workflows/pictureBookCharacters.js`, V3 books): entries with kind chips, set default, add
  alternate (`POST …/wardrobe`), migrate scene tags, render reference (job progress) — the reference image is render-only in this
  slice (no upload; §6.C 197 c). Not built here: the COVER / empty-composition entry (197 n), reference-render review / approve
  (197 o). Playwright `e2e/pictureBookComposition.spec.js` proves it on the Docker stack as the shared test user, default / LLM /
  SD tiers — `PictureBookV3Implementation.md` §12.9, §6.C 197.]**
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
| `GET/POST /{book}/locations`, `PATCH/DELETE /location/{id}`, `POST /location/{id}/variants` | Location library. **[2026-10-16 (P3-4), as built: every location route is book-scoped — `GET|POST /{book}/locations`, `GET|PATCH|DELETE /{book}/location/{id}`, `POST /{book}/location/{id}/variants`, `POST /{book}/location/{id}/lock`, `POST /{book}/locations/propose` (synchronous), `GET /{book}/locations/proposed`, `POST /{book}/locations/proposed/{pid}/accept|reject`, `POST /{book}/locations/merge`; the book is the authorization anchor and the world is derived from it. Bare-path aliases are an open ask (Implementation ⚠ 175 a).]** |
| `POST /location/{id}/render?variant=` (async) | Render a location. **[2026-10-16 (P3-4), as built: `POST /{book}/location/{id}/render` with `variant` in the body → `202 {jobId}`; 409 when locked, pinned (an approved render on the subject) or in flight.]** |
| `PUT /{book}/scene/{sceneId}/location` | **[2026-10-17 (P3-7), as built:** point a scene (or ChapBook page) at a location, `{ locationObjectId?, variantObjectId? }` — both absent clears both FKs (stored as column `0`, read back as null); naming only the variant resolves its parent location. Gate order: 404 book unreadable, 403 unless the caller may update the book, 404 scene outside the book, 404 location outside the book's world (D4) or variant under a different location than the one named; non-string ids 400. Writes both FKs as the caller through `AccessPoint` and then `markSceneNodeStale` (§24 P3-6 (c) — a pointer edit is a staleness event); returns `{sceneObjectId, locationObjectId, locationName, variantObjectId, variantName, updated, nodeStale, pinned, nodeStatus}`. A pinned node is marked STALE like any other and echoed `pinned:true`, not a 409 — only `nextRunnable` honours the pin. `GET /{book}/pages` now carries `locationObjectId`, `locationName`, `locationVariantObjectId`, `locationVariantName` per page.]** |
| `POST /{book}/compose` → 202, `POST /{book}/scene/{id}/compose` | Whole-book, in-order composition job (§9.1); single-scene re-compose. Both respect `userEdited` |
| `GET/POST /character/{id}/wardrobe`, `PATCH /scene-actor/{id}/apparel` | Wardrobe alternates; per-scene outfit (§6). **[2026-10-19 (P4-5), as built: every wardrobe route is book-scoped, the book being the authorization anchor — `GET|POST /{book}/character/{id}/wardrobe`, `PUT /{book}/character/{id}/wardrobe/{apparel}/default`, `POST /{book}/character/{id}/wardrobe/{apparel}/reference` (202 job), `PUT /{book}/composition/{comp}/actor/{actor}/apparel` `{apparelObjectId}` (empty body = unpin; replaces the `PATCH /scene-actor/{id}/apparel` shape), `GET /{book}/composition/{comp}/outfits`, `POST /{book}/wardrobe/migrate-scene-tags`. Gate order: 404 book → 403 unless the caller may update the book (reads need only read) → 404 character/composition outside the book → 400 apparel outside the character's wardrobe.]** |
| `GET/PUT /{book}/settings/auto-approve` | Auto-approve toggle (§10 rule 5) |
| `GET/PATCH /composition/{id}`, `POST/PATCH/DELETE /composition/{id}/actor[/{aid}]`, same for `/element` | Composition editing | **[2026-10-20 (P4-6), as built: `POST /{book}/composition/{id}/element/{elementId}/promote` → `{promoted, created, reused, itemObjectId, itemName, elementText, staled, warnings}`; the actor/element CRUD routes themselves are P4-8.]** **[2026-10-21 (P4-8), as built: all book-scoped — `GET|PATCH /{book}/scene/{sceneId}/composition` (the board read/write: `{compositionObjectId, composition, actors[], elements[], nodeStatus, nodeStale}`; 404 when the scene has no composition yet — "run compose first" / "set its prompt first" for a page; PATCH takes the §8 editable fields and refuses the rest with the owning route), `POST /{book}/composition/{id}/actor` (`characterObjectId` or `description`; 409 on a CHAPBOOK_PAGE composition), `PATCH|DELETE …/actor/{aid}` (character/apparel refused → `PUT …/actor/{aid}/apparel`), `POST …/element`, `PATCH|DELETE …/element/{eid}` (`itemObjectId` refused → `…/element/{eid}/promote`). Every write answers the row DTO plus `actorObjectId`/`elementObjectId`, `revision`, `nodeStatus`, `nodeStale`, and bumps `revision` by one. Gate order: 404 book → 403 unless the caller may update the book (GET needs read) → 404 scene/composition/row outside the book → 409 → 400. The P4-5 apparel pin answers 400 for an outfit outside the actor's character's wardrobe — as the row above already says; an earlier wording here claimed the row said 404, which was wrong (Implementation §6.C 194 i, ruled ok 2026-10-21; 195 c — no code change).]** |
| `POST /composition/{id}/plan` | Dry-run: build and return the plan (strategy, models, compiled prompts, degradations, estimate) without rendering |
| `POST /composition/{id}/render` → 202 `{jobId, runId}` | Render an attempt (`preset`, sparse `override`, `target`: scene \| actor:{id} \| region:{box} \| faces \| clothing \| background) |
| `POST /{book}/render-all` → 202 | Render all stale/unrendered compositions **[2026-10-11 (P1-6): built on `PictureBookService` — terminals are every scene's `sceneNode`; `scenesWithoutNode` lists the skipped ones; 409 when no scene has a node]** |
| `POST /{book}/scene/{sceneId}/render` → 202 `{jobId, runObjectId}` | **[2026-10-11 (P1-6): the Phase 1 stand-in for `/composition/{id}/render` until compositions exist (Phase 4) — the scene's `sceneNode` is the terminal; same plan/claim/lane/cancel semantics]** |
| `POST /{book}/run/{runId}/resume`, `/cancel` | Durable run control **[2026-10-11 (P1-6): `/cancel` built — 200 `{runObjectId, runStatus, cancelRequested}`; 404 unless the run is of the book's workflow; 409 when terminal, already cancelling, or no job in this JVM. `/resume` is P1-7]** |
| `PUT /artifact/{id}/select`, `/approve`, `/reject` | Review. **[2026-10-16 (P3-4): approving a LOCATION node's `landscape` artifact promotes it to the location's or variant's `approvedRender`; rejecting the approved artifact clears it; both 409 when the location is locked and 404 when the subject is outside the book's world or unreadable.]** |
| `GET /{book}/stale` (with live drift), `GET /{book}/review-queue` | Staleness / review |
| `GET /{book}/retention`, `POST /{book}/retention/apply`; `GET /retention/org`, `POST /retention/org/apply` | **[2026-10-29 (P6-3b), as built:** §23 retention sweep — dry run / apply for one book (`admin,user`; 404 book unreadable, 403 on apply unless the caller may update the book) and for every book in the caller's organization (admin only). Both take `keepAttempts` / `deleteBytesAfterDays` as query params (GET) or body ints (POST; absent → `PbRetentionSelector` defaults 3 / 30; outside range → 400 naming the field). Answer: `{scope, bookObjectId, slug, checkedAt|appliedAt, keepAttempts, deleteBytesAfterDays, nodes, items[], total, counts{keep, archive, deleteBytes, none}, bytesReclaimable}`, apply adding `archived, bytesDeleted, bytesFreed, refused, denied, failed` and an `outcome` per item. Written as the caller through `AccessPoint`; a 409 from `archive` (selected / APPROVED) counts as `refused`, never a failure of the sweep.]** |
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

   > **As built (P3-3, 2026-10-16).** `PbMigrationUtil.importBook` does step 3 in two sub-steps,
   > both non-fatal (every failure is a warning on `ImportResult`, never an exception): (a) each PB1
   > scene note's `landscapeObjectId` becomes one `landscape` IMAGE artifact on the scene's canonical
   > LANDSCAPE node (`PbPipelineUtil.landscapeHandle(sceneOid)`, created `scope=scene` when absent),
   > `GENERATED` + `selected`, **never APPROVED**, node `DONE_UNVERIFIED`; the bytes are referenced
   > (`artifact.data` → the PB1 `data.data`), not copied, and a node whose chain is already non-empty
   > is skipped — a defensive guard, not re-import idempotence: `importV1Book` refuses an existing slug
   > with 409 before this step, so the skip is only reachable when something else already filled the
   > chain (wording corrected 2026-10-16, P3-3 verifier). (b) `PbLocationUtil.proposeLocations(user, book, null)`
   > runs the `pictureBook.cluster-locations` pass over the imported scenes as the request user with
   > their default chat configuration, writing `olio.pb.locationProposal` rows (`PROPOSED`, no
   > `location`, no binding); it is a suggestion the user accepts or ignores, exactly as this step
   > says. The pass is also exposed on its own, so a re-run never needs a re-import. **Deferred to
   > P3-4:** no LOCATION node is created by migration; when P3-4 first materializes the LOCATION node
   > for an accepted location L, it seeds L's chain with the selected `landscape` artifact of every
   > scene that references L (found at `landscapeHandle(sceneOid)`, role `landscape`), unapproved,
   > so the imported images arrive on the location without a render.
   > **Built (P3-4, 2026-10-16):** `PbServiceFacade.seedLocationNode` runs when the LOCATION node is materialized
   > (first render / approve / proposal-accept). For each scene of **this book** referencing the location it takes the
   > scene's *selected* `landscape` artifact and creates a new `olio.pb.artifact` on the LOCATION node pointing at the
   > **same** `data.data` row (`refModel` / `refObjectId` provenance; no bytes copied), as the request user through
   > `AccessPoint`; scenes without a selected landscape are skipped and counted (`seeded` / `seedSkipped`). Seeds are
   > PENDING, unselected, unbound, never auto-approved; variants are not seeded.
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

> **Built (P4-4, 2026-10-19):** the dual write now also happens **at import time**. `createFromScenes` on a
> bound V3 book writes the `olio.pb.scene` rows (`text`, `setting`, `sceneIndex`, `characters` enrolment;
> idempotent on re-run) before the §9.1 compose job is auto-submitted — without them the job found no scenes
> and "straight after `createFromScenes`" was unreachable. Books imported before this date still get their
> rows lazily at first render; their backfill is the P4-10 one-off (implementation doc §6.C 189 h).

> **Built (P4-10, 2026-10-22) — step 4 only, in part.** `PbMigrationUtil.migrateCompositions` runs inside the first
> compose job on a story book (`PbComposeJobUtil.composeNow`, before the LLM loop, as the job's user through
> `AccessPoint`): one `source=MIGRATED` SCENE composition per scene that has none, in the book's group, with
> `settingText`/`action`/`mood` from the scene, `sdPrompt` → `promptOverride`, `promptLocked` and `configOverride`
> copied; idempotent (a second run creates nothing); an existing or `userEdited` composition is counted and never
> touched; `PbWardrobeUtil.migrateSceneTags` (the §6.2 apparel pin step) runs in the same pass. Counts are logged once
> and returned on the job result as `migration`. **Not built here:** actors from `scene.characters` (step 4's first
> bullet — the LLM compose that follows proposes them), the composite-render import (step 5) and the dependency
> bindings (step 6). A ChapBook is skipped — its pages get a `CHAPBOOK_PAGE` composition on the first prompt write
> (§24). Implementation §6.C 198 b–e, §12.10.

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
- **[2026-10-11 (P1-8), as built — `PbRenderPath`:** default `v1` at every surface (code, `WEB-INF/web.xml`,
  `docker/web.xml.template` + `entrypoint.sh`, `docker-compose.yml`, Console7 `resource.properties`, Ux
  fallback); the test stack (`docker-compose.test.yml`) and the Objects7 test properties pin `v3`/`v3` so
  Phase 1 tests exercise V3. Absent/blank/unrecognised values pin `v1` (INFO/WARN, never a boot failure).
  The product is chosen by `olio.pb.book.bookType` — `CHAPBOOK` → `chapbook.renderPath`, anything else
  (including SQL-NULL rows from before `bookType` existed) → `picturebook.renderPath`. The gate is
  `PbRenderPath.requireV3(book)` in the facade, immediately after `requireBook`, on the thirteen Phase 1
  entry points (regenerate, scene/render-all render, cancel, run read, resume, select/approve/reject,
  review queue, auto-approve get/set, test-node) — a **409** `{"error":"render path is v1 …"}` naming the
  key to set; the Phase 0/P1-2 views (`workflow`, `node`, `artifact`, `stale`, canvas authoring) stay
  readable under either value and the boot recovery sweep runs regardless. It is a facade feature gate,
  not an authorization boundary — PBAC is unchanged on every record. **The Ux is told through a dedicated
  read-only `GET /rest/config/render-path` → `{"picturebook","chapbook"}` rather than through the feature
  manifest** (architect ruling: the manifest is a static array meaning "flags an admin may toggle"; a
  boot value does not belong in it) — `PictureBookV3Implementation.md` §5.5 item 42, §8.8.]**
- **[2026-10-11 (P1-9), ChapBook under the switch:** for ChapBook the two Stage 2 entry points named in the
  table (`chap-book/render`, `scene/{id}/generate`) are **not** disabled at `v3` — they are the v3 front
  door (§24 as-built block): the same URLs submit the graph job and answer 200/202. `chapbook.renderPath`
  is read by `PbRenderPath.isV3(book)` per call on a `bookType=CHAPBOOK` book; `v1` keeps
  `renderResolvedScene` / `renderChapBookSummary` byte-for-byte. So "Disable" for ChapBook means the
  v1 branch inside those routes goes unreachable, not that the routes return "replaced by V3".]**
- **[2026-10-14 (P2-6), Stage 2 for ChapBook — as built:** `PbRenderPath` carries one default per product,
  `PICTUREBOOK_DEFAULT=v1` (Stage 1 continues: PB1 live and default) and `CHAPBOOK_DEFAULT=v3`. Every surface
  moved together: the code default, `WEB-INF/web.xml`, `docker/web.xml.template` + `entrypoint.sh`
  (`CHAPBOOK_RENDER_PATH`, default `v3`), `docker-compose.yml` / `docker-compose.test.yml`, Console7
  `resource.properties`, and the Ux fallback in `featureConfig.js` (`{picturebook:'v1', chapbook:'v3'}` when
  the route is absent or the body unrecognised). An absent/blank/unrecognised value pins the **product**
  default, so a typo cannot move ChapBook back to v1; `chapbook.renderPath=v1` is the documented **rollback**
  value — honoured, logged at WARN (`legacy ChapBook render path re-enabled (rollback)`), applied by a
  restart. The ChapBook routes remain the v3 front door as in the P1-9 bullet; under the rollback value they
  run the v1 branch byte-for-byte (`TestChapBookRenderStyle`). The Objects7 test properties still pin
  `v3`/`v3`. **The verify stage (3) started for ChapBook on 2026-10-14**; check status is tracked in
  `PictureBookV3Implementation.md` §10.6 (checks 1, 4 and 5 are Stephen's). Contract:
  `TestChapBookRestContract.TestChapBookDefaultsToV3AndV1IsRollback`, `TestPbRenderPath` cases 01/03/10.]**
- **[2026-10-28 (P5-3), Stage 1 Coexist inventory — as built:** no stage moved; `PICTUREBOOK_DEFAULT` stays `V1`. REMOVED (grep-proven
  caller-free): `WorkflowCompiler.CLASS_TEXT_ENCODE_ADVANCED`, `ActorTarget.baseArtifactObjectId`. REDACTED (WARN, no refusal): PB1
  `PictureBookUtil.generateSceneImage` under `picturebook.renderPath=v3` (`:9755`); legacy PB2 `REFERENCE_STRIP` / `COMPOSITE` node
  dispatch (`PbNodeExecutor.warnLegacyNode` :501, one WARN per execution with book / node oid). DEPRECATED (`@Deprecated` + javadoc naming
  the replacement and this staging; removed at Stage 4, Phase 10): `PictureBookService.generateSceneImage` :705 / `prepareImages` :775;
  `SceneCompositeUtil.includesLandscapeReference` :187 / `buildSceneRequest` :222 / `applyPromptOverride` :333 / `defaultCreativity` :349;
  `PbNodeExecutor.executeReferenceStrip` :1676 / `executeComposite` :1777. Stage 2 refusal (`REFUSAL_PREFIX`) for PictureBook stays
  Phase 7; the §37.4 Kontext dead code stays Phase 10 (Stephen, 2026-10-28). Evidence: `PictureBookV3Implementation.md` §13.5, §6.C 211.]**

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
| Location | text-only (`LOCATION_TEXT_ONLY`) **[2026-10-17 (P3-5), as built: a scene that names a location (or variant) without an approved render → text + `LOCATION_TEXT_ONLY`; a scene that names none → plain text-only with **no** degradation — nothing was promised, nothing is missing. Implementation §6.C 178 (D2).]** |
| Camera | MEDIUM, EYE_LEVEL; WIDE when there are more than 3 actors |
| Portrait | render on first use; while missing → `ACTOR_TEXT_ONLY` |

**[2026-10-23 (P4-13), as built — a ChapBook fold destroys the folded page, it does not merge it.** `ChapBookUtil.mergeSceneUp`
appends the *following* scene's stanza onto this one and then deletes that following scene together with every composition keyed
to it (`deleteSceneCompositions` → `PbDeleteUtil.deleteCompositionsByScene`, child→parent, as the acting user, before any
write; a refused row is 403/500: the stanza and both scene rows are untouched, while composition child rows already deleted
before a mid-tree refusal stay deleted — Implementation §6.C 201 c, 202) — a user-edited `CHAPBOOK_PAGE` composition on the folded page is deleted
with it, never carried onto the survivor, whose own composition is untouched (only `poemStanza` and `imageStale` change). Stated
intent, not a gap (`PictureBookV3Implementation.md` §6.C 201 x).]**

**[2026-10-23 (P4-13 / P4-14), as built — which scan owns which dangling composition class.** The orphan scan
(`PbOrphanUtil.scanPb2Rows`; per user — `inScope` by slug, org-wide only for `AccountAdministrators`; its purge deletes a
composition's dependents olio-then-user and then the row) lists a `sceneElement` / `sceneActor` whose `composition` FK no longer
resolves, a composition whose `book` no longer resolves, and a SCENE / CHAPBOOK_PAGE composition whose `scene` no longer
resolves (a FK to a deleted row reads back null, `fkId` → 0). The stale-graph scan (`PbHealthUtil.scanStale`; per book, run as
the olio principal from `healBook` and from `createBook` over an existing world) lists a composition in the book's groups whose
`book` names another book, and actors / elements whose composition is not one of the book's live rows. Both classify a
composition with a dangling `book`; the overlap is by design — one is reachable per book, the other per user — and both delete
it (architect P4-14 F5; Implementation §6.C 202).]**

### 30. Phasing

Phases follow the notes' §60–68, re-cut for AM7. Each phase is shippable and ends with real tests
(live backend, `ensureSharedTestUser()`, LLM/SD tests gated and single-worker, visual inspection
for image output).

| Phase | Scope | ChapBook impact | Exit criteria |
|---|---|---|---|
| **0 — Hygiene** (current code) | G-15 override leak, G-18 explicit width/height, G-24 template override, G-26 null guards, G-8 landscape flag, G-11 landscape failure non-fatal; from the gap doc's Appendix A "Other": `ByteModelUtil` instead of raw `FIELD_BYTE_STORE` writes, and run closure on exception; **Kontext Deprecate stage** (§37.3, ratified) **[Progress 2026-10-09, see `PictureBookV3Implementation.md` §2/§5.3: G-15, G-18, G-24 (Option A — override stays on the landscape slot), G-26, G-8, G-11, `ByteModelUtil`, run closure DONE with live tests. Additions not listed here: G-11 adds `landscapeFailed`/`landscapeError` to `olio.pictureBookResult` and a text-only txt2img fallback for a classic composite with neither landscape nor portraits; G-8 bumps `PB_PIPELINE_VERSION` to `pb2/3` because the schema default is outside the inputHash (§11.1 drift). Kontext Deprecate and the ChapBook column pending.]** | G-25 style, negative and hires; G-32 book settings saved, page overrides not flattened | The gap doc's §4 step 1 plus the notes' §60 criteria; each fix has a test, PB and ChapBook |
| **1 — Graph-first rendering + review** | Rendering through graph execution (PbNodeExecutor converged with v1 behaviour, fixing the G-30 divergences) **[done 2026-10-10, P1-3: PORTRAIT resolves the character through the v1 `resolveSceneCharacter` (PBAC), applies apparel + style tiers, links `profile.portrait`, SD server resolved by the route like v1 `generate`; `requestRegenerate` opens a run and renders (pinned → 409 first, no STALE pre-mark); only PORTRAIT closes DONE, the other node types stay `DONE_UNVERIFIED` until their phases — implementation state §8.3, §5.5 items 18–27]**, live drift detection (§11.1) **[done 2026-10-10, P1-2]**, review status, auto-approve toggle (§10), async render jobs, restart recovery. V3 runs behind the `renderPath` switch (§28.1 Stage 1) | ChapBook pages become graph nodes with artifacts; same jobs and review | Leave the page → render continues; restart → Resume; a charPerson edit shows the affected scenes |
| **2 — Style, model resolution, compiler core; ChapBook goes V3** | `olio.pb.style` with inheritance and lock, `ModelResolver`, capabilities/presets resources, models-by-task route, style editor, estimate, `RenderPlan`, TEXT_ONLY strategy, `SdxlCompiler`, plan inspector; **Kontext Disable stage** (§37.3) | **ChapBook is the first product switched to V3** (TEXT_ONLY, zero actors), with up-front composition from the existing continuity composer. **ChapBook old path disabled (§28.1 Stage 2)** | One model policy across reimage/PB/ChapBook/chat; a missing model is reported before rendering; ChapBook verify checks start; chat scene renders through the illustration service (§24.1) |
| **3 — Locations** (world-scoped) | Location/variant models, LOCATION nodes, `approvedRender`, library Ux, approve/lock, clustering migration, background-first for TEXT_ONLY/DIRECT; **POI link** (§7.2 rules 1–3, 5): map-free `olio.pointOfInterest` + `olio.store` per resolved location in book worlds, name-match in game worlds, `PointOfInterestUtil.findByName`, `PbOrphanUtil`/`PbHealthUtil` repair of a dangling `poi` | Optional locations on ChapBook pages | Scenes 3/7/12 share one approved tavern; series chapters share it; a location failure doesn't fail scenes; every resolved location has a POI with a store, and deleting the world removes both |
| **4 — Composition + wardrobe** | Composition/actor/element models (`sceneElement.item` FK, §7.2 rule 4), up-front `pb.compose` job in story order with lazy per-scene fallback and "Quick start" (§9.1, ratified) fed the location's POI store items (§9), layout util, board Ux, `Flux2Compiler`, degradations, wardrobe alternates and carry-forward (§6) | Composition model replaces `sdPrompt`/`promptLocked` (already switched in Phase 2) | Any number of actors; prompt shown = prompt sent; nothing dropped silently; outfit change persists across scenes; a promoted element is an `olio.item` in the POI store and is reused by name in a later scene at the same location |
| **5 — Multi-character strategies** | **Spike first (P5-0, no product code):** contract measurements and captured graphs against the live backend (§38.5: IP-Adapter and FLUX.2 base graphs captured, `<region:>` vs own inpaint graph decided, text-frame QA outputs, timings on .39), Swarm mask inpaint, and FLUX.2 base + SDXL inpaint style coherence. Then, as P5-1 onward, `WorkflowCompiler`, SEQUENTIAL_INPAINT, REGIONAL_PROMPT, HARMONIZE and targeted regeneration. Parameter names and the raw-graph contract are already verified (§36.2, §38.1); what remains unmeasured is behaviour and timing | n/a (no actors unless G-35 is enabled) | Scenario B: 4 characters all rendered and identifiable on visual inspection; GPU memory within limits |
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

**[2026-10-24 (P5-1), as built — what a graph-rendered stage records (§38.3 as-built block is the
full description):** per stage node one `renderGraph` JSON artifact (`{stage, graphHash,
swarmVersion, graph}`; never image bytes) alongside its image artifacts; on each image artifact
`backendGraph = {stage, graphHash, saveNodeId, swarmVersion}` and `generatorRequest` = the
sanitised request record (`stage`, `model`, `images`, `graphHash`, text + seed inputs,
`referenceArtifactIds`, `referenceArtifactObjectIds`); `seed` from the sent inputs, never the PNG.
`graphHash` is the join key across the three. `durationMs` per stage comes back on `StageResult` as
before. Nothing writes these from the executor yet — the executor's graph path is P5-2 — so until
then they exist on the records only where the P5-1 live test or a caller puts them:
`PbRenderPlanUtil.persistGraphArtifact` for the graph artifact, `PbPipelineUtil.recordImage` for
the sanitised request record, and the new `PbArtifactUtil.recordImageMetrics(…, backendGraph)`
overload for `backendGraph` (the existing `recordImage` path does not pass it; the executor will).**]**

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
    generates them (§38). The Phase 5 spike (P5-0) measures the contract and captures Swarm's
    generated graphs; the graph builder (`WorkflowCompiler`) is P5-1, built from those captures.
    No saved workflows either way.
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
    devices and are expected to be slow (§31 last bullet, §20 "reachable but slow"). **Resolved
    2026-10-24 (Stephen, on ratifying P5-0): (b), seeded by (a) — the P5-0 numbers below are the
    seed; the per-server calibration record is P5-1's to design** (the in-JVM
    `RenderCalibration` EWMA from Phase 2 is the in-process half; the persisted record is new).

    **Phase 5 decisions — inputs from P5-0 (2026-10-24; §38.5 as measured; nothing here is
    decided).** *Seed numbers for (a) / (b), `.39`, FLUX.2 Klein 9B, 1024×768, 4 steps:* BASE with
    3 references **≈ 102 s warm / 128 s cold**; no-reference 1024² 38.6 s — so roughly 20 s per
    reference on this device against the repo's `~40 s per reference` Strix Halo figure;
    ACTOR_REGION as a `<region:>` pass over the base ≈ 176 s warm / 194 s cold; one-box mask
    inpaint 77.5 s; face-segment pass +86 s over base; `<object:>` ≈ 340 s. SDXL 25-step 28–53 s,
    mask inpaint 15 s. HARMONIZE and UPSCALE unmeasured. *The per-actor pass — Swarm's `<region:>`
    versus V3's own inpaint graph (§38.2):* `<region:>` is conditioning-only — it keeps the layout
    and takes an edit on FLUX.2 but leaks across actors (1 in 3 seeds), merges or drops actors when
    boxes overlap on either family, and takes 937 s for four boxes with five references; a plain
    `maskimage` inpaint recomposites exactly outside the mask on both families (the untouched actor
    is byte-stable) but at FLUX.2's 4 steps × creativity 0.6 it cannot add what the init lacks (a
    scarf, a missing figure), and `useinpaintingencode` is not a substitute (grey slab). So the
    P5-1 plan decides between: more steps or creativity 1.0 inside the mask for FLUX.2 inpaint
    passes (unmeasured — measure first), a non-overlapping layout for any `<region:>` use
    (`PbLayoutUtil.defaultPlacement` 4-of-4 overlaps), and which checkpoint family runs which
    stage. IP-Adapter is inert on FLUX.2 and bleeds on SDXL; FACEID is blocked by the `.39` venv
    (§36.5) — identity on FLUX.2 comes from the `ReferenceLatent` chain and held across seeds.
    **Ratified 2026-10-24 (Stephen):** P5-0 as measured; the estimate's source is **(b) seeded by
    (a)**; the FLUX.2 inpaint question (more steps vs creativity 1.0 inside the mask) is
    **measured first in P5-1** before the graph builder commits; P5-1's live tests use a **real
    `am7db` cast (≥ 4 portraits) if one exists**; FACEID stays out and `useinpaintingencode` is
    excluded from Phase 5.

    **Q14 (b) — the per-server calibration record: designed in P5-1, built in P5-2 (Stephen,
    2026-10-24, B3: "design note only in P5-1").** What P5-1 did: the (a) seed is in
    `capabilities.json` — `flux2*` `secondsPerStepPerReference` 5.0, derived from the spike's
    3-reference BASE (≈ 102 s warm) against the no-reference render (38.6 s): (102 − 38.6) / 4
    steps / 3 references = 5.3, rounded to 5.0 by the implementer (Stephen ratified the
    measurements, not the rounding); `*xl*` and `*kontext*` stay `null` because no
    reference-bearing render of either family has been timed on `.39`. The in-JVM
    `RenderCalibration` EWMA records graph **BASE** renders only. What P5-2 builds, as designed
    here:
    - **Shape.** One record per `(server, model-family pattern, stage)` holding the EWMA of
      `durationMs / (steps × max(1, references))` — i.e. the same unit as
      `secondsPerStepPerReference` — plus `sampleCount`, `lastSampleAt`, the last `graphHash` and
      the Swarm version it was measured under. ACTOR_REGION samples use the *effective* step
      count `steps − start_at_step`, which is why it needs its own row rather than sharing BASE's
      (folding it in skews the BASE figure). The record seeds from `capabilities.json` when it has
      no samples; a family with a `null` seed and no samples yields "no estimate", never a number.
    - **Where it lives.** Deployment-global per server, like the server URLs in
      `system.connection` — not in any user's group and not per organisation (a GPU's speed is not
      tenant-specific). A `/System`-global record of a small new model (or a sibling of
      `olio.sd.config`; the model choice is P5-2's) resolved the way `ServerConfigUtil` resolves
      connections.
    - **Who writes it, and when.** The render executor, at **job completion**, through a dedicated
      authorised write path — never from a read (an estimate request must not create or update the
      record; `architecture.md` "Read paths must not create") and never as the org admin: the
      writer is the job's own principal with an explicit grant on the record, or the record is
      written by the system principal from the executor only, with the choice recorded in §6.C
      when P5-2 makes it. A write the writer is not permitted is counted and logged, never forced.
    - **How the estimate reads it.** `RenderEstimate` consumes the record (today it does **not**
      consume `secondsPerStepPerReference` at all — noted out of scope in P5-1), falling back to
      the in-JVM EWMA, then to the `capabilities.json` seed, then to "no estimate". A second JVM
      (Console7) updating the record cannot invalidate this JVM's in-process copy; the propagation
      bound is the record's re-read on the next estimate request, stated in the Ux, not hidden.
    - **Never a threshold.** The record feeds the estimate only; no test, watchdog or job limit
      reads it (§20 "reachable but slow").
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

*Version drift, 2026-10-24:* `/API/GetNewSession` now reports **`0.9.8.4.GIT-5dc94afa`** (same
single `comfyui_selfstart` backend; permissions still include `comfy_direct_calls` and
`comfy_dynamic_custom_workflows`). The inventory below and the §38.1 contract were taken on the
`0.9.8.3` build; the P5-0 spike's 29 requests found nothing that changed under the new one. Other
`0.9.8.3` mentions in this document are left as the dated record of the 2026-10-09 probe.

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
| YOLO face weights `face_yolov8m.pt` | File drop into `(SwarmUI)/Models/yolov8/` (not a `T2IModelSets` type) | **Installed and rendered with** — `yolomodelinternal` = `['face_yolov8m.pt']`; backend feature `yolov8`. P5-0 `segment_yolo_flux2` (2026-10-23): a clean face-only pass, 0.2 % of the frame changed outside the two faces; CLIPSeg (`<segment:face>`) found one face of two |
| IP-Adapter SDXL weights (`ip-adapter-plus_sdxl_vit-h`, `ip-adapter-plus-face_sdxl_vit-h`, FaceID variants) | **No install needed**: Swarm's `useipadapter` dropdown downloads each model on first use | **Working on SDXL** (P5-0 `ipadapter_sdxl`, 2026-10-23): `PLUS FACE (portraits)` rendered first time in 28.3 s — the same as a plain 25-step render, so fetched-then versus already-present is not separable; identity loose, wardrobe bleeds between actors. **Inert on the FLUX.2 generator path**: nodes emitted, output pixel-identical to the plain reference render |
| InsightFace (for `FACEID*` and any identity-score workflow) | Python package + `antelopev2`/`buffalo_l` models inside the Comfy venv; not reachable by any Swarm API | **Broken** (P5-0 `faceid_sdxl`, 2026-10-23): `FACEID PLUS V2` → `ComfyUI execution error (Node 101: IPAdapterUnifiedLoaderFaceID) … numpy.dtype size changed, may indicate binary incompatibility. Expected 96 from C header, got 88 from PyObject` — InsightFace's compiled extension against the venv's numpy; a `pip` repair inside the Comfy venv on `.39`. FACEID stays out of Phase 5 until then |
| SDXL inpaint checkpoint | `DoModelDownloadWS` type `Stable-Diffusion` | **Absent** — and `useinpaintingencode` with a base SDXL checkpoint is **not** a viable start (P5-0 `inpaintencode_*`, 2026-10-23: `VAEEncodeForInpaint` + `start_at_step 11/25` at creativity 0.55 leaves a flat grey slab in the mask). Plain `maskimage` inpaint (`SetLatentNoiseMask` + recomposite) works on both families without an inpaint checkpoint (§38.5 item 2) |
| ControlNet model (pose/depth for REGIONAL_PROMPT) | `DoModelDownloadWS` type `ControlNet` | **Absent, optional** — `ControlNet` list is `[]` although the feature flag and SAM2 preprocessors are present |
| Kontext checkpoint | Gated on Hugging Face | **Absent; recommend retire** (§37) |

Rendered with in the P5-0 spike (2026-10-23 / 24, `TestP50SwarmSpike`; results §38.5 and
`PictureBookV3Implementation.md` §4.11): IP-Adapter on SDXL and FLUX.2, FACEID (failed), YOLO and
CLIPSeg segments, `useinpaintingencode`, plain `maskimage` inpaint, `<region:>` and `<object:>` on
both checkpoints. The spike installed nothing; the ControlNet and Kontext rows are unchanged.

#### 36.6 Code audit — where the design text was wrong or imprecise, and what was changed

| Claim in the design | Fact | Applied |
|---|---|---|
| Pinned nodes are rejected in Service7 | `PbServiceFacade.requestRegenerate` (Objects7 `:299-308`) rejects; Service7 only maps the exception | §1 |
| Runs have an `INTERRUPTED` status | `PbRunStatusEnumType` = `UNKNOWN, PENDING, RUNNING, COMPLETED, FAILED, CANCELLED`; `CANCELLED` is reserved and never written today. **2026-10-10 (P1-1):** `INTERRUPTED` added; written only by the P1-7 boot sweep | §1, §21 |
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

1. Decide the open items in §34: **3 (default value), 7, 8** (Q14's source closed 2026-10-24:
   (b) seeded by (a)). None blocks Phase 0–2; each has a recorded default (off, DRAFT, two weeks +
   sign-off) that implementation uses until overridden.
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
| **Disable** | Phase 2 (compilers land; `SceneCompositeUtil` is superseded by `RenderPlan` + compilers) | `compositeMode=kontext` is rejected with the same "replaced by V3" response PB1 entry points return at §28.1 Stage 2; `isSupportedMode` drops it from `SUPPORTED_MODES`. V3 ships no `KontextCompiler`. Code kept in the build. **[2026-10-15 (P2-8) — as built:** `SceneCompositeUtil.isSupportedMode` accepts `flux2|classic`; `isRetiredMode` + `KONTEXT_RETIRED_MESSAGE` (ASCII, "replaced by V3 (design section 37.3)") name the refusal once; `resolveMode` stays pure and every entry point refuses its `kontext` result: `PbRenderPath.requireEnabledCompositeMode` → `PictureBookException` 409 in `PbNodeExecutor.executeComposite` (before the model policy and any SD contact; the run row already opened closes FAILED with the message, P0-8 visibility) and at the top of `PictureBookUtil.generateSceneImage` (after the override overlay, before Stage 0); `ChatService.generateScene` answers 400 for an explicit `compositeMode=kontext` (invalid content) and 409 when the body only resolves to it (`useKontext=true` with a blank or null `compositeMode`; an absent key materialises the `flux2` schema default on read, so the boolean alone passes), on both chat render paths, before the session lookup. `SceneCompositeUtil.buildSceneRequest` also refuses a retired mode itself (returns null; defence in depth), and the chat route echoes an unknown mode only control-stripped and capped at 32 characters. Tests: `TestKontextDeprecation`, `TestFlux2Composite`, `TestPbKontextDisable` (Objects7), `TestChatSceneKontextDisable` (Service7), `chatChainSceneReimageRoutes.spec.js` item 14. `ModelResolver.MODE_KONTEXT` (a second constant) is untouched until Remove.]** |
| **Verify** | §28.1 Stage 3 | Nothing extra: the verify checks already cover every composite path |
| **Remove** | Phase 10, with PB1 | Delete the code and tests in §37.4; drop the six fields through the off-by-default column-drop property only; remove `pom.xml:154` and `resource.properties:115-119` |

If you would rather **keep** it, the Phase 0 line changes to: install a checkpoint on `.39`, set
`test.swarm.kontextModel`, re-enable `TestKontext` in the POM, and add `KontextCompiler` to the §14
table as a Phase 2 deliverable with the `kontext*` fields as its inputs. Everything else in this
design is unaffected either way — Kontext is not on any phase's critical path.

#### 37.4 Deprecation inventory (what the Remove stage touches)

Kontext-only means "delete"; shared means "keep, strip the Kontext branch/comment". Line numbers
are as of 2026-10-09.
**[2026-10-28 (P5-3): the rows below were checked against the tree; dated notes in a row correct it in place. `TestKontext` is still pom-excluded and still whole — Stephen's 2026-10-28 ruling defers the #3–#8 deletions to Phase 10; only the relocation of `testStripSDXLWeighting` was done. Evidence `PictureBookV3Implementation.md` §13.5, §6.C 211 b / h / j.]**

**Main code**

| File | Lines | Kontext-only? | Remove-stage action |
|---|---|---|---|
| `olio/sd/swarm/SWUtil.java` | `defaultKontextModel` :144-147; `newKontextSceneTxt2Img` ×4 :420-502; `newKontextBase` ×2 :540-569 | Yes | Delete |
| `olio/sd/swarm/SWUtil.java` | `styleClause` :507-537 | Only prod caller is :494 inside the Kontext builder; `TestPictureBookFull.java:2013-2045` asserts PictureBook does **not** use it | Delete with the builder |
| `olio/sd/swarm/SWUtil.java` | comments :135, :163-175, :209, :230, :240 (the FLUX.2 builder explains itself by contrast to Kontext) | Shared | Rewrite the comments to stand alone |
| `olio/sd/SceneCompositeUtil.java` | `MODE_KONTEXT` :35, `SUPPORTED_MODES` :39, `KONTEXT_PANEL_SIZE` :42, `isSupportedMode` :55, `resolveMode` useKontext fallback :96-100, `includesLandscapeReference` :105, KONTEXT branch :226-239 | Shared (class survives until Phase 2 replaces it) | Strip the branch; `resolveMode` loses its `legacyKontextDefault` parameter **[2026-10-28 (P5-3): `includesLandscapeReference` :187, `buildSceneRequest` :222, `applyPromptOverride` :333, `defaultCreativity` :349 are `@Deprecated` (javadoc names RENDER_PLAN / Phase 10); the mode vocabulary, `resolveMode` and the refusal are not.]** |
| `olio/sd/SDUtil.java` | `stitchSceneImages` :1538-1590 | **Shared** — `PbNodeExecutor.executeReferenceStrip:329` | Keep; retitle the javadoc (it says "for FLUX Kontext") **[2026-10-28 (P5-3): retitled; now `:1772`, caller `PbNodeExecutor.executeReferenceStrip` :1677 (`@Deprecated`).]** |
| `olio/sd/SDUtil.java` | comments :1748, :1941, :2084 | Shared | Rewrite |
| `olio/picturebook/PictureBookUtil.java` | mode decision :10063-10074; landscape warn :10080-10086; `sceneCreativity` :10090; KONTEXT PIPELINE block :10196-10249; classic gate :10251; `SceneGenerationParams.compositeSdConfig` :1589-1591 | PB1 — removed wholesale at §28.1 Stage 4 anyway | Nothing extra **[2026-10-28 (P5-3): WARN at `:9755` when PB1 `generateSceneImage` runs under `picturebook.renderPath=v3`; no refusal until Phase 7.]** |
| `olio/picturebook/PbNodeExecutor.java` | javadoc :283-284; `resolveMode(effectiveConfig, false)` :456 | Shared | Follows `SceneCompositeUtil` **[2026-10-28 (P5-3): `executeReferenceStrip` :1676 / `executeComposite` :1777 `@Deprecated`; one WARN per legacy-node dispatch, `warnLegacyNode` :501.]** |
| `olio/llm/Chat.java` | comment :2408 | Shared | Rewrite |
| Service7 `ChatService.java` | `isSupportedMode` 400 gate :1419-1422; `resolveMode(sdConfig, true)` :1488-1490; branch comment :1539 | Shared | Phase 0: `true`→`false`; Remove: follows `SceneCompositeUtil` |
| `models/olio/sd/configModel.json` | `useKontext` :312-316, `compositeMode` description :322, `kontextModel` :390-393, `kontextSteps/Cfg/Sampler/Scheduler` :395-421 | Fields | Phase 0: deprecate in descriptions; Remove: drop six fields (column-drop property), reword `compositeMode` |
| `pictureBookMetaModel.json:74`, `pictureBookRequestModel.json:53`, `flux2Defaults.json:18`, `web.xml:45` | descriptions / comments | Text | Reword |

**Tests**

| File | Lines | Action |
|---|---|---|
| `TestKontext.java` | whole class (pom-excluded) | Delete — **except** `testStripSDXLWeighting` (:298), which tests the shared SDXL-weighting strip used by the FLUX.2 builder: relocate to `TestFlux2Composite` first **[2026-10-28 (P5-3): relocated — to the new pure-JUnit `objects/tests/TestSwUtilPromptText.java`, not `TestFlux2Composite` (which extends `BaseTest`); `TestKontext` keeps a one-line pointer and stays pom-excluded until Phase 10.]** |
| `TestFlux2Composite.java` | `promptOverrideReplacesKontextComposedPrompt` :629, `kontextAndClassicAlwaysConsumeTheLandscape` :746, `kontextAndClassicAreNeverAutoSkipped` :848, `landscapeGateFollowsTheLegacyKontextFallback` :861; assertions :398-402, :414-423, :444-453, :458-462, :468-477, :815 | Delete the four methods; rewrite the mode-vocabulary assertions to `flux2\|classic` |
| `TestPictureBookPipeline.java` | :297-319 (pom-excluded, PB1) | Goes with PB1 |
| `olio/TestPictureBookUtilE2E.java` :171-182, :237-253; `TestPictureBookUtilPersistenceE2E.java` :644-668, :723-745, :785 | PB1 Kontext branches | Go with PB1 **[2026-10-28 (P5-3): `TestPictureBookUtilPersistenceE2E.java` does not exist in the tree; `TestPictureBookUtilE2E.java` is `objects/tests/olio/`, pom-excluded (`pom.xml:168`).]** |
| `TestPictureBookFull.java` | :2013-2045 (asserts `styleClause` is *not* used), :3128-3130 (writes to `kontext-test-output/`) | Drop the negative assertion when `styleClause` is deleted; redirect the output path in Phase 0 **[2026-10-28 (P5-3): the writers already go to `./target/kontext-test-output` (`TestKontext.java:42`, `TestPictureBookFull.java:3129, :3131`); the Phase 0 redirect is done.]** |
| `TestPbGraph.java:438` | lists `kontextModel` among fields a sparse diff must not carry | Keep until the field is dropped, then remove from the list |
| Comments: `TestPictureBookCustom.java` :659, :667; `SdTestGate.java` :31, :298; `TestChatDuelLong.java` :221 | — | Reword |
| `AccountManagerObjects7/pom.xml:154`; `src/test/resources/resource.properties:115-119`; `media/flux/bad.composite.png` fixture (if only `TestKontext` reads it — check before deleting) | — | Remove **[2026-10-28 (P5-3): `test.swarm.kontextModel=` is `resource.properties:126` (empty); `bad.composite.png` is named only in comments (`TestFlux2Composite.java:52, :233`, `TestPictureBookCustom.java:659`) — no reader.]** |
| `AccountManagerObjects7/kontext-test-output/` (tracked `kontext-request.json`, untracked `portrait-fresh-render-*.png`) | scratch dir, not gitignored | Phase 0: delete and redirect writers **[2026-10-28 (P5-3): the directory is not present and `kontext-request.json` is not tracked — done.]** |

**Ux752**

| File | Lines | Action |
|---|---|---|
| `src/core/modelDef.js` | `useKontext` :10312-10316, `compositeMode` description :10322, `kontextModel/Steps/Cfg/Sampler/Scheduler` :10383-10414 | Mirror of the schema — follows `configModel.json` |
| `e2e/chatChainSceneReimageRoutes.spec.js` | :186 expects `flux2\|kontext\|classic`; :196 loops `['flux2','kontext','classic','KONTEXT']` | Disable stage: expect `flux2\|classic`, drop the two Kontext modes from the loop |
| `e2e/pictureBookScenePromptUx.spec.js:18`; comments in `chat/SceneGenerator.js` :49-50, `workflows/pictureBook.js` :225-230, :2700, `workflows/sceneExtractor.js` :357, :364, :496 and their tests | comments | Reword |

**Docs** mentioning Kontext (counts of lines): `KnownIssues.md` 34, this design 17,
`archive/PictureBookSdConfigRefactor.md` 14, `PictureBook2Plan.md` 8, `PictureBookImageGenerationDesign.md`
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

**Measured in P5-0 (2026-10-23 / 24; 29 requests; graphs, requests and responses under
`C:/tmp/p50-spike/`; per-render table `PictureBookV3Implementation.md` §4.11).** Everything in the
table above held on the `0.9.8.4.GIT-5dc94afa` build. Added facts, by row:

- *Get Swarm's own graph:* the FLUX.2 generator (`flux2Klein_9b` + `promptimages`) is 18 nodes —
  `CheckpointLoaderSimple`, `CLIPLoader qwen_3_8b (flux2)`, `VAELoader Flux\flux2-vae`,
  `EmptyFlux2LatentImage`, **`SwarmTextEncodeAdvanced`** for the positive prompt (input key
  **`prompt`**, with `steps`, 1.5× `width`/`height`, `target_width`/`target_height`, `guidance -1`),
  `CLIPTextEncode` for the negative, and per reference `SwarmLoadImageB64 → VAEEncode →
  ReferenceLatent` chained on the conditioning into `SwarmKSampler` (`noise_seed`, `start_at_step
  0`, `end_at_step 10000`). The plain `maskimage` path adds `SwarmLoadImageB64(mask) → ImageToMask
  (red) → SwarmMaskGrow → SwarmMaskBlur`, `SwarmLoadImageB64(init) → VAEEncode → SetLatentNoiseMask`,
  `DifferentialDiffusion` on the model, and — with `initimagerecompositemask` — `ThresholdMask
  0.001 → SwarmImageCompositeMaskedColorCorrecting` before the save; **`start_at_step` =
  `steps × initimagecreativity`** (4 × 0.6 → 2; 25 × 0.6 → 10). `<region:>` adds
  `SwarmSquareMaskFromPercent` + `CLIPTextEncode` + `ConditioningSetMask(globalregionfactor)` per
  region, the overlap-fix / exclude / clean-except-self mask nodes and `ConditioningCombine` (27
  nodes for two regions, 54 for four with five references). `<object:>` passes are **not** in the
  generated graph (confirmed: the capture for an `<object:>` prompt is the plain graph).
- *Submit a raw graph / Tag filling / Per-request custom inputs / Images into the graph:* a
  **FLUX.2** graph re-posted as `comfyworkflowraw` with `${comfyrawworkflowinputtextscene}`,
  `${comfyrawworkflowinputseedv3seed}` and **`${comfyrawworkflowinputimageactorA}` in a
  `SwarmLoadImageB64.image_base64`** (posted as a data-URI under that key) plus a string-id
  `v3_save_final` node: accepted, 108 s, the prompt edit visible — the image-tag fill is measured.
- *Outputs:* one image came back for two save nodes carrying the same bytes, as in render 1. The
  returned path contains spaces (`View/local/raw/<date>/<n>-<prompt prefix>-<model>.png`); encode
  it per segment before fetching.
- *Raw Comfy API:* `system_stats` → `cuda:0 AMD Radeon(TM) 8060S Graphics : native`, `vram_total
  107090132992`, RAM 68.3 GB, Windows, Python 3.12.10, PyTorch `2.9.1+rocm7.2.1`, ComfyUI 0.42.0
  (frontend 1.53.6 installed / 1.57.1 required — warning only), Comfy port 7821, `--preview-method
  latent2rgb`; `vram_free` ≈ 99 GB with SDXL resident, ≈ 79.5 GB with FLUX.2 (~20 GB for Klein 9B
  + `qwen_3_8b`), ≈ 106 GB after Swarm's idle unload (~20 min).
- *Websocket:* `GenerateText2ImageWS` with the same body gives `status + backend_status +
  supported_features` (features include `comfyui, yolov8, ipadapter, cubiqipadapterunified, sam2,
  comfy_loadimage_b64, comfy_saveimage_ws, comfy_saveaudio_ws, folderbackslash`), `gen_progress`
  (`is_preview` on the first), `keep_alive`, one `image + batch_index + request_id + metadata`
  frame whose `image` is a path, then `discard_indices` and `socket_intention`.
- *Timings (never thresholds):* §38.5 item 5.

**Measured in P5-1 (2026-10-24; `.39` on `0.9.8.4.GIT-d74f8ce4`; live run #2 of `TestP51GraphLive`
and five raw-graph probes, `C:/tmp/p51_probe3.py`). The *Images into the graph* row above is
incomplete, and the spike's image-tag fill passed by accident.** What the Swarm source
(`ComfyUIAPIAbstractBackend.cs`, master as of 2026-10-24) actually does with an image-typed
`comfyrawworkflowinput…` value:

- **`initImageFixer` runs before the tag fill**, over every image-typed dynamic input. If the
  request **key contains `swarmloadimageb` or `swarminputimage`**, the value is kept and the tag is
  filled by `fillDynamic` with the image's base64 — the behaviour the row above describes. **Otherwise**,
  when `workflow.IndexOf("${" + key)` finds the tag, the PNG is uploaded to Comfy `/upload/image` as
  `init_image_sui_backend_{BackendData.ID}_{id}.png` and the `${…}` span in the graph is replaced
  with that **filename** — which a `SwarmLoadImageB64` node then tries to base64-decode. Live run #2
  failed exactly there: `RENDERER_REJECTED … (Node 104/15: SwarmLoadImageB64) cannot identify image
  file`; probe A below is the same failure as `Invalid base64-encoded string: number of data
  characters (25)` (25 = the length of the filename it was handed).
- **The key Swarm compares with is the *cleaned* key** (`T2IParamTypes.CleanTypeName`): lowercase,
  ASCII letters only — digits and underscores are dropped — and the `IndexOf` is case-sensitive
  against the graph text. So the spike's `${comfyrawworkflowinputimageactorA}` survived only because
  Swarm searched for `…imageactora` (lowercase `a`) and missed; live run #1/#2's `duna_de_rosa` and
  `jideon_de_rosa` tags missed on the underscores; the plain `init`, `mask` and `touvier` tags were
  found and broken. A pass by case-miss is not a contract. A second consequence: two request keys
  that clean to the same string (`ref0` / `ref1` → `…imageref`) are one parameter on the server and
  silently share a value.
- **Type detection** (`DynamicParamGenerator`) takes the first `T2IParamDataType` whose lowercased
  name is a prefix of the part after `comfyrawworkflowinput`, so a key that starts
  `imageswarminputimage…` is still an IMAGE input.
- **`ImageShouldResize` is off for a raw-graph request**: it is evaluated against
  `GetImageWidth(-1)` and a `comfyworkflowraw` body sends no `width` / `height`, so the uploaded or
  inlined image is never resized by Swarm. A non-issue for V3; recorded so nobody re-derives it.

**The contract V3 follows from P5-1 on (product code, not a convention):** every image request key
and its `${…}` tag is **`comfyrawworkflowinputimage` + `swarminputimage` + `<name>`** — e.g.
`comfyrawworkflowinputimageswarminputimageinit`, `…swarminputimagemask`,
`…swarminputimagetouvier`. `ComfyGraph.inputKey(TYPE_IMAGE, name)` inserts the marker
(`ComfyGraph.IMAGE_KEY_MARKER`), so no caller can build an image key without it; non-image keys are
unchanged (`comfyrawworkflowinputtextscene`, `comfyrawworkflowinputseedseed`).
`ComfyGraph.swarmKey(key)` is Swarm's reduction (A–Z lowercased, everything but a–z dropped), and
`GraphInputs` refuses a second key that reduces to an earlier one's value
(`IllegalArgumentException: Input … collides with …`), raised while the inputs are built — i.e.
**before the generator is asked for anything** — so a reference set Swarm would merge never reaches
`.39`. Reference names therefore must stay distinct once reduced to lowercase letters: `ref0` /
`ref1` are rejected, `duna_de_rosa` / `jideon_de_rosa` / `touvier` / `init` / `mask` coexist. The
executor's tags are `actora`, `actorb`, … (`PbNodeExecutor.referenceName`) — its first version used
`actor1` / `actor2` and failed exactly here on the first live run (2026-10-26, implementation §6.C 209 a). The
tag-type detection in `TestGraphRequestShape` and the `PbArtifactUtil` key sanitiser
(`SANITIZE_IMAGE_KEY_PREFIX = "comfyrawworkflowinputimage"`) are prefix matches and still apply.

**Probes (SDXL `sd_xl_base_1.0`, graph = one `SwarmLoadImageB64` per tag + `v3_save_final`, value =
`t03_base_sdxl.png` as a data URI; timings are observations, not thresholds):**

| Probe | Tags | Result |
|---|---|---|
| A (old shape) | `init` | **error** `(Node 7: SwarmLoadImageB64) Invalid base64-encoded string: number of data characters (25)`, 2 569 ms |
| B | `swarminputimageinit` | `images=1`, 452 ms |
| C | `swarminputimagemask` | `images=1`, 360 ms |
| D | `swarminputimagetouvier` | `images=1`, 349 ms |
| E | `swarminputimageduna_de_rosa`, `…jideon_de_rosa`, `…touvier`, `…init`, `…mask` (five loaders in one graph) | `images=1`, 718 ms |

Any `renderGraph` artifact persisted by a run before this change (run #2's t03 BASE stage, if it
reached the DB) carries the old `comfyrawworkflowinputimage<name>` keys; it is a record of what was
sent, not a template, and is left as is.

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

**[2026-10-24 (P5-1), as built — `WorkflowCompiler` + `ComfyGraph` + `GraphInputs` + `NodeCatalogue`
+ `SwarmGraphClient` in `olio/illustration/`; Stephen's answers of 2026-10-24 in bold; ledger in
`PictureBookV3Implementation.md` §6.C 205.]**

- **Base graph source — generate-and-patch, as designed (Stephen, B1).** The compiler posts the
  stage's ordinary Swarm parameters to `ComfyGetGeneratedWorkflow` with *markers* in the slots that
  must become tags — the alphanumeric strings `V3SCENEMARKER7731` / `V3NEGATIVEMARKER7732` for the
  two prompts, the seed `1234567`, and one solid-colour placeholder PNG per image slot (references
  red / green / blue / cyan / orange / purple in order, init yellow, mask magenta) — then rewrites
  the returned graph: the marker text in `CLIPTextEncode.text` / `SwarmTextEncodeAdvanced.prompt`
  becomes `${comfyrawworkflowinputtextscene}` / `…textnegative`, `SwarmKSampler.noise_seed` becomes
  `${comfyrawworkflowinputseedseed}`, and each `SwarmLoadImageB64.image_base64` is matched to its
  placeholder — by exact base64 first, then by decoded colour within a channel-sum tolerance of 60
  (the generator re-encodes PNGs) — and becomes `${comfyrawworkflowinputimageswarminputimage<name>}`
  (the marker is Swarm's base64-fill switch, §38.1 "Measured in P5-1"); substitution order is
  mask → init → references. The numeric save node is replaced by the string-id
  `v3_save_final` (one save node per graph). A loader the compiler cannot match to a placeholder, or
  a marker it cannot find, is a `GraphCompileException` — the generator's shape changed under us —
  reported as `GRAPH_INVALID`, never patched around.
- **Literals stay literals.** `steps`, `cfg`, sampler, scheduler, the loaders' file names, the
  canvas size and the mask grow / blur are left exactly as the generator wrote them for the request;
  only the prompts, the seed and the images are tags. This narrows the "inputs that vary per attempt
  are tags" bullet above (which listed steps and cfg): a different step count is a different graph
  with a different `graphHash`, which is what makes the hash a provenance key — the artifact's hash
  names *the sampling parameters that produced it*, not a template that several parameter sets
  share. `start_at_step` is Swarm's `round(steps × (1 − creativity))`;
  `WorkflowCompiler.startAtStep` is the same formula so a caller can predict it, and the compiler
  logs a warning when the generated graph disagrees rather than overwriting the generator.
- **Per-actor pass = the plain `maskimage` inpaint graph (§38.5 item 2), full canvas.**
  `compileActorRegion` asks the generator for an `initimage` + `maskimage` + `initimagecreativity`
  + `maskgrow` / `maskblur` + `initimagerecompositemask` graph over the BASE image, the mask painted
  from the actor's boxes, the step count and creativity from the family's `inpaint` block in
  `capabilities.json` (FLUX.2 4 steps, creativity 1.0 pending the measure-both comparison Stephen
  asked for; SDXL 25 steps, creativity 0.6; grow 16 / blur 8 on both, the spike's values). The
  compiler places whatever references its caller passes as `promptimages`; the P5-1 plan's rule —
  the target actor's own portrait is the only reference on an ACTOR_REGION pass — is what the live
  test does and what the P5-2 executor wiring must enforce, because **nothing in product code
  calls the compiler yet** (`compileBase` / `compileActorRegion` / `persistGraphArtifact` have test
  callers only; the executor path is P5-2). `flux2*` `supportsMasks` is now `true` on the strength
  of the spike's one-box FLUX.2 inpaint; `useinpaintingencode` is excluded (Stephen, 2026-10-24).
- **Validation before submit — narrower than designed.** `NodeCatalogue` (the
  `/ComfyBackendDirect/object_info` snapshot, cached per server for an hour, dropped on a
  `RENDERER_REJECTED`) checks class names and input names, not values: an unknown `class_type`, a
  missing required input, a link to a node id that does not exist, a `${…}` tag the request does
  not fill, no non-numeric save node, and inline image data in a loader are **errors**
  (`GRAPH_INVALID`, nothing sent); an input name the catalogue does not list for the class is a
  **warning only**, because Swarm's own generated graphs carry inputs `object_info` omits
  (`lora_hooks`, `images`, `minimax_refs` on `SwarmTextEncodeAdvanced`) and the backend tolerates
  them. **Enum values and output-slot indices are not checked** — the design bullet above promised
  both; they are left to Swarm's `TryIsValid` and Comfy's own validation, which surface as
  `RENDERER_REJECTED`. When no catalogue can be fetched the adapter logs it and runs the structural
  checks alone (links, tags, save node, inline images) so an unreachable `object_info` degrades
  rather than blocks.
- **Fixture constraint worth knowing before writing an offline test.** The captured base fixture
  (`src/test/resources/olio/illustration/p50/base_flux2.workflow.json`) is the three-reference
  graph, so an offline compile against it must supply exactly three references; `references(n)`
  with another `n` fails with `Node 104 SwarmLoadImageB64 matches no placeholder image` — correct
  behaviour, not a bug (`TestWorkflowCompiler.noReferencesSendsNoPromptImagesAndKeepsNoLoader`
  asserts it). The live generator, asked for *n* references, emits *n* loaders.

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

**[2026-10-24 (P5-1), as built — where the graph, the request and the provenance actually live;
Stephen's answer B2 of 2026-10-24: "sibling JSON artifact per stage".]**

- **The graph body is a sibling `renderGraph` artifact on the stage node**, not a field of the
  image artifact and not part of the plan artifact (the §38.2 bullet "recorded with the patch in
  the plan artifact" and the §38.4 §32 line "the plan artifact holds the full graph string" are
  superseded by this). `PbRenderPlanUtil.persistGraphArtifact` writes one
  `olio.pb.artifact` of type `JSON`, role `renderGraph`, whose `text` is the envelope
  `{stage, graphHash, swarmVersion, graph}` — `graph` being the tagged API-format graph exactly as
  sent, nulls kept (`lora_hooks: null` is what the generator wrote and the artifact must say so).
  `ComfyGraph.assertNoInlineImages()` runs before anything is written: a loader carrying base64
  instead of an image tag is refused, so the artifact can never hold image bytes. One per stage
  per attempt; the image artifacts of that stage share its `graphHash`.
- **`artifact.backendGraph` on each image artifact is `{stage, graphHash, saveNodeId,
  swarmVersion}`** (`SwarmRendererAdapter.backendGraphJson`). `stage` is added to the §38.4 shape
  and `backendId` is omitted — Swarm's response does not identify the backend that ran the job, so
  the field would have been a guess. `graphHash` is the SHA-256 of the graph's canonical JSON
  (`ComfyGraph.graphHash`) and is the key that joins an image to its `renderGraph` artifact.
- **The stage's request record (`StageResult.requestJson` → `artifact.generatorRequest`) is
  `{stage, model, images, graphHash, inputs, referenceArtifactIds}`** with `inputs` *redacted* —
  text and seed values verbatim, every image input replaced by `"<N bytes png>"` — so it is
  bytes-free by construction (`SwarmRendererAdapter.graphRequestJson`). It is **not** what
  `PbArtifactUtil.isSanitized` accepts: the redacted map still carries the
  `comfyrawworkflowinputimage<name>` keys, which the key-name sanitiser strips. The pipeline's
  `recordImage` always runs `sanitizeGeneratorRequest` before `persistArtifact`, which drops those
  keys, keeps `stage` / `graphHash` / `referenceArtifactIds` and the text + seed inputs, and adds
  `referenceArtifactObjectIds`; the `renderGraph` artifact still names every image tag, so which
  loader took which reference is recoverable from graph + `referenceArtifactIds` and nothing is
  lost by the strip. The contract is asserted, not assumed
  (`TestGraphRequestShape.redactedInputsCarryNoBytes`: `isSanitized` false before the pipeline
  sanitiser, true after, content preserved). No product change was made to make the request record
  pass `isSanitized` directly — the redaction and the sanitiser have different jobs.
- **PNG parameters are not provenance for a raw render (measured in P5-0).** Swarm writes the
  *request defaults* into the PNG's `sui_image_params` block for a `comfyworkflowraw` render — not
  the graph's sampler values and not the filled tags. The spike's raw FLUX.2 re-post
  (`C:/tmp/p50-spike/01-base/raw_flux2.*`) was sent with `comfyrawworkflowinputseedv3seed = 424242`
  and a graph sampling 4 steps at 1024×768, cfg 2.0; its PNG says `steps 20, seed 1163099747,
  1024×1024, cfgscale 7.0`. So the adapter takes the seed from the inputs it sent
  (`GraphInputs.seed(TAG_SEED)`) and never from the returned PNG. A `SEED_RANDOM` request does not
  leave the choice to Swarm: the compiler draws the seed itself (`ThreadLocalRandom`, 1 …
  `Integer.MAX_VALUE`) and sends it as the tag value, so every compiled render has a known seed and
  replays byte-for-byte (§38.5 item 2). `SEED_UNKNOWN` / `seedReported=false` can only arise for a
  graph whose inputs carry no `seed` tag at all. Reading the PNG back for seed / steps / cfg would
  silently attribute a raw render to the wrong parameters.

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
  websocket message, with the node id). **[2026-10-24 (P5-1), as built:** the adapter classifies a
  Swarm `error` string as a graph rejection when it mentions an unsupported node / node type,
  "workflow", "invalid" or "comfy" — **except a session error** (`Invalid session`, which contains
  "invalid"): `SwarmGraphClient` retries a rejected session once with a fresh `GetNewSession`, and
  one that still fails is reported as a plain backend error (`ERROR_PREFIX_BACKEND`), because
  invalidating the node catalogue over an expired session would make the next render refetch
  `object_info` for nothing. Comfy-side execution errors arrive in the same `error` field over the
  HTTP route, so the "websocket message" wording above is the WS route's form of the same thing;
  V3 renders over HTTP in P5-1. Node-level error surfacing (which node failed) is a P5-2 item.**]**
- **§21:** cancel stays between stages; `InterruptAll` is still not used.
- **§32:** `artifact.backendGraph` holds `{graphHash, saveNodeId, swarmVersion, backendId}`;
  the plan artifact holds the full graph string. Add a `graphHash` dimension to the render
  timings. **[2026-10-24 (P5-1), as built: `{stage, graphHash, saveNodeId, swarmVersion}` — no
  `backendId` — and the graph string is a sibling `renderGraph` artifact, not the plan artifact;
  §38.3's as-built block is the record. The `graphHash` timing dimension is not added in P5-1:
  the in-JVM `RenderCalibration` is keyed by server + model pattern and records graph **BASE**
  renders only, skipping ACTOR_REGION (whose effective step count is `steps − start_at_step` and
  would skew the BASE EWMA); the stage dimension goes in with the persisted calibration record
  (§34 Q14 (b), P5-2).]**
- **§36.2 route list:** add `ComfyGetGeneratedWorkflow` and `ComfyBackendDirect/object_info`;
  `ComfySaveWorkflow` / `ComfyReadWorkflow` / `ComfyListWorkflows` are no longer used by V3.
- **§30 Phase 5 spike** becomes "measure the contract and capture Swarm's generated graphs against
  the live backend" (P5-0), not "author workflows"; the graph compiler itself is P5-1 — see §38.5. Phase 2 (`SdxlCompiler`, TEXT_ONLY) can ship on the plain-parameter
  path and still record `backendGraph` from `ComfyGetGeneratedWorkflow`, so the proxy mapping
  exists from the first V3 render.

#### 38.5 Measured in the P5-0 spike (2026-10-23 / 24) — the six items this section listed as unmeasured

Measured by `TestP50SwarmSpike` (Objects7, gated on `PB_SD_TESTS=1`, no DB, no LLM) against `.39`
with `flux2Klein_9b` and `juggernautXL_ragnarokBy`; 29 requests, 27 PNGs opened and described,
every generated graph and response saved under `C:/tmp/p50-spike/`; the per-render table, the
pixel diffs and the ledger of judgments are `PictureBookV3Implementation.md` §4.11 and §6.C 204.
Numbers are measurements on that GPU on that day, never thresholds (§34 Q14).

1. **IP-Adapter graph shape — captured; SDXL works loosely, FLUX.2 inert, FACEID broken.** With a
   real portrait in `promptimages` and `useipadapter = PLUS FACE (portraits)` the SDXL generator
   emits `SwarmLoadImageB64 → IPAdapterUnifiedLoader(preset) → IPAdapter(weight 0.8, start 0.0,
   end 1.0, standard)` into the model (10 nodes) and renders in 28.3 s — the same as a plain
   25-step SDXL render, so whether the weights were fetched then or were already on disk is not
   separable from the measurement. Identity is loose and the wardrobe bleeds between the two
   actors. On the FLUX.2 generator path the same nodes are emitted (`ImageBatch` ×2 over the three
   references, 22 nodes) **and the output is pixel-identical to the plain reference render** —
   IP-Adapter does nothing there; identity on FLUX.2 comes from the `ReferenceLatent` chain (item
   4). `FACEID PLUS V2` fails inside Comfy — `IPAdapterUnifiedLoaderFaceID: numpy.dtype size
   changed … Expected 96 from C header, got 88 from PyObject` — InsightFace's compiled extension
   against the venv's numpy; a venv repair on `.39`, not a Swarm or V3 matter (§36.5).
2. **`<region:>` / `<object:>` graphs — captured; `<region:>` is conditioning-only and soft,
   `<object:>` is two generations, plain `maskimage` inpaint is the usable per-actor substrate.**
   `<region:>` on an `initimage` (creativity 0.6, `globalregionfactor 0.5`) emits per region
   `SwarmSquareMaskFromPercent` + `CLIPTextEncode` + `ConditioningSetMask(0.5)` with
   `SwarmOverMergeMasksForOverlapFix` / `SwarmExcludeFromMask` / `SwarmCleanOverlapMasksExceptSelf`
   and `DifferentialDiffusion`, combined by `ConditioningCombine` (27 nodes for two actors, 54 for
   four with five references). It holds the base layout and takes a wardrobe edit on FLUX.2 but
   leaked the edit to the other actor in 1 of 3 seeds; on SDXL two overlapping boxes merged into
   one figure and one actor vanished; **on FLUX.2 the four-box fresh render (937 s) dropped the one
   actor whose box every other box overlaps** — `PbLayoutUtil.defaultPlacement`'s 4-of-4 layout
   overlaps and is not usable as `<region:>` input as it stands. `<object:>` is confirmed to run
   outside the generator (the captured graph is the plain one): a red umbrella prop was added on
   FLUX.2 in 355 s; on an actor it did not take the wardrobe edit (333 s). Plain `maskimage` on an
   `initimage` (`ImageToMask → SwarmMaskGrow 16 → SwarmMaskBlur 8 → SetLatentNoiseMask` over the
   `VAEEncode`d init, `DifferentialDiffusion`, `ThresholdMask → SwarmImageCompositeMaskedColorCorrecting`
   when `initimagerecompositemask` is set) **recomposites exactly outside the mask on both
   families** (0.0 % change in the untouched actor's box), in 77.5 s on FLUX.2 and 15.1 s on SDXL —
   this is the SEQUENTIAL_INPAINT substrate and the template for V3's own per-actor graph. Its
   limit on FLUX.2 Klein: `start_at_step = steps × creativity` = 4 × 0.6 → 2, too coarse for an
   **additive** edit (the scarf never appeared) and unable to introduce a figure that is not in the
   init (the four-actor inpaint repainted the two empty boxes as street). `useinpaintingencode`
   (`VAEEncodeForInpaint grow 6` + `SetLatentNoiseMask`, `start_at_step 11/25`) with a standard
   SDXL checkpoint leaves a **flat grey slab** — not a viable start (§36.5 corrected).
   `<segment:yolo-face_yolov8m.pt,0.6,0.5>` on FLUX.2 (the §38.1 subgraph, now with a seed +2
   sampler at `start_at_step 2` and three `ReferenceLatent` nodes) is a clean face-only pass:
   0.2 % of the frame changed outside the two faces, 28.7 % / 21.8 % inside them; CLIPSeg
   (`<segment:face>`) found one face of the two. Seeds are deterministic across JVMs (run 1 and
   run 2 base are byte-identical), so a pass re-renders its base exactly before applying itself.
3. **Text output frames — not observed; not requested.** A plain `GenerateText2ImageWS` render
   produced 30 frames: 4× `status + backend_status + supported_features`, ~19 `gen_progress` (the
   first with `is_preview: true`, `latent2rgb` previews), 4× `keep_alive`, one `image + batch_index
   + request_id + metadata`, then `discard_indices` and `socket_intention`. No graph in the spike
   had a text-output node, so the `progress.max == 12347` frame (§38.4) is still unmeasured — a
   P5-1 item once the QA-count node exists.
4. **FLUX.2 base graph — captured (18 nodes).** `CheckpointLoaderSimple flux2Klein_9b` +
   `CLIPLoader qwen_3_8b (type flux2)` + `VAELoader Flux\flux2-vae`; `EmptyFlux2LatentImage`;
   positive prompt through **`SwarmTextEncodeAdvanced`** (input key `prompt`, not `text`; carries
   `steps`, the 1.5× `width`/`height` and the `target_*` size, `guidance -1`); negative through
   `CLIPTextEncode`; each reference as `SwarmLoadImageB64 → VAEEncode → ReferenceLatent`, chained
   on the conditioning (`6 → 106 → 108 → 110`) into `SwarmKSampler` (`noise_seed`, 4 steps, cfg
   2.0, euler / simple, `start_at_step 0`, `end_at_step 10000`) → `VAEDecode` → `SwarmSaveImageWS`.
   The `ReferenceLatent` chain is what V3 must preserve when it extends the graph; identity and
   wardrobe held across three further seeds. A raw re-post of this graph with three tags
   (`6.prompt`, `10.noise_seed`, **`102.image_base64 → ${comfyrawworkflowinputimageactorA}`**) and a
   `v3_save_final` node passed `TryIsValid`, rendered in 108 s and took a prompt edit — the image
   tag fill into `SwarmLoadImageB64` is measured, not assumed.
5. **Timings on `.39` (AMD Radeon 8060S, 107 GB, ROCm 7.2.1, ComfyUI 0.42.0).** FLUX.2 BASE with 3
   references at 1024×768 / 4 steps: **101.5–102 s warm, 127–128 s cold** (checkpoint + text encoder
   reload after an SDXL render; ~20 GB resident); 1024² with no reference 38.6 s. ACTOR_REGION as a
   `<region:>` pass over the base (creativity 0.6, two regions): **175–194 s**. One-box mask inpaint
   77.5 s; `<segment:yolo>` 188 s (base + face pass); CLIPSeg 63 s; `<object:>` 333–355 s; four-box
   five-reference fresh `<region:>` 937 s (prep 0.3 s, generation 15.6 min, from a full unload).
   SDXL 25-step 1024×768: 28–53 s (4 regions 53 s); SDXL mask inpaint 15 s; `useinpaintingencode`
   9 s. HARMONIZE and UPSCALE were not in the spike. These replace the Strix Halo figures as the
   seed for §34 Q14 (b).
6. **Websocket route — works; see 3.** `GenerateText2ImageWS` at `ws://192.168.1.39:7801/API/GenerateText2ImageWS`
   with the same JSON body; 30 frames in 106.5 s for a BASE render; the image arrives as a path in
   the metadata frame, not as bytes.

Also measured on the way: `/API/GetNewSession` now reports **`0.9.8.4.GIT-5dc94afa`** (§36.1) and
nothing in §38.1 changed under it; `object_info` confirms `SwarmYoloDetection` (`model_name ∈
[face_yolov8m.pt]`, `index`, optional `class_filter`, `sort_order`, `threshold 0.25`), `SwarmClipSeg`,
`IPAdapterFaceID`, `IPAdapterUnifiedLoaderFaceID`, `SwarmLoadImageB64`; `system_stats` reports
`vram_free` before / after every render (≈ 99 GB free with SDXL resident, ≈ 79.5 GB with FLUX.2).
Nothing in this section is a decision; the Phase 5 decisions that these measurements feed are listed
in §34 under Q14.
