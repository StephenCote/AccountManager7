# PictureBook V3 — Illustration Architecture Design

**Date:** 2026-10-09
**Status:** Design only. Proposed, not ratified, no code written. **Grounding pass completed
2026-10-09 (§36):** every claim about current code was checked against the repository, and every
claim about the renderer/LLM was checked against the live servers — SD = SwarmUI on
`192.168.1.39:7801`, LLM = Ollama on `192.168.1.42:11434`. Corrections are applied in place and
marked **[verified 2026-10-09]**; the former **[verify]** markers are resolved.
**Inputs:** `PictureBookV3Notes.md` (target architecture), `PictureBookImageGenerationDesign.md`
(current behaviour and gaps G-1…G-36), `PictureBook2Plan.md` (workflow graph, §6 ComfyUI),
`PictureBookWorkflowOverhaul.md`, `PictureBookAsyncJobDesign.md`, `PictureBookSdConfigRefactor.md`.
**Not available:** the SequenceDev document that the notes refer to is not in the repository.
Where this design relies on SequenceDev (location library, background-first and sequential
inpainting), it uses the notes' description of it. **Also not available [verified 2026-10-09]:**
`PictureBookImageGenerationDesign.md` is cited above and listed in `aiDocs/README.md.merge.md`
(line 40, dated 2026-10-09), but it is **not in the working tree or in any commit** (`git log --all`
finds no such path). The G-1…G-36 references in this document therefore cannot be resolved until
that file is restored (§34 Q9).

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
| `AccountManagerObjects7` → `olio/illustration/` (**new package**) | The shared illustration service: `RenderPlan`, `StrategySelector`, prompt compilers, `ModelResolver`, `RendererAdapter` + capability model, `QaProvider`. It does not know about PictureBook or ChapBook, so ChapBook, character reimage and chat can use it. |
| `AccountManagerObjects7` → `olio/sd/` | Renderer adapter. `SwarmRendererAdapter` wraps the existing `SDUtil`/`SWUtil`, including Comfy workflows run through Swarm (§15). |
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
two-store drift (G-10) by construction.

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
  G-9/G-15.
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
| `poi` | FK `olio.pointOfInterest` (optional) | Optional link to an Olio world place. Not required; there is no map dependency |
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
STRUCTURE, NATURE, OTHER), `description`, `x/y/w/h`, `depth`, `importance` (LOW/MEDIUM/HIGH).
Elements are compiled into prompt text and into regional prompts. They are never referenced images
in V3.

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
    existing location names/aliases, the book style name.
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

### 9.1 When composition runs: up front vs. at first render (decision open)

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

**Recommendation: A, with B kept only as a fallback.**
- Compose up front as a pipeline phase.
- For any scene that has no composition at render time (LLM was down, migrated book, newly added
  scene), compose lazily for that scene only and record it as a degradation, then offer
  "re-compose in order" afterwards.
- Run a whole-book location pass after the per-scene calls.
- Offer a **"Quick start"** toggle that renders DRAFT images as each scene finishes composing, for
  users who want early images. This gets most of B's time-to-first-image without losing A's
  ordering.

**Your call.** The other parts of the design are the same under either option. Only §9, the job
kinds (§21) and the wizard flow (§26) change.

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
5. **Auto-approve** (ratified 2026-10-09: auto-approve pins, and it has a clear on/off toggle):
   - **Setting:** `olio.pb.book.autoApprove` (boolean), with an optional per-asset-type breakdown
     `autoApproveTypes` covering PORTRAIT, LOCATION, SCENE, COVER and CHAPBOOK_PAGE. The proposed
     default is **off**.
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
| `KontextCompiler` | Kept only if Kontext stays a supported model family | Uses the `kontext*` config fields that are ignored today (G-17/G-21). **[verified 2026-10-09] No Kontext checkpoint is installed on .39** (§36.1), while `flux2Klein_9b` and `flux2_dev` — which cover the same reference-editing role — are. Recommendation: retire (§34 Q4) |

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
wasted scene-image-prompt call (G-21/G-23) and the template-override cross-wiring (G-12/G-24).

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
  `savesegmentmask`) for auto-masked face fixes. **No YOLO model file is installed**
  (`yolomodelinternal` values = `[]`), so only the CLIPSeg form works until one is added (§36.5).
- Explicit `width`/`height` always (G-14; 64–16384). `images` = variant count (1–10000, plus
  `batchsize`). `variationseed`/`variationseedstrength` for re-rolls close to a selected variant.
  `fluxguidancescale` (default 3.5) for FLUX. `refinerupscale` (0.25–8) + `refinermethod`
  (PostApply | StepSwap | StepSwapNoisy) for the UPSCALE stage. `removebackground` (RemBG) for
  cut-outs. `promptimages` (the hidden reference-image parameter the code already uses),
  `enablereferencelatents`, `textencodedimage`, `usereferenceonly`.
- **Named Comfy workflows** via `comfyuicustomworkflow` (a dropdown of saved workflows; Swarm's own
  description says "Generally, do not use this directly"), for SEQUENTIAL_INPAINT and HARMONIZE.
  The workflow name and version are recorded in `artifact.backendGraph`. **Only one saved workflow
  exists on .39 (`Examples/Basic SDXL`)**, so every workflow this design names must be authored
  and saved through `ComfySaveWorkflow` (§34 Q5). The spike must confirm that a saved workflow's
  custom params accept the plan's images and values through this parameter.

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
artifact creation using `ByteModelUtil.setValue`, never a raw `FIELD_BYTE_STORE` write (G-20 rule).

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

- **One default policy (G-19/G-22):**
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

QA runs **on the renderer side, as a Comfy workflow through Swarm**. It does not go through the LLM
stack. QA is a pluggable, optional stage.

- **Interface:** `QaProvider.check(plan, imageBytes) → QaReport {result: PASS|WARN|FAIL, checks[]}`.
- **`SwarmQaProvider` (first implementation):** a named QA workflow that returns measurements, not
  images:
  - **Person and face detection.** Count visible people and faces, and compare against the plan's
    expected count (HERO + SUPPORTING + NAMED_BACKGROUND, ± crowd).
  - **Per-actor presence.** Check for a detected person or face inside each referenced actor's box.
  - **Identity.** Compare face embeddings between each detected face in an actor box and that actor's
    portrait artifact. This depends on a face-analysis node set being installed in the Comfy backend;
    without it the check reports `UNAVAILABLE`.
- **What the installed backend can do [verified 2026-10-09, §36.3]:** the ComfyUI behind Swarm on
  .39 has **no third-party node packs** (no Impact Pack, ReActor, InsightFace/ArcFace, IPAdapter-plus),
  so **identity is `UNAVAILABLE` on day one**. Detection and counting are feasible with built-ins:
  `MediaPipeFaceLandmarker` / `MediaPipeFaceMask` (face detection; auto-downloads its model),
  `RTDETR_detect` (person/object boxes), `SAM3_Detect` / `SAM3_TrackToMask`, `SwarmClipSeg`, and
  `SwarmYoloDetection` once a YOLO model file is installed. Swarm's `sam2` and `ipadapter` features
  can be installed by `POST /API/ComfyInstallFeatures` (§36.5); that call was prepared on 2026-10-09
  but not executed — it clones and pip-installs on .39 and needs your go-ahead.
- **Alternative provider — a vision LLM (decision: §34 Q12):** `.42` serves `qwen3-vl:8b-instruct`
  (and `valkyriesys/eudaimonia-dryad3-vision`) **[verified 2026-10-09]**. A `VlmQaProvider` ("how
  many people are visible; is there a red-haired woman on the left; list mismatches with this plan")
  covers count and presence *and* the semantic checks (outfit, location elements) that the Comfy
  route cannot, at the cost of going through the LLM stack this section otherwise avoids. Both fit
  the `QaProvider` interface; the decision is which ships first.
- **Not checked automatically in V3:** outfit, location elements and other semantic checks, unless
  the VLM provider is chosen. The user checks those in review.
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
| QA fails to run | Variant unaffected; QA artifact records `ERROR` |
| An exception anywhere | `run` and `node` are closed as FAILED in a `finally` block (G-19: no orphan RUNNING runs) |

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

- Variants are N artifacts on the terminal node from one attempt. They share the new
  `artifact.attempt` (the run objectId) and have their own `variantIndex` and `seed`.
- The variant strip shows the current attempt plus the approved artifact. Older attempts sit behind
  "History".
- **Retention:** keep the approved and selected artifacts forever. Keep the last K attempts (default
  3) per node. Move older GENERATED/REJECTED variants to ARCHIVED, and delete their bytes after D
  days (default 30). This needs a cleanup sweep in `PbHealthUtil` (it already finds orphans).
  Stage intermediates (BASE, regions) keep only those referenced by a retained final artifact.

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
**only sends sparse diffs** (fixes the "full entity overwrites children" class, G-27/G-32).

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
| **0 — Hygiene** (current code) | G-9/G-15 override leak, G-14 explicit width/height, G-12 template override, G-26 null guards, G-8 landscape flag, G-20 ByteModelUtil writes, G-19 run closure | G-11/G-25 style, negative and hires; G-32 book settings saved, page overrides not flattened | The notes' §60 criteria; each fix has a test, PB and ChapBook |
| **1 — Graph-first rendering + review** | Rendering through graph execution (PbNodeExecutor converged with v1 behaviour, fixing the G-24 divergences), live drift detection (§11.1), review status, auto-approve toggle (§10), async render jobs, restart recovery. V3 runs behind the `renderPath` switch (§28.1 Stage 1) | ChapBook pages become graph nodes with artifacts; same jobs and review | Leave the page → render continues; restart → Resume; a charPerson edit shows the affected scenes |
| **2 — Style, model resolution, compiler core; ChapBook goes V3** | `olio.pb.style` with inheritance and lock, `ModelResolver`, capabilities/presets resources, models-by-task route, style editor, estimate, `RenderPlan`, TEXT_ONLY strategy, `SdxlCompiler`, plan inspector | **ChapBook is the first product switched to V3** (TEXT_ONLY, zero actors), with up-front composition from the existing continuity composer. **ChapBook old path disabled (§28.1 Stage 2)** | One model policy across reimage/PB/ChapBook/chat; a missing model is reported before rendering; ChapBook verify checks start; chat scene renders through the illustration service (§24.1) |
| **3 — Locations** (world-scoped) | Location/variant models, LOCATION nodes, `approvedRender`, library Ux, approve/lock, clustering migration, background-first for TEXT_ONLY/DIRECT | Optional locations on ChapBook pages | Scenes 3/7/12 share one approved tavern; series chapters share it; a location failure doesn't fail scenes |
| **4 — Composition + wardrobe** | Composition/actor/element models, compose job (§9.1, per the decision), layout util, board Ux, `Flux2Compiler`, degradations, wardrobe alternates and carry-forward (§6) | Composition model replaces `sdPrompt`/`promptLocked` (already switched in Phase 2) | Any number of actors; prompt shown = prompt sent; nothing dropped silently; outfit change persists across scenes |
| **5 — Multi-character strategies** | **Spike first:** Swarm mask inpaint, region syntax, named Comfy workflows on the installed build, and FLUX.2 base + SDXL inpaint style coherence. Then SEQUENTIAL_INPAINT, REGIONAL_PROMPT, HARMONIZE and targeted regeneration. The spike's parameter names are already verified against the installed build (§36.2); what remains unmeasured is behaviour and timing, and the backend assets in §36.5 must be installed first | n/a (no actors unless G-35 is enabled) | Scenario B: 4 characters all rendered and identifiable on visual inspection; GPU memory within limits |
| **6 — Variants** | Variant batches, strip, history, retention sweep | Same | Scenario F |
| **7 — PictureBook goes V3** | Book workspace Ux replaces wizard steps 4–5. **PB1 disabled (§28.1 Stage 2)** | — | Scenarios A–G; PictureBook verify checks start |
| **8 — Visual QA** | Swarm/Comfy QA workflow (detection + optional face identity), `SwarmQaProvider`, QA Ux, QA-gated auto-approve | QA on ChapBook pages (detection is mostly n/a; checks gross defects only) | Missing-character detection on a deliberately broken render |
| **9 — Cover & book art** | Cover/title/chapter/series compositions and reader integration | ChapBook cover | A book and a ChapBook each have an approved cover |
| **10 — Removal** (post-completion) | §28.1 Stage 4 for PB1, after the verify checks and your sign-off | Same for the old ChapBook path | Code removed; nothing in the build references PB1 entry points |

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
| The installed Comfy backend lacks the assets the strategies assume (no IP-Adapter, no SAM2, no YOLO face model, no ClipVision, no inpaint checkpoint, no ControlNet, no Kontext — §36.3, §36.5) | Install list with exact commands in §36.5 (Q10/Q11). Until installed: identity QA is UNAVAILABLE, SEQUENTIAL_INPAINT uses a base SDXL checkpoint with `useinpaintingencode`, segment-based masking uses the built-in `SwarmClipSeg`/`SAM3`/`MediaPipeFaceMask` nodes |
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

**Still open:**
1. **When composition runs:** up front vs. at first render. The analysis is in §9.1;
   the recommendation is up front, with a lazy per-scene fallback and a "Quick start" toggle.
2. **Olio linkage:** should `olio.pb.location` optionally link to an `olio.pointOfInterest` in the
   world, or stay independent?
3. **Auto-approve default:** off (proposed) or on, and whether a per-asset-type breakdown is wanted
   or a single switch is enough.
4. **Kontext:** keep it as a supported family (needs `KontextCompiler`), or retire it?
   **Fact (§36.1):** no Kontext checkpoint is installed on .39 and the weights are gated on
   Hugging Face, so nothing in the current stack can exercise `SWUtil.newKontextSceneTxt2Img`
   or chat's Kontext default. **Recommendation: retire**, and change `resolveMode(sdConfig, true)`
   in `ChatService` to the FLUX.2 default at the same time.
5. **Comfy workflows:** which multi-stage workflows (inpaint, harmonize, QA) to author and keep in
   Swarm, and who owns their versions. **Fact (§36.2):** the only saved workflow on .39 is
   `Examples/Basic SDXL`; `ComfySaveWorkflow`/`ComfyReadWorkflow` are available for V3 to manage
   its own, and `comfyuicustomworkflow` selects one by name per request.
6. **Face identity QA:** is a face-analysis node set installed, or acceptable to install, in the
   Comfy backend? **Fact (§36.3):** nothing is installed — no InsightFace/ArcFace, no IP-Adapter,
   no third-party packs at all. The built-ins (`MediaPipeFaceMask`, `SwarmYoloDetection` without
   any YOLO weights, `SAM3_Detect`) give *detection*, not identity. See Q10/Q11.
7. **Default preset** for a new book's first Generate All (recommended: DRAFT).
8. **Verify-stage bar** (§28.1): is "two weeks with no rollback plus sign-off" the right gate
   for removal?

**New, from the grounding pass (2026-10-09):**

9. **Missing gap document.** `README.md.merge.md:40` lists `PictureBookImageGenerationDesign.md`
   as active, dated today, and the `G-n` ids throughout this design come from it, but the file is
   not in the working tree or any commit. Is it on another machine, or was it never saved? Until
   it exists, the G-numbers in §30 Phase 0 are unverifiable.
10. **Approve the node-pack install.** `ComfyInstallFeatures` with `features=ipadapter,sam2` was
    prepared and the exact command is in §36.5. I attempted it and was blocked by the
    tool-permission gate, so it needs you to run or approve it. It clones two repositories into the
    Comfy backend and **restarts the backend**, so run it when nothing is rendering.
11. **File drops that no API can do.** YOLO face weights, IP-Adapter weights and the ClipVision
    encoder live in folders that Swarm's `DoModelDownloadWS` refuses (not `T2IModelSets` types),
    so they are filesystem copies on .39 (§36.5). Optional: an SDXL inpaint checkpoint and a
    ControlNet. Which of these do you want?
12. **Vision-LLM QA as the first provider.** `.42` has `qwen3-vl:8b-instruct`. A `VlmQaProvider`
    that asks "which of these characters is present, and does the face match the portrait?" needs
    no Comfy install and reuses the chat path. Proposed as Phase 8's first provider, with the
    Comfy detection provider second. Agree?
13. **Who authors the Comfy workflows** (Q5 restated with the facts): V3 can save them through the
    API, but someone must build the graphs. Do you want me to draft them in the Phase 5 spike, or
    will you author them in the Swarm Ux?
14. **Re-measure on .39.** Every timing in this design (§15, §18) is from the Strix Halo iGPU.
    Shall the Phase 2 "estimate" feature wait for measured .39 numbers, or ship with a per-server
    calibration record that learns from actual runs?

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
| **Absent** | Kontext, any inpaint checkpoint, ControlNet, ClipVision, Embeddings, IP-Adapter, Redux, GLIGEN, YOLO weights (`yolomodelinternal` lists `[]`), SAM2 |

Consequences already applied: §14 (`KontextCompiler` recommended for retirement), §16 (model
resolver must report "not installed" from `ListModels`, not assume), §33.

#### 36.2 Swarm API and parameters [verified against `ListT2IParams` and the Swarm source]

Routes V3 will use: `GetNewSession`, `GenerateText2Image`, `ListModels`, `ListT2IParams`,
`ComfyListWorkflows` / `ComfySaveWorkflow` / `ComfyReadWorkflow`, `ComfyInstallFeatures`,
`DoModelDownloadWS`, `InterruptAll` is present but V3 cancels between stages (§21).

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
| Segment (auto-mask) | `segmentmodel`, `segmentsteps` (40), `segmentcfgscale` (7), `segmentmaskblur` (10), `segmentmaskgrow` (16), `segmentmaskoversize` (16), `segmentthresholdmax`, `segmentsortorder`, `segmentapplyafter`, `segmenttargetresolution`, `savesegmentmask`. `<segment:text,creativity,threshold>` uses CLIP segmentation by default (Swarm auto-downloads `clipseg-rd64-refined` on first use); a `yolo-` prefix selects a YOLOv8 model, which needs the §36.5 C weights |
| Batch / variants | `images` (1–10000), `batchsize`, `variationseed`, `variationseedstrength` |
| Canvas | `width`/`height` (64–16384), `fluxguidancescale` (3.5), `refinerupscale`, `refinermethod`, `removebackground` |
| References | `promptimages`, `enablereferencelatents`, `textencodedimage`, `usereferenceonly` |
| Routing / provenance | `comfyuicustomworkflow` (by saved name), `exactbackendid`, `webhooks`, `forwardrawbackenddata` |

**Not yet done:** none of these has been sent with a real image. The Phase 5 spike (§30) is still
required for behaviour and timing; it is no longer required for names.

#### 36.3 ComfyUI backend — nodes

1037 node types, **no third-party packs**. Usable built-ins for V3: `MediaPipeFaceLandmarker`,
`MediaPipeFaceMask`, `RTDETR_detect`, `SAM3_Detect`, `SAM3_TrackToMask`, `SwarmClipSeg`,
`SwarmYoloDetection` (no weights installed), the Swarm mask set (`SwarmMaskGrow`, `SwarmMaskBlur`,
`SwarmMaskThreshold`, `SwarmMaskBounds`, `SwarmSquareMaskFromPercent`, `SwarmCleanOverlapMasks`,
`SwarmExcludeFromMask`, `SwarmLatentBlendMasked`, `SwarmImageCompositeMaskedColorCorrecting`),
`DifferentialDiffusion`, `InpaintModelConditioning`, `ControlNetLoader`, `CLIPVisionLoader`,
`StyleModelLoader`, `GLIGENLoader` (loaders exist; their model folders are empty).

Absent: every `IPAdapter*` node, InsightFace/ArcFace/`FaceAnalysis*`, SAM2. So today the QA
provider can do **detection** (is there a face/person where the composition said) but not
**identity** (is it *this* character); §17 marks identity UNAVAILABLE until §36.5 lands.

#### 36.4 LLM server `192.168.1.42:11434` — models

Text: `qwen3:8b`, `qwen3:32b`, `qwen3-27b`, `gpt-oss:120b`, JOSIEFIED-Qwen3 8b / 30b, coder
models. Vision: `qwen3-vl:8b-instruct`, `valkyriesys/eudaimonia-dryad3-vision`. Embeddings:
`nomic-embed-text`, `bge-m3`. The composition step (§9) and location descriptions use the
analysis model through the existing chatConfig/LiteLLM route; a vision model is available for the
§34 Q12 QA provider without any install.

#### 36.5 Install list — what the API can do and what it cannot

**A. Node packs — one API call, needs your approval (Q10).** `ComfyInstallFeatures` takes a
comma list of feature ids from Swarm's `src/Core/InstallableFeatures.cs` (`ipadapter`,
`controlnet_preprocessors`, `frame_interpolation`, `gimm_vfi`, `comfyui_tensorrt`, `sam2`,
`bnb_nf4`, `gguf`, `extramodels`, `nunchaku`, `teacache`), clones each repository into the backend
and **restarts the backend**. Run from the dev machine when nothing is rendering:

```bash
SID=$(curl -s -X POST -H "Content-Type: application/json" -d '{}' \
  http://192.168.1.39:7801/API/GetNewSession | python -c "import sys,json; print(json.load(sys.stdin)['session_id'])")
curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"session_id\":\"$SID\",\"features\":\"ipadapter,sam2\"}" \
  http://192.168.1.39:7801/API/ComfyInstallFeatures
# expected: {"success":true}; then wait for the backend to come back (ListModels answers)
```

I attempted this call on 2026-10-09 and the tool-permission gate blocked it; it has **not** run.

**B. Model downloads the API accepts.** `DoModelDownloadWS` (WebSocket) takes `url`, `type`,
`name`, `metadata`; `type` must be a `T2IModelSets` key (`Stable-Diffusion`, `LoRA`, `VAE`,
`Embedding`, `ControlNet`, `ClipVision`, `Clip`), writes `{folder}/{name}.safetensors`, streams
`{current_percent, overall_percent, per_second}` then `{success:true}`, and does not refresh the
model list. Candidates, once A is in (otherwise the ClipVision encoder has no consumer):

| What | URL | `type` / `name` |
|---|---|---|
| ClipVision encoder for IP-Adapter | `https://huggingface.co/h94/IP-Adapter/resolve/main/models/image_encoder/model.safetensors` | `ClipVision` / `CLIP-ViT-H-14-laion2B-s32B-b79K` |
| SDXL inpaint checkpoint (optional) | your choice; none of the installed SDXL checkpoints is an inpaint variant | `Stable-Diffusion` |
| ControlNet (optional, for pose/depth in REGIONAL_PROMPT) | your choice | `ControlNet` |

**C. File drops no API can do (Q11).** These folders are not `T2IModelSets`, so the download
route refuses them ("Invalid type."); copy to .39 by hand:

| What | URL | Destination |
|---|---|---|
| YOLO face detector (for `segmentmodel` / `SwarmYoloDetection`) | `https://huggingface.co/Bingsu/adetailer/resolve/main/face_yolov8m.pt` | `(SwarmUI)/Models/yolov8/` |
| IP-Adapter SDXL weights | `https://huggingface.co/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter-plus_sdxl_vit-h.safetensors` and `.../ip-adapter-plus-face_sdxl_vit-h.safetensors` | the ipadapter pack's models folder (confirm after A installs it) |
| Kontext | gated on Hugging Face | not recommended (Q4) |

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
| `attempt`, `variantIndex`, `reviewStatus`, `durationMs` on artifacts | None exist in `src/main`; all four are new schema fields | §10, §23, §32 (§23 text not yet updated — see note below) |
| Retention can reuse the orphan sweep | `PbOrphanUtil` deliberately skips artifacts (`PbOrphanUtil.java:53-54`) and `PbHealthUtil` is a stale-graph scan; the retention sweep is a new routine | §23 (not yet updated) |
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

**Note on §23.** The edit that adds the two "new field / new sweep" facts above to §23 was blocked
by the tool-permission gate on 2026-10-09 and was not retried; §23 still reads as if `attempt`,
`variantIndex` and the retention sweep might exist. Treat this table as authoritative until §23 is
updated by hand.

#### 36.7 Prep checklist before Phase 0 starts (notes only; nothing implemented)

1. Decide Q9–Q14 (§34). Q9 blocks Phase 0 scoping; Q10/Q11 block Phase 5 and Phase 8.
2. Run §36.5 A, then B/C as decided; re-probe `ListModels` and `yolomodelinternal` and update
   §36.1.
3. Spike script (Phase 5, no product code): one `GenerateText2Image` per group in §36.2 — a
   masked inpaint over a FLUX.2 base render with `juggernautXL_ragnarokBy`, one `<region:>`
   prompt, one `<segment:>` with `SwarmClipSeg` — timed on .39, outputs looked at. Record the
   timings against §15/§18 and replace the Strix Halo figures.
4. Confirm the ipadapter pack's model folder path after install (needed for §36.5 C).
5. Re-read `PictureBookImageGenerationDesign.md` once it exists and reconcile the G-n ids in §30
   Phase 0.
6. Phase 0 starts with the hygiene items in §30 and their tests; nothing in Part VII changes that.
