# PictureBook 2.0 — Design & Implementation Plan

**Date:** 2026-08-11
**Status:** PLAN ONLY — awaiting Stephen's ratification of §9 open questions. No code written.
**Supersedes (on completion):** `PictureBookDesign.md` §1-8 storage model; `PictureBookSdConfigRefactor.md` config model.

Three changes, planned together because they share one root cause:

1. **Olio compartment.** One `Books` universe holding all static corpora; one world per book.
2. **Workflow-oriented image handling.** A persisted DAG, so any image is viewable in the context of
   the nodes that produced it, and an edit regenerates dependent branches.
3. **Optional ComfyUI backend** alongside SwarmUI, for native graph-level control.

The root cause they share: **PictureBook has no persisted graph and no compartment.** A book is an
`auth.group` in the user's home; a scene is a `data.note` with JSON in its `text` field; the whole book
config is one JSON blob in a note named `.pictureBookMeta`. Nothing records which record produced which
input. That is why images can't be traced, why edits can't propagate, and why the foreign sub-records
collide in the user's home group (KI-42/KI-60).

> **Verification note.** Every file:line in this document was read, not inferred. Claims that were
> *not* verified are marked **UNVERIFIED** and must be checked before being relied on.

---

## 1. Goal & scope

**In scope**
- New persisted `olio.pb.*` models (book, series, scene, workflow, node, binding, artifact, run) in Objects7.
- A `Books` universe + per-book worlds via a new custom init rule; the three blockers in §4.
- Persisting the artifacts currently discarded, with real provenance (which record produced each input, by objectId).
- Per-book PBAC role model, ownership matrix, read-path/create audit.
- Optional ComfyUI backend (§6).
- REST additions (transport only), Ux752 workflow view, migration of existing books.

**Explicitly out of scope**
- **KI-60's fix.** Stephen owns it. Nothing here touches `PathUtil`.
- Full convergence of the PictureBook and chat composite paths (KI-44/45/47) beyond extracting PB's own stage seams — see §8.
- Migrating game/arena callers off the hardcoded `"My Grid Universe"/"My Grid World"` pair. Additive overloads only.
- KI-49, KI-27, ISO42001, SDUtil decomposition beyond the node seams.
- Adding schema defaults near `flux2Cfg`, `flux2ReferenceSize`, `flux2IncludeLandscapeRef`, `kontextModel`, `mannequin*` — a default there makes `flux2Defaults.json` dead code.

---

## 2. Model design

### 2.1 Conventions applied to every new model

Copy `olio.narrative`
(`AccountManagerObjects7/src/main/resources/models/olio/narrativeModel.json`) exactly:

```
"likeInherits": ["data.directory"],
"inherits": ["common.groupExt", "common.baseLight"],
"query": ["id", "groupId", "objectId", "ownerId", "organizationId"],
"group": "<fallback name>",
"dedicatedParticipation": true   // only where the model owns a foreign list
```

plus its **own plain `name` field** — deliberately *not* `common.nameId`, which carries the `\S`
validation rule that makes a PATCH omitting `name` fail silently (`model-api.md`). `olio.narrative`
already dodges this the same way.

Three hard rules:

1. **Every PB2 model is group-scoped. None groupless.** A record with a `groupId` gets the group-only
   access shortcut; a groupless one forces field/role checks. This is a security decision.
2. **PB2 never uses the `schema.getGroup()` fallback.** Every create passes an explicit world-scoped
   path. `"~/" + schema.getGroup()` at `PictureBookUtil.java:2189-2195` is precisely what produced the
   KI-60 collision target.
3. **No schema `default` on any config-ish field** (see above).

**How new tables land without a reset.** `IOSystem.open`
(`AccountManagerObjects7/src/main/java/org/cote/accountmanager/io/IOSystem.java:120-153`) always scans
`ModelNames.MODELS`; for an identity model with no table it runs `dbUtil.generateNewSchemaOnly(schema)`
(`:125`), and for an existing table it emits `ALTER TABLE … ADD COLUMN` patches (`:135-139`).
`properties.isReset()` is never needed. Add JSON + register in `OlioModelNames.MODELS` → tables appear
on the next Tomcat boot or JUnit run. Tables land as `A7_olio_pb_book_0_1` etc. Column drops stay
behind the off-by-default `isDropColumns()`.

### 2.2 The models

**`olio.pb.book`** — lives in `{world}/Book`.
`name`, `description`, `slug` (indexed, constraint `"slug, organizationId"`), `world` (foreign
`olio.world`), `series` (foreign `olio.pb.series`), `chapter` (int), `sourceData` (foreign `data.data`),
`sdConfig` + `compositeSdConfig` (foreign `olio.sd.config` — **real persisted records**, replacing the
JSON blob and making config queryable), `bookStatus` (enum), `compositionContext`, `createdByObjectId`.

**`olio.pb.series`** — lives in the **universe's** `Book` group. `name`, `description`, `universe`
(foreign `olio.world`), `bookCount`.
The scope for "chapters of one book"; the universe remains the scope for "all books". Chapter linkage
is a PB field, **never** `olio.world.basis` — `basis` must keep pointing at the `Books` universe for
every book world, because `Decks.getRandomTraits(user, parWorld, n)` and friends read traits/colors/
patterns out of the basis, and a book world's own corpora groups are empty (`WorldUtil.loadWorldData`
loads into the universe only, `WorldUtil.java:194-221`).

**`olio.pb.scene`** — lives in `{world}/Book`.
`name`, `book` (foreign), `index` (int, indexed), `title`, `description`, `summary`, `setting`,
`action`, `mood`, `blurb`, `userEdited`, `characters` (foreign list `olio.charPerson`,
`dedicatedParticipation`), `sceneNode` (foreign `olio.pb.node`).
Replaces the per-scene `data.note` JSON (`PictureBookUtil.java:2900-2922`). Scene order becomes an
indexed column instead of array position in a blob; `PUT /scenes/order` becomes N patches on `index`.

**`olio.pb.workflow`** — lives in `{world}/Workflow`. One per book.
`name`, `book` (foreign), `graphVersion`, `graphStatus` (CLEAN/DIRTY/RUNNING/FAILED), `nodeCount`,
`lastRun` (foreign `olio.pb.run`). No `nodes` foreign list — nodes point up instead, so the
"foreign lists aren't populated" trap is avoided.

**`olio.pb.node`** — lives in `{world}/Workflow`. Mirrors `tool.planStep`
(`models/tool/planStepModel.json`) but persisted; `tool.planStep` itself is unusable because
`ioConstraints:["undefined"]` makes `DBUtil.isConstrained` treat it as non-persisted
(`DBUtil.java:329-331`).

- `name`, `workflow` (foreign, queried by long id)
- `nodeType` (enum) — taxonomy lifted verbatim from `TestPictureBookCustom`'s own step banners:
  `SOURCE_TEXT`, `SCENE_EXTRACT`, `SCENE`, `CHARACTER`, `CHARACTER_DESCRIPTION`, `APPAREL`,
  `MANNEQUIN`, `PORTRAIT`, `SCENE_PROMPT`, `LANDSCAPE_PROMPT`, `LANDSCAPE`, `REFERENCE_STRIP`,
  `COMPOSITE`, `PAGE`, `BOOK_ASSEMBLY`
- `nodeStatus` (enum): `PENDING`, `READY`, `RUNNING`, `DONE`, `DONE_UNVERIFIED`, `STALE`, `FAILED`, `SKIPPED`
- `pinned` (boolean) — **separate from status**; this is today's `userEdited`. Propagation still marks a
  pinned node STALE (knowing "your approved output is now inconsistent" is worth having); the executor
  refuses to re-run it without `force=true`.
- `ordinal`, `sceneIndex`, `scope`/`scopeRef` — display + subgraph grouping
- `promptTemplateName`, `promptText` — the resolved prompt cache, replacing `scenePrompt`/
  `landscapePrompt` inside the scene JSON (`PictureBookUtil.java:596-611`)
- `configOverride` (string) — JSON of only the explicitly-set config fields; see §2.4
- `inputHash`, `configHash` — as of the last successful run
- `createdByObjectId`, `lastError`, `lastRunAt`

**`olio.pb.binding`** — lives in `{world}/Workflow`. **This is the edge, and the answer to "which
record produced this input".**

- `node` (foreign) — the consumer
- `role` (string, indexed): `sourceText`, `sceneText`, `characterDescription`, `portrait0`, `portrait1`,
  `landscape`, `landscapeRef`, `initImage`, `referenceStrip`, `apparel`, `mannequin`, `chapterSource`
- `sourceNode` (foreign, nullable) — the producer; null for external roots
- `sourceArtifact` (foreign `olio.pb.artifact`, nullable) — **the exact artifact revision consumed**
- `refModel` + `refObjectId` — for inputs that are ordinary AM7 records (`olio.charPerson`,
  `olio.apparel`, `data.data`)
- `valueText`, `valueHash` — literal inputs (a seed, a hand-typed prompt)
- `required` (boolean)

An edge model rather than an embedded list means one artifact can be consumed by many nodes, and the
graph records *which revision* each consumer saw — structurally impossible today (a grep for
`derivedFrom|provenance|lineage|sourceImage|parentImage|generatedFrom` returns **zero hits** across
Java, JS and model JSON).

**`olio.pb.artifact`** — lives in `{world}/Artifacts`. Provenance wrapper; **bytes stay in `data.data`**
in `{world}/Gallery` (the existing uniform shape — no new blob model).

- `name`, `artifactType` (enum: `TEXT`, `PROMPT`, `IMAGE`, `IMAGE_STRIP`, `COMPOSITE_CANVAS`, `JSON`, `RECORD_REF`)
- `data` (foreign `data.data`), `text`, `refModel`/`refObjectId`
- `producedByNode` (foreign), `role`
- `revision` (int), `supersedes` (foreign self), `current` (boolean) — version chain, so old images
  stay viewable instead of being overwritten
- `seed` (long), `sdConfigSnapshot` (foreign `olio.sd.config`) — **per-artifact** snapshot of the
  *effective* config actually used. Replaces `persistBookSdConfig`
  (`PictureBookUtil.java:456-465`, called `:3340-3342`), which overwrites `meta.sdConfig` on every
  generate and keeps exactly one snapshot per book.
- `generatorRequest` (string) — the **sanitized** backend request JSON. Fixes two defects by
  construction: `initImage`/`promptImages` are replaced with the **artifact objectIds** of the
  references rather than inlined base64 (today `SWTxt2Img.initImage` `:82-84` and `promptImages`
  `:92-94` have no `@JsonIgnore`, so every FLUX.2/classic composite persists multi-megabyte base64 in
  an attribute), and `SWCommon.session_id` is omitted (today the Swarm session id is persisted with
  every image). The existing `s2i` attribute keeps being written unchanged for one release.
- `contentHash`, `mimeType`, `imageWidth`, `imageHeight`, `byteLength`
- `backend` (enum `SDAPIEnumType`) + `backendGraph` (string) — see §6

**`olio.pb.run`** — lives in `{world}/Workflow`.
`name`, `workflow` (foreign), `runStatus`, `startedAt`/`completedAt`, `requestedNodeIds`,
`executedNodeCount`, `failedNodeCount`, `error`, `chatConfig` (foreign `olio.llm.chatConfig`),
`createdByObjectId`. Mirrors `tool.plan`'s `totalExecutedSteps`/`executed`.

Constants go in `OlioModelNames` / `OlioFieldNames`; the enums beside `StepTypeEnumType`/
`StepStatusEnumType` in `org.cote.accountmanager.schema.type`.

**The six existing `olio.pictureBook*` DTOs stay exactly as they are** — they are the REST contract the
Ux depends on (`PictureBookService.java:120-131` `ensureSchema` → `olio.pictureBookRequest`), and
phase 3 dual-writes so they keep working.

### 2.3 Staleness & dirty propagation

Two representations, one authoritative:

- **Authoritative: `inputHash`.** Stable SHA-256 over, in fixed order: `nodeType` + for each binding
  (sorted by role) `role` + (`sourceArtifact.contentHash` ?: `refModel`+`refObjectId`+`refRevision` ?:
  `valueHash`) + `configHash` (hash of the *merged effective* config, not the override) + `promptText`
  hash + a `PB_PIPELINE_VERSION` constant. A node is stale iff `recompute(node) != node.inputHash`.
- **Denormalized: `nodeStatus`.** So "show me stale nodes" is one indexed query, not a graph walk.
  Written by `PbGraphUtil.markStaleDownstream`, repaired by `PbGraphUtil.recomputeStatus`. State
  plainly in the code: the hash is truth; the status is a repairable cache and is never trusted for
  correctness decisions.

Propagation: on a new artifact revision (or a change to a referenced record), find every binding whose
`sourceArtifact` is the superseded artifact or whose `sourceNode` is the changed node → set consumer
`nodeStatus = STALE` → recurse breadth-first with a visited set. Pinned nodes are marked, not re-run.

**Detecting a change to an external record — the mechanism, added in design review round 1.** The
formula above names `refRevision`, but there is no such field and **`olio.charPerson` carries no
revision or content hash** (journaling is WIP and not enabled on it). Without a mechanism, editing a
character would never mark anything stale — which is the *headline* use case, and exactly the pattern
the reference test hand-rolls (`if ((int)duna.get("age") != 15) {…}`,
`TestPictureBookCustom.java:1027-1044`). That edit is a **record** change, not an artifact supersession,
so artifact chaining alone cannot see it.

So `olio.pb.binding` gains **`refHash` (string, 128)** and the design gains a declared, per-model
**watched field set** — e.g. for `olio.charPerson`: `name, firstName, lastName, gender, age, race,
ethnicity, hairColor, eyeColor, hairStyle, alignment` plus the `pbDescription` attribute and the
`store.apparel` in-use set; for `olio.apparel`: its wearables' ids and `inUse` flags. `refHash` is a
stable hash over that set, computed **at bind time** and **recomputed** during
`PbGraphUtil.recomputeStatus`. A node is stale when any binding's recomputed `refHash` differs from the
stored one.

Two honest consequences: (a) the watched set is a **policy decision, not a derivation** — a field
outside it changes nothing, so the set must be declared in one place, documented, and covered by a test
that edits a watched field and an unwatched field and asserts the different outcomes; (b) recompute
costs one projected read per referenced record, so `recomputeStatus` is a deliberate operation
(invoked on opening a book's workflow view, and after a character edit), **not** something to run per
request.

Also: `inputHash` is null until a node's first successful run, so "stale iff `recompute != inputHash`"
would mark every `PENDING` node stale. Carve-out: a node with a null `inputHash` is `PENDING`/`READY`
(by whether its required bindings resolve), never `STALE`.

And note `configHash` folds in the merged effective config, so **editing `olio/sd/flux2Defaults.json`
invalidates every node in every book** — the same class as the `PB_PIPELINE_VERSION` risk in §10, and
it needs the same loud logging.

Cycle safety: `HierarchyValidator.checkHierarchy` only covers `parentId` chains, so a DAG needs its own
guard — `PbGraphUtil.validateAcyclic(workflow)`, explicit DFS with a colour map, called **before** a
binding is persisted; reject with `PictureBookException(400, …)`.

This replaces exactly the two things the reference test hand-rolls: manual invalidation
(`clearSceneCache` → `clearCachedScenePrompts(sceneOid)`, `TestPictureBookCustom.java:1102-1104`) and
manual staleness checks (`if ((int)duna.get("age") != 15) { re-imprint; re-apparel; re-portrait }`,
`:1027-1044`).

### 2.4 Config per node vs per book

Precedence: **node `configOverride` → book `sdConfig` → `olio/sd/flux2Defaults.json` →
`Flux2Defaults` constants** (`Flux2Defaults.java:31-39`, getters `:112-123`).

The node override **cannot** be a persisted `olio.sd.config` record: a record instantiated via
`Factory.newInstance("olio.sd.config")` materialises every defaulted field, so "override" becomes
indistinguishable from "default" — the exact reason `configModel.json:330,337,361,385` omit defaults.
So `configOverride` is a **string holding the JSON of only the explicitly-set fields**, produced with
the existing mechanism `cfg.copyRecord(changedFieldNames).toString()` (`toString()` serialises only
set fields; `toFullString()` serialises everything — `model-api.md`). Merge at run time into a working
copy. `olio.pb.artifact.sdConfigSnapshot` *is* a full record, because there the point is to freeze what
was actually sent.

The live `flux2Defaults.json` `steps: 4` vs `FALLBACK_STEPS = 24` disagreement stays as-is; it is tuned
for the distilled `flux2Klein_9b` and is deliberate.

### 2.5 Artifacts that must now be persisted

| Today | Becomes |
|---|---|
| classic composite canvas → `./comp-*.png` debug dump (`PictureBookUtil.java:3757-3758`) | `COMPOSITE_CANVAS` artifact + `data.data` in `{world}/Gallery` |
| Kontext stitched strip → `./land-*.png` | `IMAGE_STRIP` artifact |
| FLUX.2 letterboxed references — base64 in-request only | `IMAGE` artifacts, referenced by objectId from `generatorRequest` |
| chat/PB landscape bytes (`SDUtil.generateLandscapeBytes:1224-1292` returns raw bytes, persists nothing) | new **overload** that persists and returns an artifact; the byte-returning method untouched so chat is unaffected |
| non-book portraits deleted immediately (`PictureBookUtil.java:3591`) | kept as `PORTRAIT` artifacts in `{world}/Gallery/Characters` — the only reason to delete was home-group pollution, which the compartment removes |
| one overwriting book config snapshot | per-artifact `sdConfigSnapshot` |
| `null, null` for systemCharacter/userCharacter on every book image (`:3613, :3701, :3733, :3762`) | real attribution via bindings with roles `portrait0`/`portrait1`; chat already passes real oids (`ChatService.java:1424`) |
| `imageType` set only for `scene`/`landscape`/`animal` (`SDUtil.java:1070, :1198, :1553`); portraits/mannequins/`createImage` have none | `artifactType` + `role` become the source of truth; the attribute keeps being written for compatibility |

---

## 3. Olio universe / world design

### 3.1 Layout

```
/Olio/Universes/Books                      <- the ONE universe for all books (per organization)
    Colors Names Surnames Words Dictionary Occupations Patterns Traits   (-> /Library/* shared)
    Apparel Wearables Qualities            <- apparel templates (ApparelUtil.java:198-214)
    Population Narratives Profiles ...     <- "shared up to universe" destinations
    Locations                              <- EMPTY, by design
    Book                                   <- olio.pb.series records (new group)
    Worlds
/Olio/Universes/Books/Worlds/{bookSlug}    <- one world per book, its own 36 WorldFactory groups
    Population                             <- olio.charPerson
    Narratives Profiles Statistics Stores Instincts Personalities States
                                           <- the 7 groups that today land in the USER'S HOME
    Apparel Wearables Qualities            <- already Olio-owned today
    Gallery, Gallery/Characters            <- data.data image bytes
    Events                                 <- the minimal root event
    Book                                   <- olio.pb.book/.scene + the two olio.sd.config records
    Workflow                               <- olio.pb.workflow/.node/.binding/.run
    Artifacts                              <- olio.pb.artifact
```

`WorldFactory.implement()` (`WorldFactory.java:33-86`) already creates 36 DATA groups per world *and*
per universe — so **every destination PictureBook needs already exists**, including `Narratives`,
`Profiles`, `Statistics`, `Stores`, `Instincts`, `Personalities`, `States`, `Apparel`, `Wearables`,
`Qualities`, `Gallery`. Only `Book`, `Workflow`, `Artifacts` are new, created by the custom rule under
the world's own container rather than added to `olio.world`/`WorldFactory`, so game worlds don't grow
three groups they'll never use. *(Judgment call — §9 Q11.)*

`features = new String[0]` for both universe and world ⇒ `loadLocations` skips `GeoParser.loadInfo`
(`WorldUtil.java:120-122`) while every other corpus loads. **Static data yes, locations no, with no
fork of `loadWorldData`.**

### 3.1b Universe/world lifecycle is PictureBook's, and hidden from the user

**RATIFIED DIRECTION (Stephen, 2026-08-11):** *"what may not be there is the get/create/delete/list
universes and worlds — unless that'll all be handled in PictureBook and hidden from user (simpler Ux I
think), so PictureBook meta includes the foreign keys to the universe and world."*

**The gap is real — verified.** `WorldUtil` is referenced **nowhere** in
`AccountManagerService7/src/main/java/org/cote/rest/services`. The only world-shaped endpoint in the
whole REST surface is `GameService.java:875` `/isInWorld/{objectId}`, a membership check. `WorldUtil`'s
entire public API is `getWorld` (`:40`), `getCreateWorld` ×2 (`:51`, `:55`), `cleanupWorld` (`:227`) and
`getWorldGroups` (`:312`). So there is **no list-universes, no list-worlds, no create-by-name, and no
safe delete** exposed anywhere. Today everything funnels through the hardcoded
`OlioContextUtil.getGridContext("My Grid Universe","My Grid World")` (`:35`), and the Ux's
`/Olio/Universes` browsing (`tree.js:329-332`, `list.js:535`) is generic `auth.group` browsing, not an
`olio.world` API.

**Decision: do not build world management. PictureBook owns the lifecycle; the book is the only
user-facing handle.** The FKs this needs are already in §2.2 — `olio.pb.book.world` (foreign
`olio.world`) and `olio.pb.series.universe`.

| Operation | User-facing form | Implementation |
|---|---|---|
| **list** | list **books** — `olio.pb.book` is group-scoped and queryable, so an ordinary `/rest/model/search` with a numeric `organizationId` condition | **no world enumeration.** Strictly better than listing worlds: the listing is PBAC-filtered on the book records themselves |
| **get** | `GET /{bookObjectId}` | book → `world` FK → find-only context (§5.5) |
| **create** | `POST /olio/picture-book/create` | the *only* place `getCreateBookContext` runs; creates the world, and `getCreate`s the `Books` universe if this is the org's first book |
| **delete** | `DELETE /{bookObjectId}` | per-record teardown — see the hard constraint below |

Consequences, and they are mostly simplifications:

1. **No new REST surface for worlds** ⇒ no world-management endpoints to authorize, no world picker, no
   way for a user to name or address a world directly. Smaller attack surface, and it keeps §5.3's
   per-book roles as the only access mechanism.
2. **There is no "create universe" operation at all.** The `Books` universe is a per-org singleton
   created lazily on first book creation — which is already how `OlioContext.initialize()` step 3 behaves
   (`:317`). This settles **§10 Q8** in favour of per-organization.
3. **Phase 1b narrows.** The Ux does **not** need a world selector. The user selects a **book**; the book
   resolves the universe/world ids; those travel with the call per §4 Blocker 2. This is consistent with
   the earlier ratified direction — *switching books **is** switching worlds*, transparently. What the Ux
   must hold is a current-book context, not a current-world one.
4. **Delete must not use `cleanupWorld`.** `WorldUtil.cleanupWorld` (`:227`) → `cleanupLocation` →
   `IOSystem.getActiveContext().getWriter().delete(lq)` (`:283-298`) is a **raw bulk delete that bypasses
   PBAC entirely** across ~30 groups, running as olioUser. Deleting a book world must instead reuse the
   per-record recursive teardown PictureBook already has — `deleteGroupRecursive`
   (`PictureBookUtil.java:4212-4289`, the KI-32 fix), generalised over the world's groups via
   `getWorldGroups` (`:312`) — so every delete passes `AccessPoint.delete` and the `{bookSlug} Writer`
   Delete grant is the gate. **Note the existing enumeration inside `deleteGroupRecursive` uses the
   unauthorized `getSearch().findRecords` (`:4220,4228,4255,4281`); generalising it is the moment to fix
   that, not to propagate it.**
5. **Orphan worlds become possible and need an answer.** If a world is created but the `olio.pb.book`
   record isn't, nothing points at it and it is invisible *and* unreclaimable — there being no
   list-worlds API is exactly what makes it unreclaimable. This is not hypothetical: it is the direct
   consequence of §3.3's `initialized = true`-before-grants gap combined with the swallow-all catch at
   `:403-406`. **Mitigation:** create the `olio.pb.book` record **first**, in the same logical operation,
   and treat a world with no referencing book as reclaimable by an admin-only reconcile utility (not a
   REST endpoint). Add a test that a failed book creation leaves no orphan, or leaves one that reconcile
   finds. **§10 Q17.**
6. **`olio.world.name` must be derived, not user-supplied.** The book `slug` (already constrained unique
   per org, §2.2) is the world name. A user-typed world name would reintroduce the collision class the
   compartment is meant to remove.

### 3.2 `BookWorldInitializationRule`

New class `…/olio/rules/BookWorldInitializationRule.java`, extends `CommonContextRule`. Must be
**first** in `config.getContextRules()` because `initialize()` breaks on the first non-null `generate()`
(`OlioContext.java:351-357`).

- `pregenerate(ctx)` — **no-op.** Explicitly does NOT call `GeoLocationUtil.prepareMapGrid` (`:119`) or
  `checkK100` (`:162`) — the entire difference from `GridSquareLocationInitializationRule.pregenerate`
  (`GridSquareLocationInitializationRule.java:50-53`).
- `generate(ctx)` —
  1. `EventUtil.getRootEvent(ctx)`; if non-null return it (same idempotency guard as
     `GridSquareLocationInitializationRule.java:67-71`).
  2. Create the three PB groups via `makePath(ctx.getOlioUser(), MODEL_GROUP, worldGroupPath +
     "/Book"|"/Workflow"|"/Artifacts", DATA, orgId)`.
  3. Create and return a **minimal root event** in `world.events.path`: `name = "Book " + worldName`,
     `type = CONSTRUCT`, `eventStart/Progress/End = config.getBaseInceptionDate()`, **no `location`**,
     no realm.
- `postgenerate(ctx)` — nothing of its own. `GenericItemDataLoadRule` is added as the **second** rule
  (reuse, not reimplementation) so `ActionUtil.loadActions` + `ItemUtil.loadItems` +
  `BuilderUtil.loadBuilders` + `AnimalUtil.loadAnimals` run — `BuilderUtil` is what resolves
  `ApparelUtil.getApparelTemplate`, and PB's apparel path needs those templates in the universe.
- `generateRegion` — no-op (never called; no realms). `selectLocations` — `return new BaseRecord[0];`

**Must NOT be called anywhere in this rule:** `GeoLocationUtil.prepareMapGrid/checkK100/prepareK100/
prepareCells/newLocation/createLocation/randomLocation/getRegionLocations`,
`CharacterUtil.populateRegion`, `RealmUtil.getCreateRealm`, `LocationPlannerRule`,
`RandomLocationInitializationRule`.

### 3.3 The `rootEvent == null` gate

`OlioContext.java:359-361` throws `OlioException("Failed to find or create a new region")` when no rule
returned an event, and the whole body is wrapped in `try/catch` at `:403-406`, so the caller silently
gets `initialized == false`.

**Chosen approach: return a real minimal root event; do not change the gate.** Rationale: (a) zero
behaviour change for grid/arena/agent contexts; (b) `clock = new Clock(EventUtil.getLastEpochEvent(this),
EventUtil.getRootEvent(this))` at `:372` wants a root event, and a null there is an unaudited risk
across every `Clock`/`realmClock` consumer; (c) a root event is a genuinely useful per-book time anchor
for chapter ordering. The rejected alternative (a `config.requireRegion` flag that skips the throw)
forces an audit of every `getClock()` consumer and adds a knob whose only job is to disable an error —
and this codebase already has one such dead knob (`useSharedLibraries`,
`OlioContextConfiguration:31,77-83`, never read; `WorldUtil.java:36-37` uses its own static with a TODO).

**Two findings on this path.**

- **Three misleading ERROR lines per book init.** `getRealms()` (`:515-534`) finds zero realms → create
  branch → `GeoLocationUtil.getRegionLocations` (`GeoLocationUtil.java:823-833`) logs
  `"Zero region events were found"` and returns empty — **it creates nothing**, so a book world stays
  location-free (good). Then `startOrContinueRealmEvents()` (`:430-464`) logs `"No realms detected"` at
  `:436`, increments `errors`, returns false → `initialize()` logs `"Failed to start realms"` at `:391`.
  **Response:** add `OlioContextConfiguration.requireRealms` (default `true`) and gate the error
  accounting/log level at `:436`/`:391` on it. Additive, no behaviour change for existing contexts.
- **A real authorization gap.** `initialized = true` is set at `:379`, but
  `configureWorldAuthorization(universe,false)`/`(world,true)` run at `:394-395` — *after* it — and any
  exception there is swallowed at `:403-406`. **A context can therefore report itself initialized with
  no grants applied**, and the acting user's later writes get denied in a way that looks exactly like a
  PBAC bug. PB2 must not inherit this: `PbOlioContextUtil.getCreateBookContext` verifies grants after
  `initialize()` (assert the acting user is a member of the book's Writer role, and probe one
  authorized create) and throws rather than returning a half-built context. **This is the single most
  likely source of future "the PBAC is broken" reports and is being designed out deliberately.**

### 3.4 `OlioContextConfiguration` changes

**Corrected in design review round 1 — `requireRealms` alone is not enough.** `OlioContext.java:394-395`
unconditionally calls the **2-arg** `configureWorldAuthorization`, which resolves the org-wide
`~/Roles/Olio Admin` / `~/Roles/Olio User` by `makePath` at `:163-164`. §5.3's isolation argument
requires those calls **not** to fire for book worlds, and an "additive only: `requireRealms`" config
gives `initialize()` no way to select the role-parameterised overload. **§5.7 property 1 is
unimplementable without this.** So the config also gains:

- `BaseRecord authorizationUserRole` / `authorizationAdminRole` (nullable). When both are set,
  `initialize()` passes them to the new 4-arg `configureWorldAuthorization`; when null it uses today's
  org-wide pair, so grid/arena/agent behaviour is unchanged.
- `boolean enrolActingUser = true`. When false, `configureEnvironment` skips the unconditional
  enrolment at `:267-273`/`:282`, so PB2's explicit `POST /members` (§5.4) is the only way in.

**Related overstatement, corrected:** "the org-wide `Olio User` is not used by PB2" is not achievable.
`configureEnvironment` is the first statement of `initialize()` (`:308`), and its first-run branch grants
that role **Read** on `/Olio`, `/Olio/Universes` and the `Worlds` container (`:299`). Grants do **not**
recurse (see the correction in Blocker 3), so this is bounded to those three container groups — but it
means **book existence stays org-wide discoverable** even though book *content* does not. State that
honestly rather than claiming the role is unused. If enumerating book names is itself sensitive, the
`Worlds` container grant needs revisiting — flagged as §10 Q14.

Otherwise additive: `boolean requireRealms = true`. No ctor change (the 9-arg ctor's arg order already
differs from field order). A static factory in the new `PbOlioContextUtil`:
`newBookConfiguration(user, dataPath, bookSlug)` → `universeName="Books"`, `worldName=bookSlug`,
`features=new String[0]`, `requireRealms=false`, `resetWorld=false`, `resetUniverse=false`,
`contextRules=[BookWorldInitializationRule, GenericItemDataLoadRule]`, no evolution/state rules,
`basePath="/Olio"`.

### 3.5 Chapters

Both mechanisms are explicit, authorized write operations in a new `PbSharingUtil` (Objects7). Neither
is ever reachable from a read.

**Share up to universe** — `promoteToUniverse(user, book, record)`: requires `Books Writer` →
`OlioUtil.cloneIntoGroup(record, universeGroupForModel)` (the existing utility, already used at
`GridSquareLocationInitializationRule.java:87`) → `RecordUtil.updateRecords` → record a binding with
`role="promotedFrom"` pointing at the book-world original, so the workflow view shows the lineage. The
original is left intact.

**Copy to adjacent world (next chapter)** — `copyToChapter(user, fromBook, toBook, records)`: requires
Writer on **both** books → for each `olio.charPerson`, clone into the target world's `Population`, and
clone each foreign sub-record into the target world's corresponding group with
`OlioUtil.cloneIntoGroup`; for the narrative use
`NarrativeUtil.getCreateNarrative(ctx, population, setting)` (`NarrativeUtil.java:1063-1086`), which
already creates into `ctx.getWorld().get("narratives.path")` as `ctx.getOlioUser()` (`:1103`) —
**exactly the canonical utility Stephen named in KI-60 as what `createPersistedForeignInstance` should
have been.** PB2 adopts it rather than repairing the hand-rolled path. Record a binding per copied
record with `role="chapterSource"`.

**Copy, not reference, is the proposed default**, so chapter 2 can age/redress a character without
mutating chapter 1 — precisely the `age != 15` re-imprint pattern the reference test hand-rolls
(`TestPictureBookCustom.java:1027-1044`). Note the copy **must** carry the seven foreign sub-records:
`deleteGroupRecursive`'s comment (`PictureBookUtil.java:4243-4245`) explicitly relies on sub-records
being "created fresh, once, per character — never called with a shared/reused instance", so sharing
them across chapters would make a chapter-1 delete destroy chapter-2 data. §9 Q7 asks.

---

## 4. The three blockers

### Blocker 1 — `fastDataCheck` probes locations

`WorldUtil.fastDataCheck` (`:107-112`) counts `data.geoLocation` rows in the world's `Locations` group;
the gate is at `:199-201`. A location-free `Books` universe returns false forever, so all eight loaders
re-run on every init (each self-guards on a count — ~8 count queries per book open; cheap but not free).

**Response (corrected in design review round 1).** An earlier draft recommended probing
`colors + surnames`. **That is wrong and would have silently left the Books universe's `Traits` group
empty.** `Colors` and `Surnames` are both in `WorldUtil.SHARED_LIBRARY_NAMES` (`:38`) and are repointed
to `/Library/{name}` (`:66-74`), which is **one group per organization** (`LibraryUtil.java:22,32-43`).
So if any pre-existing universe in the org has populated them, a brand-new `Books` universe returns
fast-path **true on its very first init** and `loadWorldData` returns at `:199-201` — before `loadTraits`
at `:215`. `Traits` is **not** a shared library, so it would stay empty and `Decks.getRandomTraits`
(`Decks.java:156`) would yield nothing. The plan's own §9 assertion `count(data.trait) > 0` would fail.
And `fastDataCheck` defaults to `true` (`OlioContextConfiguration.java:30`), so this fires by default.

**The probe must use a universe-local corpus.** `Traits` is the natural choice: it is loaded on every
universe (`:174-183`), is not a shared library, and is the one corpus whose `reset` flag is the raw
`reset` rather than the effectively-never `(useSharedLibrary == false && reset)`. Keep the locations
check as an **additional** condition only when `features.length > 0`. Better still, make the probe
per-corpus (each loader already self-guards on its own count) so the fast path can never skip a corpus
that is genuinely absent — at which point `fastDataCheck` becomes an optimisation with no correctness
role at all, which is what it should have been. *(§10 Q2 — this changes existing behaviour, so it is
Stephen's call which of the two.)*

### Blocker 2 — `OlioContextUtil` cache

`getOlioContext` (`OlioContextUtil.java:30-40`) keys on `user.get(FIELD_NAME)` alone and hardcodes
`getGridContext(user, dataPath, "My Grid Universe", "My Grid World", false)` at `:35`, in a plain
`HashMap` at `:26`.

Three distinct defects, and the first is live today independent of PB2:

1. **Cross-organization collision.** `system.user`'s uniqueness constraint is `"name, organizationId"`
   (`models/system/userModel.json:3`), but the cache key omits `organizationId`. User `steve` in org A
   and `steve` in org B share one `OlioContext` — holding org A's `olioUser`, `universe`, `world`,
   `adminRole`, `userRole`. Org B's request then reads and **writes into org A's world groups as org
   A's `olioUser`**. This carries a *principal* across tenants, not just a value.
2. **One context per user, ever.** No universe/world component in the key ⇒ one-world-per-book is
   unreachable through this API. Worse: after opening book A, a request for book B returns A's context,
   so `ApparelUtil.constructApparel` writes B's apparel into **A's** groups and
   `SDUtil.resolveCharacterImagePath` writes B's portraits into **A's** gallery — silently, no error.
3. **Not thread-safe, unbounded.** Plain `HashMap` in a servlet container; contexts hold
   `populationMap`/`demographicMap`/`realms` (`OlioContext.java:62-65`) forever.

### RATIFIED DIRECTION (Stephen, 2026-08-11) — universe/world travels with the call

> *"I think we'll need to pass in Universe/World Ids to each context, or default to the initial one,
> which should fix the cache issue, and also mean Ux and services need to understand and work with those
> contexts — and that'll fix the issue of a user switching between worlds/books."*

This is broader than the additive-overload scope drafted below, and it is the better shape. It makes the
context **identified by the caller** rather than inferred, which fixes three things at once by
construction rather than by cache hygiene:

1. **The cache defect disappears** — the key is `organizationId` + the universe/world ids the caller
   named, so there is nothing to collide.
2. **World switching becomes correct by definition** — today's "request for book B returns book A's
   context" (Blocker 2 defect #2) cannot happen if B's id is in the request.
3. **Ux and services become world-aware**, which is the actual product requirement behind chapters and
   the §6b canvas: a user moving between books/chapters is switching context explicitly.

Design consequences to carry through the whole plan:

- **Wire contract:** `universeObjectId` / `worldObjectId` (UUIDs, consistent with the rest of the REST
  surface) as **optional** parameters on every endpoint that constructs an Olio context. Absent ⇒
  default to the initial/current pair, so all ~15 `GameService` call sites, `OlioService`,
  `GameStreamHandler.java:109`, `OlioAction.java:297`, `PatchAction.java:31` and the existing Ux keep
  working untouched. Ids, not names — names are exactly what is hardcoded today
  (`OlioContextUtil.java:35`).
- **The dead hook becomes live:** `chatConfigModel.json:132-141` already declares `universeName` /
  `worldName`, read by no Java code. This is where a chat session records which book world it belongs
  to. Consider adding id fields alongside the existing name fields rather than repurposing them.
- **Ux state:** a current-universe/world selection that survives navigation, sent on every Olio call.
  The Ux already hardcodes `/Olio/Universes/My Grid Universe/Worlds/My Grid World`
  (`games/wordGame.js:16`) and browses `/Olio/Universes` (`tree.js:329-332`, `list.js:535`) — those
  become the default selection rather than a literal.
- **Scope note:** this touches Service7 signatures and Ux752, so it is **larger than phase 1** as
  drafted. Recommend phase 1 lands the Objects7 side (parameterised resolution + keyed cache, defaulting
  to the current pair), and a new **phase 1b** threads the optional ids through Service7 + Ux752. That
  keeps phase 1's blast radius reviewable while committing to the ratified shape. Q8's "one Books
  universe per org" answer feeds directly into what the default resolves to.

**Response** — the mechanics, now driven by the ratified direction above:

1. New overload `getOlioContext(user, dataPath, universeId, worldId, rules)`, with the name-based and
   2-arg forms delegating to it (the 2-arg form resolving the default pair).
2. Cache key = `organizationId + "/" + userName + "/" + universeId + "/" + worldId`. **orgId is
   mandatory.**
3. `ConcurrentHashMap` + per-key lock objects, double-checked around the slow `initialize()` — **not**
   `computeIfAbsent`, which would hold a bin lock across heavy re-entrant IO.
4. **Bounded** LRU (32) with eviction logged, plus
   `evict(orgId, userName, universeName, worldName)` wired into `CacheService` next to the existing
   `OlioUtil.clearCache()` at `CacheService.java:68`, called on book delete/reset.
5. Keep the 2-arg `getOlioContext(user, dataPath)` delegating to the grid pair.

**Also in this file, and not optional: `AccessPoint.setPermitBulkContainerApproval(true)`.**
`OlioContextUtil.java:43` sets it and `:83` resets it (same at `:91`/`:119` for arena). `IOContext`
holds exactly one `AccessPoint` (`IOContext.java:52,85,119-120`) and the flag is a **non-`volatile`
instance field** (`AccessPoint.java:45`). While it is on, **every other request thread in the JVM**
doing a batch write gets container-level-only authorization — `AccessPoint.update` checks once per
container and reuses that decision for the rest of the batch (`:205-227`, with the risk noted in its
own comment at `:177-179`). Under PB2, context init happens on demand from REST, so this flag would
flip constantly, process-wide, driven by unrelated users. **Response:** never use the setter; pass the
existing `AccessPoint.update(user, objects, permitBulkApproval)` parameter (`AccessPoint.java:163`).

### Blocker 3 — `configureWorldAuthorization` + the single org-wide role

Current behaviour (`OlioContext.java:146-204`): resolves exactly one `~/Roles/Olio User` and one
`~/Roles/Olio Admin` per organization (`:163-164`, `:248`, `:278-279` — the paths are literal
constants with no world component, and `PathUtil.java:72-79` expands `~` to `olioUser`'s home, one per
org). It then collects the world model's foreign group fields whose group carries `shared=true` (only
the `/Library/*` libraries) and grants on the children of a group it resolves by parent walk.

**Finding — the group resolution is nondeterministic.** `:188-189` builds
`QueryUtil.createQuery(MODEL_GROUP, FIELD_PARENT_ID, cfgWorld.get(FIELD_GROUP_ID), orgId)` and calls
`findRecord` — **singular, filtered only by `parentId`, no name condition**, and `findRecord` is
first-row-wins on an unsorted query (`SearchBase.java:52-71`). A world record lives in the `Worlds`
group, so `pdir` is *whichever child of `Worlds` Postgres returns first* — potentially a different
book's container. `:193-196` then enumerates that container's 36 groups and grants the org-wide role
CRUD at `:199-202`. The same applies to the universe call, where the parent is `/Olio/Universes` and
the environment already contains `My Grid Universe`, `Phase2 Universe`, `Memory Duel Universe`,
`Phase3 Universe` (`TestMemoryPhase2.java:58`, `TestMemoryDuel.java:88`, `TestKeyframeMemory.java:74`).
**So today, initialising any Olio context on a multi-universe DB can grant the org-wide Olio role
read/CRUD on an unrelated universe's or world's groups.** Live now, independent of PB2. Consequences
under one-world-per-book: cross-book exposure *and* possible silent self-lockout (book B's own groups
may receive no grant), which the swallow-all `catch` at `:403-406` hides until a later PBAC denial.

Note also that the world call passes `userWrite=true`, so the shared `/Library/*` groups get
`{Read,Update,Create,Delete}` for the org-wide role, while `LibraryUtil` itself grants
`ROLE_ACCOUNT_USERS` only `{Create,Read,Update}` (`LibraryUtil.java:25`). **Olio silently adds Delete
on the org's shared corpora to every Olio user**, and under PB2 that would re-fire on every book
creation.

**Response:**

- New overload `configureWorldAuthorization(cfgWorld, userRole, adminRole, boolean userWrite)` — roles
  are **parameters**, not the org-wide singletons.
- Target group set resolved **deterministically**: iterate the world record's own 36 foreign group
  fields plus the container resolved **by name** via `pathUtil.findPath(olioUser, MODEL_GROUP,
  config.getWorldPath() + "/" + worldName, DATA, orgId)`. Never a `parentId`-only `findRecord`.
- Keep the existing 2-arg signature delegating to the new one, so grid/arena behaviour is identical
  *except* the `findRecord` → name-resolution fix. **Recommend raising the nondeterminism as its own
  KI** (§9 Q3).
- **Group entitlements do NOT recurse — settled in design review round 1.** An earlier draft left this
  as an open question on the strength of `common.groupExt.groupId`'s `"recursive": true`. That flag has
  exactly **one** consumer in the module — `PolicyUtil.java:255`, where it only decides whether a field
  is included in the read-policy scan. It does not make group entitlements inherit down the tree. The
  §5.3 evidence is the operative one: `effectiveGroupObjectEntitlementTemplate.sql:10` joins
  `GO.groupId = ER.id`, an exact match; only *role* hierarchy recurses (`effectiveRoleTemplate.sql:5`,
  `roles_to_leaf`). **So every group that needs access must be granted explicitly**, and `scanNestedGroups`
  (or an equivalent) is **required**, not belt-and-braces — sub-subgroups such as `Gallery/Characters`
  (created by `SDUtil.resolveCharacterImagePath`) would otherwise be unreachable.
  Use it only for the book world container, and note it grants as `olioUser` (`:217-218`) where
  `configureWorldAuthorization` uses the org admin (`:200-201`) — two granting paths, two principals;
  pick one and use it consistently. Also note `:170-172` throws if any world foreign group field is
  null, which the new overload must handle.
- **Ordering:** grants must run **after** group creation — `configureLibraryRootPermissions` bails at
  `LibraryUtil.java:90-94` when the target group doesn't exist, and `ChatLibraryUtil.java:47-48,52-53,
  57-58,62-63` plus `PolicyUtil.java:1067-1068` are five live instances of that silent no-op.
  `MemberUtil.member(…, true)` is idempotent, so grant-after-create is always safe.

### Blocker adjacent — process-global caches keyed to nothing

**PB2 is what introduces the multi-universe-per-process condition** (a JVM serving both `Books` and
`My Grid Universe`), so these move from latent to active and must be fixed in phase 1:

- `Decks.java:25-36` — `traitDeck`, `patternDeck`, `colorDeck`, `maleNamesDeck`, `femaleNamesDeck`,
  `surnameNamesDeck`, `occupationsDeck`: all `static`, keyed to no world. `getRandomPattern`/
  `getRandomColor`/`getRandomTraits` (`:60-79`, `:156-171`) lazily fill from whichever `world` arrived
  first, then serve that content to every caller regardless of world **or organization** — so book B's
  characters get book A's palette, and across orgs it leaks `data.color`/`data.trait` records by
  reference, then persists them as FKs. Fix: key by basis-world objectId in a `ConcurrentHashMap`, plus
  `Decks.clear(worldObjectId)`.
- `ColorUtil.java:41-42` — `colorComplements`, `defaultColorMap`, `static HashMap`, carrying the
  author's own `// TODO - these hashes need to be replaced` (`:40`). `findComplementaryColor`
  (`:161-166`) takes a `world` and ignores it for cache purposes.
- `OlioUtil.java:52` — `dirNameCache`, keyed `model + "-" + groupId` (so not cross-world, good) but
  unsynchronized, mutated in place at `:138`, unbounded.

This is the same class of defect `architecture.md` documents for `IOContext`'s `vectorUtil`/`voiceUtil`.

---

## 5. Security & PBAC design

### 5.1 The finding that dominates everything else

**Today, any user holding `@RolesAllowed("user")` self-enrols into the org-wide `~/Roles/Olio User`
role by making one ordinary REST call — and that role carries CRUD on world groups.**

`configureEnvironment` enrols the acting user unconditionally on **both** paths: first-run at `:282`
(`member(olioUser, userRole, config.getUser(), null, true)`) and every subsequent run at `:267-273`
(the KI-35 fix). `configureEnvironment` is the first statement of `initialize()` (`:308`), reached from
`OlioContextUtil.getOlioContext` → `getGridContext` (`:35,41,80`), which is called from **every**
`GameService` endpoint (18 call sites), `OlioService.java:76,202,319,376,393`,
`GameStreamHandler.java:109`, and `PictureBookUtil.java:2372`.

With one shared simulation world that is arguably intended. **Under PB2, where book content *is* world
group content, it means every user of the app gets CRUD on every book unless per-book roles land
first.** This is why §5.3's role model is a prerequisite, not a refinement.

### 5.2 Ownership matrix

| Records | Owner | Group | Why |
|---|---|---|---|
| `olio.world` (universe + book worlds), all 36 world groups, the 3 PB groups | **olioUser** | — | As today: `WorldUtil.getCreateWorld(olioUser, …)` |
| `/Library/*` shared libraries | **org admin** | `/Library` | `LibraryUtil` unchanged; `shared=true`; CRU to `ROLE_ACCOUNT_USERS` (`LibraryUtil.java:22,45,75-76`) |
| Static corpora (`data.word`, `data.color`, `data.trait`, `data.censusWord`, `data.wordNet`, patterns) | **olioUser** | universe / `/Library` | Loaded by `loadWorldData` with `ctx.getOlioUser()`; unchanged |
| apparel / wearables / qualities | **olioUser** | book world's `Apparel`/`Wearables`/`Qualities` | Already deliberate (`PictureBookUtil.java:2806,:2851-2855`) so complementary-colour computation reaches the shared colour library |
| `olio.charPerson` + statistics/instinct/personality/state/store/profile/narrative | **olioUser** ← *change from PB1* | book world's `Population`/`Statistics`/… | Puts character and apparel under one owner — what KI-35's own comment (`OlioContext.java:250-266`) says the apparel path assumes; makes chapter copy/promote same-owner; removes the user's home group entirely |
| `data.data` image bytes | **olioUser** | `{world}/Gallery`, `{world}/Gallery/Characters` | Exactly where `SDUtil.resolveCharacterImagePath` already writes as olioUser (`:359-380`) |
| `olio.pb.book`, `olio.pb.run` | **acting user** | `{world}/Book`, `{world}/Workflow` | Human-intent records; `ownerId` should name the human |
| `olio.pb.workflow`, `.node`, `.binding`, `.artifact`, `.scene` | **olioUser** *(proposed)* | `{world}/Workflow`, `{world}/Artifacts`, `{world}/Book` | Machine-generated pipeline state. Uniform ownership avoids an owner-A node updated by user B on group grants alone, and avoids the KI-28 class of trap. Authorship preserved by `createdByObjectId`. **§9 Q1 — must be settled before phase 2; it sets the grant set.** |

**Consequences of flipping characters to olioUser — each needs a test, not an assumption:**

1. **`ownerId` stops identifying a human.** Everything reading it as "the requesting user" changes
   meaning: `ApparelUtil.java:522,768`, `CharacterUtil.java:303-309`. Combined with
   `OlioUtil.FULL_PLAN_FILTER` excluding `ownerId` from projections (`OlioUtil.java:645`), this gives
   the KI-28 unboxing NPE a second trigger.
2. **Owner-scoped queries stop scoping.** `ChatUtil.java:1545-1553` filters `ownerId = user.id` for
   name lookups and explicitly documents that objectId lookups "rely on PBAC via `AccessPoint.find()`
   for authorization instead" (`:1549-1550`); same at `ChatAutotuner.java:82`. If PBAC read becomes
   "anyone in the org-wide Olio User role", that stated fallback stops being a boundary.
   `PictureBookUtil.findBookGroup` (`:336-341`) is exactly this shape.
3. **`reset` becomes a cross-user destructive primitive.** `reset` (`:4159-4201`) →
   `deleteGroupRecursive` (`:4212-4289`) enumerates children with the **unauthorized**
   `getSearch().findRecords` (`:4220,4228,4255,4281`) and deletes each via `AccessPoint.delete(user,…)`.
   The PBAC gate is per-record delete — which the org-wide role *satisfies*, because
   `configureWorldAuthorization(world, true)` grants Delete.
4. **Never wire `resetWorld` to REST.** If "delete a book" were implemented as `resetWorld=true`
   (`OlioContext.java:334-339` → `WorldUtil.cleanupWorld`), that path is `cleanupLocation` →
   `IOSystem.getActiveContext().getWriter().delete(lq)` (`WorldUtil.java:283-298`) — a **raw bulk
   delete that bypasses PBAC entirely**, across ~30 groups (`:232-273`), as olioUser, authorized by
   nothing but "somebody asked for a context." Book deletion goes exclusively through per-record
   `AccessPoint.delete`.

**The 2026-08-10 revert becomes re-attemptable.** `SDUtil.resolveCharacterImagePath` was reverted from
a book-scoped path because olioUser has no create rights in the acting user's home; its own comment
(`:370-372`) states the rule PB2 must obey — *"I changed the location without changing the principal."*
Inside the book world olioUser owns everything, so the book-scoped variant works — but that is a
**dependent follow-up after phase 3** with its own test, and note the *within-book same-name* collision
(KI-34) remains even though the cross-book one disappears.

**All `olio.charPerson` updates stay PATCH-shaped** via the existing `patchCharPersonField`
(`PictureBookUtil.java:2318-2327`). `identity.person.users` is a foreign list to the groupless
`system.user`, and per `model-api.md` a full `planMost` graph update would demand role grants on that
foreign field and silently drop the change otherwise.

### 5.3 Role model

Four roles, all `RoleEnumType.USER`. Entitlements do **not** inherit down the group tree —
`effectiveGroupObjectEntitlementTemplate.sql:10` joins on an exact `groupId` match; only role hierarchy
is walked recursively (`effectiveRoleTemplate.sql:5`). So isolation must be built from explicit
per-group grants to a per-book principal; granting a parent does not work.

| Role | Path | Grants |
|---|---|---|
| Books Reader | `~/Roles/Olio/Books/Reader` | **Read** on `/Olio`, `/Olio/Universes`, `/Olio/Universes/Books` and its 36 groups (corpora, apparel templates, colours) |
| Books Writer | `~/Roles/Olio/Books/Writer` | Read as above **+ Create/Update** on the universe groups that legitimately accumulate shared data: `Apparel`, `Wearables`, `Qualities`, and (only if promote-to-universe is used) `Population`, `Narratives`, `Profiles`. **No Delete anywhere in the universe.** |
| `{bookSlug}` Reader | `~/Roles/Olio/Books/{bookSlug}/Reader` | **Read** on the book world's 36 + 3 groups |
| `{bookSlug}` Writer | `~/Roles/Olio/Books/{bookSlug}/Writer` | Read/Create/Update on the book world's 36 + 3 groups; **Delete only within the book world** (needed by `reset()`) |

**Membership rule (Stephen's stated target):** a user must hold **`{bookSlug}` Reader or Writer** *and*
**Books Reader or Writer**. The universe role alone gives corpora access and nothing else; the book role
alone is useless because apparel templates and colours live in the universe.

#### RATIFIED (Stephen, 2026-08-11) — role hierarchy is the inheritance mechanism; listing universes is fine

> *"`~/Roles/Olio User` — It should get read access to the top to list universes — then role inheritance
> kicks in, or the book-universe role is assigned. We'll need to test this. Right now it's a lower
> priority."*

This settles **§10 Q14**: book *existence* being org-wide discoverable is **accepted and intended** —
`Olio User` holding Read on `/Olio` and `/Olio/Universes` is what makes the universe list renderable at
all. Only book *content* needs per-book isolation, which is what §5.7's four properties protect.

It also supplies the mechanism the architect's review said was missing. Group entitlements do **not**
recurse (exact `groupId` match, `effectiveGroupObjectEntitlementTemplate.sql:10`), but **role hierarchy
does** — `effectiveRoleTemplate.sql:5`'s `roles_to_leaf` recursive CTE, and `auth.role` carries
`common.parent`. So the role tree, not the group tree, is where inheritance comes from:

```
~/Roles/Olio User                      <- Read on /Olio, /Olio/Universes  (lists universes)
    ~/Roles/Olio/Books/Reader          <- Read on the Books universe's corpora groups
        ~/Roles/Olio/Books/{slug}/Reader
    ~/Roles/Olio/Books/Writer
        ~/Roles/Olio/Books/{slug}/Writer
```

**This materially improves §10 Q10 (scale).** If a leaf role inherits its ancestors' entitlements, each
book's roles only need grants on **that book's own 39 groups** — the universe-level corpora reads come
from an ancestor, granted once per org instead of once per book. That removes the duplicated
universe-grant work from every book creation, and it is the answer to Q10 that the (refuted)
group-recursion fallback was supposed to provide.

**Two things must be verified before the tree above is built — Stephen flagged this himself:**

1. **The direction of inheritance.** `roles_to_leaf` is a recursive CTE over the role hierarchy, but
   whether membership in a **child** role confers the **parent's** entitlements (or the reverse) is not
   something to assume — invert it and either every Olio user gets every book, or the nesting does
   nothing. **A phase-1 test settles it in one run:** grant a permission on a group to a parent role,
   enrol a user in a child role only, and assert whether `AccessPoint` permits. Design the tree to
   whichever direction the code actually implements.
2. **Whether inherited entitlements are ever *widening*.** If `{slug}/Writer` inherits from
   `Books/Writer`, confirm a book-scoped member cannot thereby acquire universe **Create/Update** beyond
   the `Apparel`/`Wearables`/`Qualities` set §5.3 allows.

**Priority: lower, per Stephen.** So phase 1 should ship the per-book roles with **explicit grants on
both levels** (correct but redundant), and the hierarchy optimisation lands only after test 1 above
establishes the direction. That ordering means an inheritance surprise degrades performance, never
isolation — the opposite trade to guessing now.

The org-wide `~/Roles/Olio User`/`Olio Admin` are **left untouched for grid/arena** and **are not used
by PB2**. That is the whole point: with N book worlds under one universe, granting the shared role is
granting every book to every Olio user.

Also: **no role should receive Delete on `/Library/*`** (see Blocker 3).

**Grant/enrol order, and it matters:**

1. `WorldUtil.getCreateWorld(olioUser, universe, worldPath, bookSlug, new String[0])` → 36 groups exist
2. `BookWorldInitializationRule.generate` → `Book`, `Workflow`, `Artifacts` exist
3. `makePath(olioUser, MODEL_ROLE, "~/Roles/Olio/Books/{bookSlug}/Writer", USER, orgId)` (and Reader;
   and the two universe roles if absent). The role must exist before `setEntitlement` —
   `AuthorizationUtil.java:80-98` silently skips when the permission `findPath` returns null (`:91-93`)
   and `member(...)` failures are only logged when trace is on (`:87-89`). **Add a hard failure, not a
   log line.**
4. `configureWorldAuthorization(universe, booksReader, booksWriter, false)` then
   `(world, bookReader, bookWriter, true)` — **after** 1-2. Universe before world, preserving today's
   order at `:394-395`, because colour/trait resolution reads the universe (`ColorUtil.java:61-67`,
   `Decks.java:101-106`).
5. `scanNestedGroups(bookWorldContainer, true)` only if phase 1's recursion probe says it's needed.
6. Enrol the **creator** in `{bookSlug} Writer` + `Books Writer`.
7. **Verify**: assert membership and probe one authorized create; throw on failure (§3.3's gap).

Grants must be an **idempotent step independent of generation success**. Today they are the last thing
`initialize()` does, after eight generation stages, inside a swallow-all catch — so any generation
failure yields a book with 36 groups and zero grants. And re-init cannot repair it:
`configureEnvironment` early-returns at `:248-275` once `~/Roles/Olio Admin` exists, and the world-group
grants are not on that path.

### 5.4 Enrolment is an explicit authorization surface

Given §5.1, the current default is unacceptable for PB2: it would mean "any authenticated user calls
`GET /book` → gets enrolled → can read every book". So:

- **Only** `POST /olio/picture-book/create` enrols (the creator, as Writer).
- Any other membership change goes through a new `POST /olio/picture-book/{book}/members`, authorized
  by `{bookSlug} Writer` **or** `~/Roles/Olio Admin`, and audited.
- **Nothing on a read path enrols anyone, ever.**

`enroleReader`/`enroleAdmin` (`OlioContext.java:128-144`) are `public`, take an arbitrary user, and
contain **no authorization check** — the membership write runs as `olioUser` (`:137`). No REST endpoint
calls them today; PB2 must not add one that isn't gated. §9 Q6 asks Stephen to ratify who may add
members.

#### RATIFIED (Stephen, 2026-08-11) — remove the automatic enrolment; register explicitly

> *"`configureWorldAuthorization` just emits the groups and sets up the world role entitlements — but now
> I see the problem: the context user passed w/ OlioContext is always given read access to every world,
> and since the roles are hidden under Olio User it's not easy to manually set. So I think that line needs
> to be taken out and fix the initialization to register a user as a user or admin — since access request
> system is still in pieces we'll just have to note that needs to be completed to lock down sharing."*

This ratifies §5.1's finding as a defect to fix in Olio itself, not merely something PB2 routes around.
**Both enrolment sites go:**

- `OlioContext.java:270-273` — `enrole(config.getUser(), userRole)` on the early-return path, i.e. every
  subsequent init. This is the KI-35 fix (its rationale is the comment at `:250-266`).
- `OlioContext.java:282` — `member(olioUser, userRole, config.getUser(), null, true)` on the first-run
  provisioning path.

Replace with an **explicit registration API** — `OlioContext.registerUser(BaseRecord actor, BaseRecord
user, boolean asAdmin)`, or equivalently the `enrolActingUser` config field in §3.4 defaulting to
**false** — so enrolment becomes a deliberate act with an identifiable caller, never a side effect of
constructing a context.

**Three consequences to handle honestly, not gloss over:**

1. **This will regress KI-35 for anyone not explicitly registered.** That is the *point* — it is the
   security fix — but it is a live behaviour change for the existing game/arena flows: `ApparelUtil`
   creates apparel/wearables/qualities as `olioUser` while the character and store belong to the acting
   user, so an unregistered user's dress-up/down write gets refused, `inuse` stays true forever, and
   `describeOutfit` over-reports. Exactly the symptom the `:250-266` comment describes. **So removing the
   enrolment requires adding an explicit registration call to whatever provisions game access**, or the
   game breaks. Sequence those together; do not ship the removal alone.
2. **The roles should move somewhere manageable.** `~/Roles/Olio User` resolves under **olioUser's** home
   (`PathUtil.java:72-79` expands `~` to the owner's home), which is why membership "isn't easy to
   manually set" — the roles are buried in a synthetic user's tree rather than anywhere an
   administrator browses. Relocating them to an org-level path (e.g. `/Roles/Olio/...`) would make them
   manageable through the role-membership UI that already exists — the member picker and member list
   built for KI-1/KI-2/KI-3. **This is the concrete fix for the "not easy to manually set" half of the
   problem**, and it is a prerequisite for per-book roles being administrable at all, since PB2 adds two
   roles per book. Note it changes existing role paths, so it needs a migration and is its own change.
3. **`configureWorldAuthorization` is not the culprit and should not be gutted.** Stephen's reading is
   right: it only resolves the group set and sets role entitlements (`:146-204`). The over-broad access
   comes from *who ends up in the role*, not from what the role is granted. So the fixes are independent:
   remove the auto-enrolment (this section) and make the grant targeting deterministic and
   role-parameterised (§4 Blocker 3). Neither substitutes for the other.

#### Dependency: the access-request subsystem must be completed to lock down sharing

Stephen's note, recorded as a real dependency rather than an aside. Current state, verified:

| Piece | State |
|---|---|
| Models — `access.accessRequest`, `approver`, `baseAccess`, `request`, `requester`, `submitter` | present (`models/access/`) |
| `AccessRequestFactory` | present |
| Policy operations — `AccessApprovalOperation`, `LookupApproverOperation`, `LookupOwnerOperation` | present |
| `AccessRequestService` (REST) | present |
| Ux752 UI | **absent** — only generic `am7client.js`/`modelDef.js`/`pageClient.js` references; no dedicated request/approval view |

So the backend scaffolding for request-and-approve exists and the user-facing flow does not. **What that
means for PB2:** §5.4's `POST /{book}/members` is the interim mechanism — a book Writer adds members
directly. The intended end state is that a user *requests* access to a book and an approver grants it,
which is what `access.accessRequest`'s own model-level roles already describe (`create: Requesters`,
`update: RequestUpdaters/RequestAdministrators`, `approvalStatus` gated by `Approvers`). Until that flow
is completed, **book sharing is add-by-writer only, with no request/approval trail** — which is
acceptable for now but should be stated in the feature's own docs rather than discovered later. Tracked
as **§10 Q18**.

### 5.5 Read-paths-that-create audit

| # | Path | Risk | Response |
|---|---|---|---|
| 1 | `WorldUtil.getCreateWorld` → `LibraryUtil.getCreateSharedLibrary(user, name, true)` ×7 (`WorldUtil.java:66-74`, inside the `rec == null` branch) → find probe as org admin at `LibraryUtil.java:39`, **early return before any permission configuration** at `:40-42`, `makePath(adminUser,…)` at `:43`, raw `createRecord` **PBAC bypass** at `:45`, grants at `:49` | Any PB code reaching `getOlioContext` triggers admin-privileged writes. Under PB2 that includes **three GET endpoints** — `/{book}/scenes` (`PictureBookService.java:517`), `/characters` (`:538`), `/settings` (`:591`) — because the world record carries the group paths | **The D1 split** — see the corrected design below. Exactly three operations may call `getCreateBookContext`: `POST create`, `POST reset`, `POST chapter`. |
| 2 | `PictureBookUtil.ensureBookGroup` (`:322-331`), `ensureSubGroup` (`:369-379`) | Home-group creation on read | Deleted in PB2; world groups replace them; reads use `findPath` |
| 3 | `OlioContext.configureEnvironment` (`:230-301`) — creates roles/groups **and enrols the acting user** | A read that constructs a context mutates authorization | Unreachable from reads once #1 holds; enrolment moves to §5.4 |
| 4 | `prepareForeignSubModelGroups` (`:2161-2178`) + `createPersistedForeignInstance` (`:2187-2204`) against `~/Narratives`, `~/Profiles`, `~/Statistics`, `~/Store`, `~/Instincts`, `~/Personalities`, `~/States` | **The KI-60 collision target** | Both deleted in phase 3; destinations become world groups; narratives via `NarrativeUtil.getCreateNarrative` |
| 5 | `ApparelUtil.getApparelTemplate` writes templates into the universe (`:198-214`) via `BuilderUtil.loadBuilders` in `GenericItemDataLoadRule.postgenerate` | A write during init | Acceptable — only reachable from a **create** path once #1 holds. Documented, not hidden. |

Note #1 is *already* live for `GameService`/`OlioService` GETs. PB2 would extend it, not introduce it —
not an excuse, but useful for scoping.

#### The D1 split, corrected (design review round 1)

An earlier draft claimed `WorldUtil.getWorld` is find-only. **It is not.** `WorldUtil.java:41` calls
`pathUtil.makePath(...)`, and `PathUtil.java:62-64` routes the public `makePath` to `doCreate=true`. The
find-only primitive is `findPath` (`PathUtil.java:55-57` → `makePath(..., false)`). So a
`findBookContext` built on `getWorld` would still create the world's group path on a read.

The split therefore needs three concrete pieces, none of which may be assumed:

1. **`WorldUtil.findWorld(user, groupPath, worldName)`** — new, a copy of `getWorld` with `makePath`
   replaced by `findPath`, returning null when the group path does not exist. `getWorld` keeps its
   current behaviour so no existing caller changes.
2. **A find-only context assembly path.** `PbOlioContextUtil.findBookContext` must **not** call
   `OlioContext.initialize()`, because `configureEnvironment` (`:308`) both creates the olio user via
   `getCreateUser(octx.getAdminUser(), …)` (`:244`) and enrols the acting user (`:267-273`). It must
   instead assemble a read-only context from records that already exist: resolve `olioUser` with a
   **find-only** user lookup (not `getCreateUser`), `findWorld` for universe and world, and skip the
   rule pipeline, clock and realms entirely. If any piece is absent → return null → the caller 404s.
   **This is new code, not a reuse**, and it is the single most important thing to get right in
   phase 1: it is what keeps three GET endpoints from performing org-admin writes.
3. **A read-only `OlioContext` variant, or an explicit read-only view.** A partially-populated
   `OlioContext` with a null `clock`/`realms` is a trap for any downstream Olio utility that assumes
   `initialize()` ran. Either give the read path a narrow interface exposing only what PB reads (the
   world's group paths, `olioUser`, the universe), or add an `initialized`/`readOnly` flag that the PB
   read utilities assert on. **Open design point — §10 Q13.**

### 5.6 REST authorization gaps to close

All 16 existing endpoints carry `@RolesAllowed({"admin","user"})` and use
`ServiceUtil.getPrincipalUser(request)`. That is necessary and **nowhere near sufficient** under a
per-world-role design: `"user"` is a coarse container role and cannot express "may act on *this* book."

- **Where the check belongs:** in Objects7, as PBAC data — per-book roles plus per-group entitlements —
  so `AccessPoint` stays the enforcement point and `PictureBookUtil.findBookGroup` (`:336-341`) stays
  the single choke point. **Nothing new goes in Service7.**
- **Scene-addressed endpoints never authorize the owning book.** `generateSceneImage` (`:3277`),
  `regenerateBlurb`/`setSceneStatus` (`:3879`) and `prepare-images` (`:3840`) resolve the scene note by
  objectId and never resolve or authorize its book. With books in a shared world that is a direct
  object reference with no book-level check. Each must resolve the scene's book and re-authorize.
- **`cancel` discards the principal.** `PictureBookService.java:475-476`:
  ```java
  ServiceUtil.getPrincipalUser(request);            // :475 — return value discarded
  SummarizeProgress progress = cancelRegistry.get(key);  // :476
  ```
  The registry is a static process-wide map (`:85`) keyed by a client-supplied path param, so **any
  authenticated user can cancel any other user's in-flight extraction.** Today those ids are hard to
  obtain; under PB2 world browsing makes them discoverable. Key by `(principal, key)` and check
  ownership. *(This is a pre-existing defect worth fixing regardless of PB2.)*

### 5.7 Cross-book & multi-tenant isolation argument

One `Books` universe **per organization**: every group, role and record carries `organizationId`, and
`configureEnvironment` derives it from `config.getUser()`. The context cache key must include orgId or
the process-level cache breaks that boundary (Blocker 2).

Cross-book isolation rests on exactly four properties. **If any one fails, the system collapses to
"every Olio user can read every book":**

1. **Per-book roles** instead of the org-wide `Olio User`.
2. **Deterministic grant targeting** — name-resolved groups, never the `parentId`-only `findRecord`.
3. **No PB record outside its book world's groups** — every create passes an explicit world path; the
   `schema.getGroup()` fallback is banned.
4. **Enrolment is an explicit authorized operation**, never a side effect of a read.

Each gets a **negative test**, not an argument (§8).

---

## 6. Optional ComfyUI backend

**Stephen's requirement:** optionally go straight to a ComfyUI backend instead of SwarmUI, for more
options — especially from a workflow-driven perspective.

**Why this fits PB2 rather than being a side quest.** ComfyUI's native API *is* a node graph: `POST
/prompt` takes `{prompt: {<nodeId>: {class_type, inputs}}, client_id}` where an input can be wired to
another node's output as `[nodeId, outputIndex]`. So PB2's `olio.pb.node` + `olio.pb.binding` graph has
a direct structural analogue, and the same graph that drives PB2's dependency tracking can **compile
to** a Comfy workflow. That is the strongest argument for the DAG model: it stops being an internal
bookkeeping device and becomes the thing actually executed.

**What going direct buys.** SwarmUI is a front end over an embedded ComfyUI, so Swarm's request DTO
(`SWTxt2Img`, 284 lines) is a *parameter* abstraction — a fixed set of knobs. Going direct gives
arbitrary graph topology: multi-stage sampling in one call, LoRA/ControlNet chains, custom nodes,
per-node model swaps, and node-level reuse of intermediate latents. It also gives two things PB2 wants
specifically:

- **`POST /interrupt`** — a real cancellation endpoint. KI-10's cancellation currently stops *our*
  loops between stages (`SummarizeProgress` checked at chunk/scene boundaries) but cannot abort an
  in-flight generation. Comfy can.
- **`WS /ws?clientId=`** — per-node execution progress events (`executing`, `progress`, `executed`).
  These map onto the existing `PictureBookProgressNotifier.notifyProgress` call sites
  (`:3350,3416,3608,3688,3692,3712,3717,3745,3792`) and would upgrade progress from per-stage to
  per-node.

**What it costs.** Swarm's model management, session handling and parameter validation have to be
replaced. A raw workflow couples us to the installed Comfy version and to any custom nodes' exact
`class_type` names and input keys — a much tighter coupling than Swarm's stable parameter surface. That
argues for keeping Swarm as the default and Comfy as an opt-in, not a replacement.

### 6.1 Design

Additive, mirroring the existing Auto1111/Swarm split:

1. **`SDAPIEnumType` gains `COMFY`** (`…/olio/sd/SDAPIEnumType.java`, currently `AUTO1111`/`SWARM`).
   Selection already flows from the `sd.server.apiType` init-param and
   `ServerConfigUtil.getServerUrl(SERVER_SD, …)` (`ChatService.java:1334-1342`,
   `OlioService.java:153-155`), so no new config surface is needed — **but** the server URL is a
   `/System`-global `system.connection` record, deployment-global not per-org, and per
   `architecture.md` must not be pushed into a process-global singleton.
2. **New package `…/olio/sd/comfy/`**, structurally parallel to `…/olio/sd/swarm/`:
   - `ComfyGraph` — the workflow document (ordered map of node id → `{class_type, inputs}`), with
     `addNode`, `wire(fromId, outIdx, toId, inputKey)`, `toJson()`.
   - `ComfyUtil` — graph *builders* mirroring `SWUtil`'s request builders one-for-one:
     `newTxt2ImgGraph`, `newSceneGraph`, `newFlux2SceneGraph`, `newKontextSceneGraph`,
     `newMannequinGraph`. Same inputs, same style seam (`SDUtil.getSDConfigPrompt`) — so prompt
     assembly is **shared**, not forked.
   - `ComfyClient` — `POST /prompt`, poll `GET /history/{prompt_id}`, fetch bytes via
     `GET /view?filename=&subfolder=&type=`, upload inputs via `POST /upload/image`, cancel via
     `POST /interrupt`, and validate against `GET /object_info`.
3. **`SDUtil` branches on `apiType`** at its existing branch points — the six persistence writers
   (`createImage :918`, `createSceneImage :1027`, `createPersonImage :505/:531`,
   `generateLandscapeImage :1100`, `createAnimalImage :1431`, `generateMannequinImages :1803`) keep
   their signatures and their uniform `data.data` persistence shape. Only request construction and
   transport differ. `resolveModel`/`getDefaultModel`/`schemaDefault` (`:1590-1637`) stay the single
   model-resolution seam.
4. **Config.** `olio.sd.config` gains `comfyWorkflowName` (string) and `comfyGraphOverride` (string,
   JSON) — **no schema defaults**, consistent with the `flux2*`/`kontextModel`/`mannequin*` rule, so a
   resource-backed `olio/sd/comfyDefaults.json` (mirroring `Flux2Defaults`) stays live.
5. **Provenance.** `olio.pb.artifact.backend` records which backend ran, and `backendGraph` stores the
   **sanitized** graph JSON (image inputs replaced by artifact objectIds, no session/client id) — the
   Comfy analogue of `generatorRequest`. This makes a PB2 image fully reproducible: graph + seed +
   effective config, all queryable.
6. **Node-type → graph mapping.** `PbNodeTypeEnumType` values map to named graph builders, so a
   `COMPOSITE` node compiles to a Comfy subgraph. A later step (out of scope here) could let a whole
   PB2 scene subgraph compile into **one** Comfy prompt instead of N HTTP calls — that is the real
   payoff and should be designed only after the one-node-one-call version works.

### 6.2 Phasing & verification

Lands as **phase 3b**, after the graph exists (phase 2) and PB is wired to it (phase 3), because the
node taxonomy is what the graph builders map from. Swarm stays the default throughout; Comfy is
selected per-config.

**UNVERIFIED and must be checked against the live instance before implementation:** the exact
`class_type` names and input keys for the installed checkpoints (FLUX.2 / Kontext nodes especially),
whether the local ComfyUI is reachable independently of Swarm's embedded copy, and its version. The
approach must be to **capture a working graph from the ComfyUI UI** (Save/Export API format, or the
network payload) and build the Java builders from that captured JSON — not from assumed node names.
`aiDocs/imageComposite.md:28-30,47` already gives this instruction for Swarm's image-parameter keys;
the same rule applies here, more strongly.

Verification: `TestComfyBackend` — a live test asserting a real image comes back and decodes, plus a
**parity test** that the same node with `backend=SWARM` and `backend=COMFY` and an identical seed
produces two artifacts whose `sdConfigSnapshot` effective values match (proving config resolution is
shared, not forked). Following the KI-48 rule, if the checkpoint is missing the test must **skip
visibly**, never pass.

---

## 6b. Interactive canvas — the pre-production casting & style board

**Stephen's requirement (2026-08-11, with a PAI screenshot):** an interactive UI like PAI's, where
**character images, initial landscapes, and initial or key scenes can be identified and tested**.

### 6b.1 What the reference UI actually does

Read off the screenshot, because these are the design requirements:

- **A freeform spatial canvas** — dotted-grid infinite plane, pan/zoom, cards placed at arbitrary x/y
  with varying sizes and aspect ratios (tall portraits, wide landscapes). Not a grid, not a list.
- **Every card is a typed node** with a type badge (`Image`) and an **addressable handle** —
  `character_@magnus`, `character_@ansgar`, `character_@johan`, `character_@rut`, `character_@lina`,
  `character_@herd`, `image_style_bible`. The `@handle` is the key idea: a stable, human-typed token
  that later prompts and nodes reference. This is what makes the board *compose* rather than just
  display.
- **Entity chips** beneath cards (`Magnus`, `Ansgar`, `Johan`, `Rut`, `Lina`, `Meadow Herd`), colour-coded,
  binding an image to a character — or to a **group** entity (`Meadow Herd` is a herd, not an individual).
- **Multiple candidate variants per handle** — several distinct `character_@johan` cards, several
  `@rut`, several `@lina`. So the board is a **casting call**: generate N, compare side by side, pick
  the keeper.
- **`image_style_bible`** — a single landscape card that establishes the look (here: a stylised goat on
  a mountain ledge) which the character cards visibly share. One canonical style reference that
  everything else consumes.

The workflow this implies is *pre-production*: settle the style, cast the characters, establish the key
landscapes — **and only then** run the book. That is exactly the gap in PB1, where the first time you
see a character is inside a finished composite, and the only remedy is the hand-rolled re-imprint loop
at `TestPictureBookCustom.java:1027-1044`.

### 6b.2 It maps onto the DAG with three additions

Most of this already exists in §2.2. The canvas is a **view over the graph**, not a parallel structure:

| Canvas concept | PB2 model |
|---|---|
| card | `olio.pb.node` + its `current` `olio.pb.artifact` |
| candidate variants | `olio.pb.artifact.revision` / `supersedes` / `current` — already designed |
| the chosen keeper | `artifact.current = true` + `node.pinned` |
| entity chip | `olio.pb.binding` with `refModel`/`refObjectId` → `olio.charPerson` |
| style bible governing everything | a node whose artifact is bound into other nodes with `role="styleRef"` |
| "test" a card | a run scoped to one node (`olio.pb.run.requestedNodeIds`) |

Three genuine additions:

**1. `handle` on `olio.pb.node`** (string, 64, indexed, **unique per book**). The addressable token —
`character_@johan`, `image_style_bible`. Rules: slug-shaped, assigned on creation from the node type +
entity name, user-renameable. This is what lets a prompt template reference `@johan` and have the
executor resolve it to that node's current artifact, creating the binding automatically. **This is the
mechanism that makes the canvas compositional**, and it is worth more than the visual layer.

**2. Canvas geometry on `olio.pb.node`** — `canvasX`, `canvasY`, `canvasW`, `canvasH` (int). Presentation
state, but it belongs on the node: the spatial arrangement *is* the user's mental model and must survive
reload. Nullable, so a node created by the pipeline rather than by hand gets auto-laid-out.

**3. `STYLE_BIBLE` node type**, added to `PbNodeTypeEnumType`, plus a `styleRef` binding role. A style
bible is the natural first candidate for **promote-to-universe** (§3.5): one style shared across every
chapter of a series is precisely a universe-level asset, and this gives that mechanism a concrete first
use rather than a hypothetical one.

Also needed: an **entity-group** notion for `Meadow Herd`. A chip may bind to a collection rather than
one `olio.charPerson`. The cheapest correct answer is a binding whose `refModel` is `auth.group` (the
world's `Population` subgroup) or a `olio.pb.castGroup` model. **§10 Q15 — do not guess.**

### 6b.3 Why this ordering matters for correctness, not just UX

A style bible and cast selected *before* scene generation means the scene nodes' bindings point at
**pinned, approved** artifacts. Then:

- Re-rolling a character portrait supersedes one artifact and marks exactly that character's scenes
  STALE — visible on the canvas as badges, not discovered by re-reading a finished book.
- Changing the style bible marks **everything** downstream STALE. That is correct, dramatic, and
  precisely the "regenerate down dependent branches" behaviour Stephen asked for. It also makes the
  §2.3 `configHash`/`PB_PIPELINE_VERSION` mass-invalidation risk *legible* instead of mysterious.
- KI-53 (three art styles in one composite, because each character's narrative baked in its own random
  style) becomes structurally impossible: style arrives from one bound `styleRef` node, not from N
  per-character `sdPrompt` tails that `stripTrailingConfigStyle` has to strip back off.

That last point is the strongest argument for building the canvas rather than treating it as polish.

### 6b.4 Implementation notes

- **Phase 5 splits.** 5a = the canvas (read + arrange + per-node test/regenerate/pin + variant compare).
  5b = the book/page views. 5a is the higher-value half and should land first.
- **Read the reference UI before writing any of it** — `AccountManagerUx752/src/workflows/pictureBook.js`
  (1545 lines) and `src/features/pictureBook.js` (658), per the standing project rule. Also
  `SdConfigPanel.js` (374), which is the existing shared per-node config editor and should be reused as
  the card's settings popover rather than reimplemented.
- **Mithril + a canvas layer.** No graph library is currently in the Ux752 dependency set; adding one is
  a decision, not an implementation detail (§10 Q16). Absolute-positioned divs with a CSS transform for
  pan/zoom plus SVG edges is enough for tens of nodes and adds no dependency. Edge rendering is only
  needed where the DAG is not obvious from layout — the reference screenshot draws **no visible edges
  at all**, relying on handles and chips instead, which is a strong hint the first version should too.
- **Progress.** Per-node progress already has a transport: `PictureBookProgressNotifier` →
  `WebSocketService.chirpUser` (`PictureBookService.java:103-113`). Comfy's `WS /ws` per-node events
  (§6) would upgrade a testing card from a spinner to real step progress.
- **New endpoints** (phase 4, transport only): `PUT /{book}/node/{nodeId}/canvas` (geometry),
  `POST /{book}/node/{nodeId}/test` (single-node run producing a new candidate),
  `PUT /{book}/artifact/{artifactId}/select` (make current), `PUT /{book}/node/{nodeId}/handle`.
- **Verification:** a Playwright spec driving establish-style → cast two characters → generate 3
  candidates for one → select the second → assert the dependent scene node shows STALE → regenerate →
  assert only that branch re-ran (via `executedNodeCount` and artifact revisions). That is the same
  assertion §9 already specifies, driven through the real UI.

---

## 7. Ordered implementation phases

Sequenced so nothing waits on KI-60.

**Phase 0 — Ratification (no code).** Stephen answers §9 Q1 (ownership), Q2 (`fastDataCheck`), Q3
(nondeterministic `findRecord`), Q6 (enrolment authority), Q7 (chapter copy vs reference).
*Exit:* answers recorded.

**Phase 1 — Olio plumbing (Objects7 only; no PictureBook behaviour change).**
`BookWorldInitializationRule` (new), `PbOlioContextUtil` (new), `OlioContextUtil` (additive overload +
keyed bounded concurrent cache; stop using `setPermitBulkContainerApproval`), `OlioContextConfiguration`
(+`requireRealms`, +role fields, +`enrolActingUser`), `OlioContext` (role-parameterised
`configureWorldAuthorization` + deterministic group resolution + `requireRealms` gating),
`WorldUtil` (`findWorld` + `fastDataCheck` probe), `Decks`/`ColorUtil` (keyed caches),
`CacheService` (targeted evict — see below).

**Corrected exit criteria (design review round 1).** Phase 1 changes `configureWorldAuthorization`
target resolution, `fastDataCheck`, the `OlioContextUtil` cache key, `Decks`, `ColorUtil`, and removes
`setPermitBulkContainerApproval` — **every one a live behaviour change for grid/arena/game/agent, none
of which `TestPictureBookCustom` exercises.** So the gate is:
- `TestBookWorld` green (the new Olio-plumbing assertions in §9), **plus**
- the **existing** grid/arena/game/Olio test suites green — the actual blast radius. Enumerate them and
  run them; this is the non-regression gate for phase 1, not the PictureBook test.
- `TestPbSecurity`'s **Objects7-level** assertions only (group/role/entitlement/membership via
  `AccessPoint` and `MemberUtil`). Its REST-level assertions (`POST /rest/model/search`,
  `DELETE /{A}/reset`, `/scene/{S}/generate`, `/cancel`) **cannot exist until phase 4** and move there.
  An earlier draft listed the whole of `TestPbSecurity` as a phase-1 exit, which was incoherent.

Two scope corrections in this phase:
- **Dropping `setPermitBulkContainerApproval` is not a one-liner.** `OlioContextUtil.java:43/:83` (and
  `:91/:119` for arena) wrap the entire `initialize()`, and the bulk writes happen deep inside
  `loadWorldData` → `WordParser`/`WordNetParser`. Threading the boolean down to the parameterised
  entry points (`AccessPoint.java:155`, `:163`) is a real refactor with a real performance consequence
  for those loaders. Budget it as such, or scope phase 1 to *not regressing* it and fix it separately.
- **`CacheService` is not a one-line hook.** `clearCaches()` is a no-arg static (`CacheService.java:63-69`)
  reachable from `GET /cache/clearAll` with `@RolesAllowed({"admin","user"})`. A targeted
  `evict(orgId, user, universe, world)` cannot hang off it, and wiring clear-all there would let any
  authenticated user drop every cached Olio context process-wide. Add a distinct, admin-gated
  targeted-evict path, and say which one is meant.

**Phase 1b — Thread universe/world ids through Service7 + Ux752** (the ratified direction in §4
Blocker 2). Optional `universeObjectId`/`worldObjectId` on every endpoint that constructs an Olio
context, defaulting to the current pair; a current-world selection in the Ux that survives navigation
and is sent on every Olio call; the hardcoded `/Olio/Universes/My Grid Universe/Worlds/My Grid World`
in `games/wordGame.js:16` becomes the default rather than a literal.
*Exit:* existing game/Olio e2e specs green with no ids supplied (proving the default path), plus a new
test that two different world ids from one user in one session yield two different worlds' data.

**Phase 2 — Persisted models + graph utilities (Objects7).** 8 model JSONs; `OlioModelNames`/
`OlioFieldNames` constants; enums; `PbGraphUtil` (build / `validateAcyclic` / `computeInputHash` /
`markStaleDownstream` / `recomputeStatus` / `nextRunnable`), `PbArtifactUtil` (persist + sanitize +
supersede chain), `PbSharingUtil` (promote/copy).
*Exit:* `TestPbGraph` green; tables verified via `DBUtil.getTableName`; **no reset used**.

**Phase 3 — Wire the pipeline to the graph, behind a flag (Objects7).** Each seam in
`generateSceneImage` (`:3272-3793`) becomes a node execution, using the **existing** per-stage
`PictureBookProgressNotifier` call sites as node boundaries — that seam map is already in the code.
Persist the §2.5 artifacts. Delete `prepareForeignSubModelGroups` + `createPersistedForeignInstance`;
route sub-records into world groups and narratives through `NarrativeUtil.getCreateNarrative`.
**Dual-write** `.pictureBookMeta` and scene notes so all 16 PB1 endpoints keep working. Feature flag
`picturebook.v2` (existing mechanism, `aiDocs/UxFeatureFlagDesign.md`).
*Exit:* `TestPictureBookCustom#TestPictureBookCustomPipeline` passes **unchanged with the flag off**
(the non-regression gate), and `TestPictureBookWorkflow` passes with it on.

**Phase 3b — ComfyUI backend (Objects7).** §6. *Exit:* `TestComfyBackend` green or visibly skipped;
Swarm parity test green.

**Phase 4 — REST + DTO seam (Service7, transport only).** New endpoints, every one
`@RolesAllowed({"admin","user"})` and a thin delegate: `GET /{book}/workflow`,
`GET /{book}/workflow/node/{nodeId}`, `GET /{book}/artifact/{artifactId}`,
`POST /{book}/node/{nodeId}/regenerate`, `POST /{book}/node/{nodeId}/pin`, `GET /{book}/stale`,
`POST /{book}/members`, `POST /chapter`. Also fix §5.6's three gaps.
*Exit:* `TestPictureBookRestContract` green — including a body-deserialization test per endpoint (the
KI-24/KI-25 class) and a reflective assertion that every resource method carries `@RolesAllowed`.

**Phase 5 — Ux752.** Workflow graph view (nodes, edges, stale badges, artifact revision history,
per-node regenerate/pin), **reading `src/workflows/pictureBook.js` (1545 lines) and
`src/features/pictureBook.js` (658) first**.
*Exit:* `npx vite build` + `npx vitest run` + `npx playwright test e2e/pictureBookWorkflow.spec.js
--workers=1 --project=chromium`.

**Phase 6 — Migration + PB1 deprecation.** `PbMigrationUtil.importV1Book`; deprecate PB1 storage
endpoints only after Stephen verifies. *Exit:* `TestPbMigration` green on a real existing book; v1
records untouched.

### Relationship to KI-60 (required statement)

1. **No duplication.** Nothing in phases 0-6 touches `PathUtil.findExistingNode`/`makePath`.
2. **The collision *target* disappears.** KI-60's failure is a foreign sub-record write to
   `~/Narratives` recovering onto the wrong group (`#151 Apparel` for `#1049 Narratives`). Phase 3
   deletes both writers and moves the destinations into `{world}/Narratives`. The repro path
   (create book → extract → delete book → re-add) stops producing it.
3. **The defect is not fixed.** The wrong-group recovery is reachable from any `makePath`
   get-or-create race, by any caller. **PB2 must not be described as fixing KI-60.**
4. **Sequencing.** Independent both ways. **But if PB2 lands first, KI-60's reproducer disappears and
   the bug will look fixed when it isn't.** I'd recommend Stephen's fix land first purely to keep the
   reproducer available. His call.

---

## 8. Known-issue disposition

| KI | Disposition | Reason |
|---|---|---|
| **KI-60** | **Separate — Stephen's** | PB2 removes the repro path, not the `PathUtil` recovery defect. Landing PB2 first invalidates the reproducer — flagged, not worked around. |
| KI-42 | **Partially addressed; core stays separate** | PB2 deletes the two callers that race on the 7 home groups. The underlying mismatch — constraint `(name,parentId,organizationId)` vs a type-filtered `findByNameInParent` in `makePath` — is untouched. |
| KI-59 | **Structurally closed; visual claim still open** | The landscape becomes a persisted artifact bound with `role="landscapeRef"`, and the FLUX.2 reference strip is persisted. A test can assert the binding exists, the reference decodes, its dimensions match `flux2ReferenceSize`, `generatorRequest` names the landscape artifact's objectId, and the `landscape reference SUPPRESSED` line did not fire — plus the differential test below. It still does not prove *aesthetic* integration. Say so. |
| KI-47 | **Separate** | Per-artifact `sdConfigSnapshot` + sanitized `generatorRequest` make the 4 SD-session changes *auditable* — you can read exactly what was sent — but do not visually verify them. |
| KI-44 / KI-45 | **Extraction: fixed in phase 3. Convergence: deferred with a hook** | PB2's node boundaries *are* the seams KI-45 wants for `generateSceneImage` (534) / `createCharPerson` (463). Convergence with `SceneCompositeUtil` is deferred because its 5-arg KONTEXT overload (`SceneCompositeUtil.java:151`) drops `mood`, explicit steps/cfg, negative prompt and `useConfigStyle` that PB's inline branch does pass (`PictureBookUtil.java:3726`) — converging first regresses PB. Order: extend the shared overload, then converge. |
| KI-49 | **Separate; risk of false closure** | PB2 shrinks the `/extract-scenes-only` response (scenes become rows), which may **mask** it. No claim. |
| KI-28 | **Separate, but PB2 raises exposure** | olioUser-owned characters mean more paths read `ownerId` on projections that `OlioUtil.FULL_PLAN_FILTER` (`:645`) excludes → the `ApparelUtil.java:694` unboxing NPE. Recommend an independent defensive fix (`Number` unboxing) plus a test running the outfit wizard against a world-scoped, olioUser-owned character. |
| KI-27 | **Separate** | Resolving chatConfig once per `olio.pb.run` reduces resolve calls per run. Whether that touches the 404 is unknown. No claim. |
| KI-10 | **Improved by phase 3b** | Comfy's `POST /interrupt` can abort an in-flight generation, which the current `SummarizeProgress` boundary-check design cannot. |
| **New (this analysis)** | **Raise as new KIs** | (a) `configureWorldAuthorization`'s `parentId`-only `findRecord` (`:188-189`) can grant the org-wide Olio role on an unrelated universe/world — live today on multi-universe DBs. (b) `initialized = true` (`:379`) precedes authorization (`:394-395`) inside a swallow-all catch (`:403-406`). (c) `OlioContextUtil`'s cache key omits `organizationId` — cross-tenant. (d) `AccessPoint.setPermitBulkContainerApproval` is a global, non-volatile authorization relaxation. (e) Olio grants Delete on `/Library/*`, exceeding `LibraryUtil`'s own CRU. (f) `cancel` discards its principal — any user can cancel any other's extraction. (g) Scene-addressed PB endpoints never authorize the owning book. |

---

## 9. Verification plan

Rules: real tests only; live backend; `ensureSharedTestUser()` / `ensureIso42001TestUser()` — **never
admin**; LLM/SD paths single-threaded against the DGX Spark at `192.168.1.42`; **never `-Dreset`** (new
tables arrive via `IOSystem.java:120-153`). A live test that cannot reach its backend must **skip
visibly, never pass** (KI-48).

### Backend (`mvn -o -pl AccountManagerObjects7 -Dtest=… test`)

**`TestBookWorld`** (phase 1):
1. universe `Books` exists; `count(data.geoLocation)` in its `Locations` group is **0**;
   `count(data.color)`, `count(data.word)` in `Names`, `count(data.trait)` all **> 0**.
2. two book worlds coexist; `findBookContext(user,"book-a")` and `("book-b")` return **distinct**
   contexts for the **same** user (Blocker 2, proven).
3. `octx.isInitialized() == true` (the rootEvent gate is satisfied) **and** post-init grant
   verification passed.
4. idempotency: group count under the world container identical before/after a second init;
   `EventUtil.getRootEvent` returns the same objectId.
5. `octx.getRealms()` empty **and** `count(data.geoLocation)` still 0 (proves `getRealms()`'s create
   branch at `:525-531` creates nothing).
6. `requireRealms=false` suppresses the three ERROR lines — assert via a log appender or the gated
   return, **not** by eyeballing output.
7. **Grant recursion probe:** does a grant on the world container reach `Gallery/Characters` without
   `scanNestedGroups`? Settles the `"recursive": true` question.
8. Same user name in two organizations returns two distinct contexts, and the org-B context's
   `getOlioUser().organizationId == orgB`.
9. `AccessPoint.isPermitBulkContainerApproval()` is false before and after a book creation.

**`TestPbSecurity`** (phases 1-2 — the four isolation properties, as negative tests). Two distinct
shared test users, A enrolled on book A, B on book B:
- B cannot read A's `olio.pb.book`, `.workflow`, `.node`, `.artifact`, `.scene`, nor the `data.data` bytes.
- B's `POST /rest/model/search` for `olio.pb.node` **with an explicit numeric `organizationId`
  condition** returns zero of A's nodes.
- B cannot create in A's `{world}/Workflow`.
- B is **not** enrolled as a side effect of any read (`MemberUtil.isMember` false after a full read
  sweep) — run twice: once with B having never initialized a context, once after.
- Enrolling B in book A requires the Writer role; an unauthorized attempt fails.
- A user holding the book role but **not** the universe role cannot read the universe corpora (proves
  the two-role requirement is real, not decorative).
- After creating book B, the effective entitlements on book A's groups are **unchanged** — no grant
  naming B's role, none naming the org-wide `Olio User`.
- No role holds Delete on `/Library/*` after N book creations.
- B attempting `DELETE /{A}/reset` deletes **zero** records.
- B calling `POST /scene/{S}/generate|blurb`, `PUT /scene/{S}/status` with a scene from A's book → 403,
  no mutation, no SD call.
- A starts an extraction; B posts `/{sameKey}/cancel` → `cancelled:false` and A's extraction completes.
- Force `rootEvent == null` during creation → either the book isn't visible at all, or its owner still
  has full CRUD. Silent zero-grant groups fail.
- No PB endpoint reaches `WorldUtil.cleanupLocation` / `getWriter().delete(Query)`.

**`TestPbGraph`** (phase 2) — real persisted records: cycle rejected by `validateAcyclic`; superseding
one artifact marks exactly the expected downstream set STALE and leaves an unrelated branch CLEAN; a
pinned node is marked STALE but refused without `force`; `inputHash` changes when `configOverride`
changes and does **not** change when an unrelated node runs; `configOverride` round-trips as
only-set-fields (assert the JSON does **not** contain `flux2Cfg`/`kontextModel`/`mannequin*` unless
explicitly set — the dead-resource guard); node/binding/artifact round-trip through a PATCH-shaped
partial update **including `name`**.

**`TestPictureBookWorkflow`** (phase 3, LLM+SD, single-threaded) — the same six steps as the reference
test, through the graph.

**`TestPictureBookCustom#TestPictureBookCustomPipeline`** — run **unchanged** at the end of every phase
from 3 onward. This is the non-regression gate. **If it needs editing, that is a signal to stop, not to
edit it.**

**`TestPbMigration`** (phase 6) — import a real v1 book; scenes/characters/images present, v1 records
untouched, migrated nodes carry `nodeStatus = DONE_UNVERIFIED` and `inputHash = null` (honest labelling
of provenance we don't have).

### Honestly verifying an image pipeline (the KI-59 hole)

Existence-only assertions are the hole. Three levels; only the first two go in the default suite.

**Level 1 — structural (deterministic).** For every image: `byteLength > N`; PNG magic bytes;
`ImageIO.read` decodes; decoded width/height equal the config's expectation (composite dimensions,
`flux2ReferenceSize` for references, the forced 1024×768 landscape at `:3609-3611`, mannequin 1024²);
the expected bindings exist with the expected roles and non-null `sourceArtifact`; `generatorRequest`
contains the reference artifacts' objectIds and **neither** a base64 payload **nor** `session_id`; the
`landscape reference SUPPRESSED` line did not fire when `flux2IncludeLandscapeRef` is on.

**Level 2 — differential (the real answer).** Run the same scene **twice with an identical seed and
identical config**, once with the landscape reference bound and once unbound, and assert the two
composites' `contentHash` **differ**. That is a genuine, human-free proof that the reference *affects
the output* — which is what "does it integrate" means operationally. If the hashes match, the reference
is being ignored and the test fails for the right reason. Same technique per reference role
(`portrait0`, `portrait1`, `referenceStrip`). Corollary: same seed + config + bindings ⇒ **same**
`contentHash`. **If the backend turns out not to be seed-deterministic, drop that corollary and say so
— do not weaken it into a tautology.**

**Level 3 — perceptual, behind an env flag, never in the default suite.** Mean-absolute-difference /
histogram distance between composite and downscaled landscape over the background region, against a
threshold calibrated from a stored reference pair. A failed threshold means "investigate", not
"broken", and the message must say that.

### Frontend

`npx vite build`; `npx vitest run` (stale-badge and graph-layout state logic);
`npx playwright test e2e/pictureBookWorkflow.spec.js --workers=1 --project=chromium` driving create →
extract → generate → edit a node → **assert downstream nodes show stale** → regenerate → assert only
the expected nodes re-ran (via `olio.pb.run.executedNodeCount` and artifact revision numbers). Plus a
second-user negative spec mirroring `TestPbSecurity`. Requires the Docker stack on `:8443` plus Vite on
`:8899`.

---

## 10. Risks & open questions for Stephen

**Q1 — Ownership of `olio.pb.*` pipeline records: olioUser (proposed) or the acting user?** Determines
the entire grant set. Must be decided before phase 2.

**Q2 — `WorldUtil.fastDataCheck`:** change the probe to colors+surnames (helps every universe, changes
existing init cost characteristics) or add a config-driven overload (zero change, one more knob)?
*Recommendation: change the probe.*

**Q3 — The nondeterministic `findRecord` (`OlioContext.java:188-189`):** fix in place (helps all
callers, but changes grid/arena grant targets on a multi-universe DB — a live behaviour change) or
bypass only via PB2's new overload? Either way, raise it as its own KI.

**Q4 — KI-60 sequencing → better answer: pin the reproducer in a unit test, then sequencing stops
mattering.** Superseded recommendation below; the original ("land Stephen's fix first") was treating a
test-coverage problem as a scheduling problem.

The existing test `TestKi42MakePathNeverReturnsAGroupThatIsNotInTheDatabase`
(`TestPictureBookKnownIssues.java` ~`:230-256`) already drives the adoption path deterministically: it
pre-creates a `Narratives` group under the home with a type the DATA-filtered lookup won't match, calls
`makeGroup(user, "~/Narratives")`, and asserts (a) the returned id has a real row and (b) exactly one
`Narratives` row exists. **It never asserts the returned record is named `Narratives`.** So it would pass
even if `makePath` handed back `#151 Apparel` — which is precisely KI-60's second defect. That is an
assertion gap, not a missing test.

Two steps, both independent of PB2:

1. **Add the identity assertion** — `assertEquals("Narratives", result.get(FieldNames.FIELD_NAME))`, plus
   `parentId` and `organizationId`. One line each, and it makes the existing test capable of failing on
   the reported defect. Worth doing whatever else happens.
2. **Reproduce the wrong-group case specifically.** Step 1's scenario recovers onto a *correctly named*
   row, so it won't exhibit the Apparel substitution on its own. That needs the sibling set present
   (`Apparel`, `Wearables`, `Qualities`, `Narratives`, `Profiles`, …) and the same *shape* of
   `findByNameInParent(auth.group, <home>, <name>, …)` lookup repeated across siblings, which is what a
   picture-book run does — then run it with and without `setCache(false)`. That is exactly the one-step
   experiment KI-60's own entry proposes, and it would confirm or eliminate the `CacheDBSearch`
   query-key theory in a single run.

Once the wrong-group case is a deterministic unit test, it survives PB2 deleting the PictureBook
reproducer, and the two work streams become genuinely order-independent. **Still Stephen's call, and
still his fix** — nothing in PB2 touches `PathUtil`. This is a testing recommendation, not a claim on
the fix.

**Q5 — `initialized = true` before authorization.** PB2 designs around it with post-init verification.
Should `OlioContext.initialize()` itself be corrected for all callers? That is a behaviour change for
grid/arena.

**Q6 — Enrolment authority.** Proposed: `{bookSlug} Writer` or `Olio Admin` may add members; creation
enrols the creator; nothing else enrols. This is the difference between per-book isolation and org-wide
visibility.

**Q7 — Chapter semantics: copy or reference?** Proposed copy, with a `chapterSource` binding for
lineage. A "shared single record" reading is a materially different implementation — and note
`deleteGroupRecursive`'s stated invariant (`:4243-4245`) that sub-records are never shared.

**Q8 — `Books` universe per organization** (proposed; the only PBAC-consistent reading) **or per
deployment?**

**Q9 — Does `reset()` destroy the graph?** Proposed: it clears artifacts and marks nodes STALE but
**keeps** the graph (the ComfyUI mental model).

**Q10 — Scale.** One book = 39 groups + 36 world-model foreign refs. 100 books ≈ 3,900 groups, and the
grant loops are O(groups × perms × types) per init. Needs a **measured** init time in phase 1. Note
`PathUtil.makePath` is `synchronized` (`PathUtil.java:62`) — a process-global monitor — and creating a
world means 39 `makePath` calls plus `/Library` probes, so book creation serializes all path resolution
in the JVM. If it's slow, grants collapse to the world container and rely on the `"recursive": true`
question that phase 1's probe settles.

**Q11 — Do the 3 PB groups belong on `olio.world`/`WorldFactory`** (canonical, but every game world
grows three unused groups) **or created by the rule under the world container** (proposed)?
*My judgment: the rule.*

**Q12 — Comfy scope.** Is phase 3b's "one node → one Comfy call" the right first step, with
whole-subgraph compilation as a later phase? And is Swarm remaining the default correct?

**Q13 — Read-only context shape.** Should `findBookContext` return a partially-populated `OlioContext`
with a `readOnly` flag, or a narrow read-only view interface? A half-built `OlioContext` with null
`clock`/`realms` is a trap for any Olio utility assuming `initialize()` ran (§5.5).

**Q14 — Is book *existence* sensitive?** `configureEnvironment:299` grants the org-wide Olio User role
Read on `/Olio`, `/Olio/Universes` and the `Worlds` container, so book **names** stay org-wide
discoverable even though book content does not. Acceptable, or does that grant need revisiting?

**Q15 — Group/cast entities on the canvas.** `Meadow Herd` in the reference screenshot is a collective,
not an individual. Bind such a chip to an `auth.group`, or introduce an `olio.pb.castGroup` model?

**Q17 — Orphan worlds.** With no list-worlds API (§3.1b), a world created without its `olio.pb.book`
record is invisible and unreclaimable. Create the book record first and add an admin-only reconcile
utility — confirm that's the right shape.

**Q18 — Access-request completion.** Book sharing is add-by-writer-only until the `access.accessRequest`
flow has a UI (§5.4). Is that acceptable for the first release, and does completing it belong in this
work or its own?

**Q19 — Relocating the Olio roles.** Moving `~/Roles/Olio User`/`Olio Admin` out of olioUser's home to an
org-level path makes them administrable through the existing role-membership UI, but changes existing role
paths and needs a migration. In or out of scope?

**Q16 — Canvas rendering dependency.** Hand-rolled absolute-positioned Mithril + CSS transform + SVG
edges (no new dependency, adequate for tens of nodes), or adopt a graph/canvas library? The reference UI
draws no visible edges at all, which argues for the former.

### Risks not phrased as questions

- **Highest:** a mis-set grant makes every book readable org-wide. Mitigation is §5.7's four properties,
  each with a negative test. **Do not ship phase 4 without those green.**
- **Second:** dual-writing meta + graph in phase 3 means two sources of truth for one release.
  Mitigation: the graph is authoritative when the flag is on and `.pictureBookMeta` is write-only in
  that mode (never read back), so they cannot disagree in a way that affects behaviour.
- **Third:** `PB_PIPELINE_VERSION` bumps invalidate every node's `inputHash` and mark whole books
  stale. That is correct behaviour and it will look like a bug. Document it; log the bump loudly.
- **Fourth:** the JVM will hold contexts for many worlds. The bounded LRU is a cap, not a solution;
  measure memory in phase 1 with 5 book worlds live.
- **Fifth:** the Comfy graph builders couple to installed node names. Mitigation: build from a captured
  working graph, validate against `GET /object_info` at startup, and fail loudly with the missing
  `class_type` rather than sending a malformed graph — the same lesson as KI-31's construction-time
  placeholder guard.

---

## Appendix A — design review round 1 (architect), remaining items

Five blocking findings (B1-B5) are already folded into the sections above: the D1 split correction
(§5.5), the `fastDataCheck` probe correction (§4 Blocker 1), the external-record staleness mechanism
(§2.3), the config role fields (§3.4), and the phase-1 exit criteria (§7). What remains:

**Corrections to claims this document made**

- **Group entitlements do not recurse.** `FieldSchema.isRecursive()` has one consumer,
  `PolicyUtil.java:255`, and it only decides read-policy scan inclusion. Folded into §4 Blocker 3.
  Consequence: **§10 Q10's fallback plan ("collapse grants to the world container and rely on
  `recursive: true`") does not exist** and must not be relied on. If per-book grant cost is a problem,
  the answer has to be something else.
- **The SD server URL is deployment-global, not per-org**, so exactly one process-wide copy is
  permitted — §6.1 item 1 misapplied the architecture rule. The real issue there is different and
  belongs in §6: `sd.server.apiType` is a **boot-pinned** init-param while the server URL is
  **DB-backed runtime-configurable** via `system.connection`/`ServerConfigUtil`, so the two can
  disagree after a runtime URL change (a Comfy URL with a Swarm apiType, or the reverse). State that
  propagation bound explicitly and validate the pair at resolution time.
- "Copy `olio.narrative` exactly" then lists a different `query` array; `narrativeModel.json:8` is
  `["id","groupId"]`. Harmless — the union with `common.baseLight` supplies the rest — but "exactly"
  is wrong.
- `SDAPIEnumType` also has `UNKNOWN`. And `artifact.backend` lands in phase 2 while `COMFY` is added in
  phase 3b, so enum `baseClass` validation rejects `COMFY` until then — sequence the enum value first.
- Line drift, substance intact: cancel is `:474-475`; the cancel registry `:86`;
  `createPersistedForeignInstance`'s `"~/" + getGroup()` ~`:2196-2197`; `getRealms()` `:514-534`.

**Model-definition gaps to close before phase 2**

- **Reverse-edge indexes are missing and they carry the core operation.** Downstream propagation is a
  reverse lookup. Add `index: true` to `binding.node`, `binding.sourceNode`, `binding.sourceArtifact`,
  `artifact.producedByNode`, `artifact.current`, `node.workflow`, `node.handle`.
- **Every `type: "enum"` field needs `"baseClass"`** or `getEnum()` cannot resolve. Not stated in §2.2.
- **`likeInherits` brings no fields**, so PB2 models get **no `urn`** and **no `groupPath`**
  (`common.baseLight` has neither, and `data.directory`'s fields are not inherited). Anything in the
  canvas view or the clone paths that wants a path must use `groupId`. It also means
  `data.directory`'s `name, groupId, organizationId` constraint is **not** inherited
  (`RecordFactory.java:759-763` only adds the name to the inherits set), so stated invariants —
  one workflow per book, unique `(book, index)` per scene, one `current` artifact per `(node, role)`,
  unique `handle` per book — need **explicit `constraints`** or nothing enforces them.
- **Drop `"group": "<fallback name>"`.** Declaring a fallback that §2.1 Rule 2 forbids is a loaded gun;
  omit it so a missing explicit path fails loudly — and verify it fails loudly rather than NPEs.
- **`configOverride`-as-JSON-string costs are real and were understated.** The reasoning is right
  (`configModel.json` documents "NO SCHEMA DEFAULT ON PURPOSE" on exactly the fields cited), but the
  override is **not queryable** — which is the stated benefit of moving `sdConfig` to real records — and
  **not schema-validated**, so `minValue`/`maxValue` on `flux2Cfg` and friends are not enforced. Also
  unspecified: where `changedFieldNames` for `cfg.copyRecord(...)` comes from. It is knowable from a
  REST payload's JSON keys, **not** from a `Factory.newInstance` record, which materialises everything.

**`ColorUtil` — three items phase 1 must address, beyond "keyed caches"**

- `colorComplements` is keyed by hex alone while the underlying query is scoped by `world.colors.id`
  (`ColorUtil.java:161-166`) — per-org data in a static field, squarely the architecture rule. Either
  key it by world, or state explicitly that it is global.
- `findComplementaryColor` reads via **raw SQL that bypasses PBAC** (`ColorUtil.java:167-200`). Not in
  the §5.5 audit; it should be.
- `ColorUtil.java:139` contains a `makePath(owner, MODEL_GROUP, "~/Colors", …)` **home-group fallback**
  of exactly the class §2.1 Rule 2 bans — and Rule 2 is currently stated only for `PictureBookUtil`.
- The `Decks` fix (a `ConcurrentHashMap` keyed by world objectId holding `BaseRecord[]`) is **unbounded**,
  unlike the context cache's LRU(32). Same growth problem, same file.

**Hoist out of phase 4:** the two live authorization defects in §5.6 — `cancel` discarding its principal
and the scene-addressed endpoints not authorizing their book — are exploitable today and independent of
all of PB2. They should ship as a standalone patch, not wait five phases.

**Layering verdict: no violation found.** All new models, utilities, enums and the Comfy package sit in
Objects7; ISO42001 untouched; phase 4's endpoints are delegates; the "per-book check is PBAC data
enforced by `AccessPoint`" design is coherent. One caveat: the scene-endpoint authorization fix must
live in an Objects7 utility, **not** as an `if` block in the resource method, or it becomes business
logic in Service7.

**Hard prohibitions: clean.** No reset — §2.1's claim was verified against `IOSystem.java:113-141`. No
new PBAC bypass; the plan removes two. Tests use `ensureSharedTestUser()`, never admin, and honour the
KI-48 skip-visibly rule.

**Rules worth promoting into `.claude/rules/architecture.md`** (currently unwritten policy):
1. The DAG discipline in §2.3 — "the hash is truth; the status is a repairable cache, never trusted for
   correctness decisions" — plus cycle safety and reverse-edge indexing.
2. A criterion for JSON-in-a-string-field vs a model: only where "unset must be distinguishable from
   default", and always stating the queryability/validation loss.
3. **§2.1 Rule 2 should be a project rule, not a PB2-local one.** `schema.getGroup()` / `~/` home-group
   fallbacks are banned nowhere in the rules, yet they are the mechanism behind KI-42/KI-60 and appear
   in at least two files (`PictureBookUtil`, `ColorUtil.java:139`).

---

## Appendix B — follow-ups noted in passing

- **Test credentials should reference a `system.connection` record, not an inline token.** RESOLVED
  2026-08-11: an Azure OpenAI key had appeared in
  `AccountManagerAgent7/src/test/resources/resource.properties:19` as a working-tree modification;
  never committed (verified via `git log -S` and a scan of all reachable commits), and Stephen removed
  it the same day. Recorded because the *pattern* is the risk, not that one value: Stephen confirmed
  connection handling has since changed, so aligning the test resources with
  `ServerConfigUtil`/`system.connection` would remove the whole class of exposure.
- **Stale javadoc:** `PictureBookService.java:56` documents `~/PictureBooks/{bookName}/`; the code
  writes `~/Data/PictureBooks/{bookName}` (`PictureBookUtil.java:324`, `:3078`).
- **Dead config:** `OlioContextConfiguration.useSharedLibraries` (`:32`, `:77-83`) is never read;
  `WorldUtil.java:36-37` uses its own static with a `// TODO: Use the OlioContextConfiguration`.
- **Dead hook available:** `chatConfigModel.json:132-141` declares `universeName`/`worldName`, read by
  no Java code — a ready-made place for chat to select a book universe/world.
