# PictureBook 2.0 — Design & Implementation Plan

**Date:** 2026-08-11, revised 2026-08-12
**Status:** **DESIGN COMPLETE — READY FOR IMPLEMENTATION.** Reviewed by `security-reviewer` and
`architect` (returned CHANGES-NEEDED; all five blocking findings folded in). Implementation to be done in
a separate conversation.
**Supersedes (on completion):** `PictureBookDesign.md` §1-8 storage model; `PictureBookSdConfigRefactor.md` config model.

---

## 0. Implementation handoff — read this first

**Start here:** §7 Phase 1. Nothing depends on KI-60 being closed.

### Ratified decisions (do not re-litigate)

| # | Decision | Where |
|---|---|---|
| 1 | Olio security fixes land **inside PB2 phase 1** → phase 1 is a breaking change to the game and gates on the **existing game/arena/Olio suites**, not on `TestPictureBookCustom` | §7 |
| 2 | **Uniform `olioUser` ownership** for every `olio.pb.*` record, including `.book` and `.run`; authorship via `createdByObjectId` | §5.2 |
| 3 | Four roles per tier: `AdminRole`, `UserRole`, `AuthorRole`, `EditorRole` | §5.3 |
| 4 | The two live auth defects (`/cancel` principal; scene endpoints not authorizing their book) ship **with phase 4** | §5.6 |
| 5 | **Universe/world lifecycle hidden inside PictureBook** — no world management API/UI; the book carries the FKs; "list" = list books | §3.1b |
| 6 | **Universe/world ids travel with the call**, defaulting to the current pair | §4 Blocker 2 |
| 7 | Reuse Rocket's **concepts, not its code** | §5.3 guardrail |
| 8 | **Explicit grants at both tiers** is the primary authorization design. The role-membership join is **not** a PB2 dependency | §5.3 point 2 |
| 9 | **No read-up.** Resolve book storage **by FK**, never by path traversal as the acting user | §5.6b |

### Hard guardrails

- **Never `-Dreset`**, and **never reset `am7db`**. New tables arrive via `IOSystem.java:113-141`.
- **Objects7 JUnit → `am7db` (`15432`)** — key location dependency, cannot be repointed.
  **REST/App/Ux → `am7test` containers** (`9443`, pg `15433/am72db`), which *are* resettable. §9.
- **Do not port** AM6's materialised effective-authorization layer, `pendUpdate` bookkeeping, direct
  participation-table manipulation, or the bulk-factory machinery. §5.3 guardrail.
- **Do not touch `PathUtil`** — KI-60 is Stephen's. §7.
- Preserve the deliberate no-schema-default fields (`flux2Cfg`, `flux2ReferenceSize`,
  `flux2IncludeLandscapeRef`, `kontextModel`, `mannequin*`); a default there makes `flux2Defaults.json` dead.
- `TestPictureBookCustom#TestPictureBookCustomPipeline` must pass **unchanged**. If it needs editing, stop.

### Phase 0 ratification — RECORDED 2026-08-12 (Stephen)

Phase 0's exit criterion ("answers recorded") is met. These are now ratified and must not be
re-litigated; they are folded into the sections they govern.

| Q | Answer | Consequence |
|---|---|---|
| **Q1** ownership of `olio.pb.*` | **olioUser, uniformly** — every `olio.pb.*` record including `.book` and `.run`. Authorship via `createdByObjectId`. | Confirms §0 decision 2 and **supersedes §5.2's table row** that assigned `.book`/`.run` to the acting user. The grant set is therefore uniform across all eight models. |
| **Q2** `WorldUtil.fastDataCheck` | **Fix in place** — change the probe to the universe-local **`Traits`** corpus per the corrected §4 Blocker 1. Not colors+surnames (shared libraries; would silently leave `Traits` empty). | Live behaviour change for every existing universe. Gated on the existing grid/arena/game suites. |
| **Q3** nondeterministic `findRecord` (`OlioContext.java:188-189`) | **Fix in place** — name-resolution via `pathUtil.findPath`, never a `parentId`-only `findRecord`. | Changes grid/arena grant targets on a multi-universe DB. Still raised as its own KI. |
| **Q6** enrolment authority | **As proposed (§5.4).** Creation enrols the creator as Writer; only `{bookSlug}` Writer or `Olio Admin` may add members, via an audited `POST /{book}/members`; **nothing on a read path ever enrols**. | `enrolActingUser` defaults to **false**; both `OlioContext` auto-enrolment sites (`:270-273`, `:282`) are removed, with the explicit registration call added to whatever provisions game access **in the same change** (§5.4 consequence 1). **SEE THE Q6-STAGING AMENDMENT BELOW — it supersedes the "in the same change" clause.** |

#### Q6 staging amendment — RATIFIED 2026-08-12 (Stephen), supersedes the Q6 row's "in the same change" clause

The Q6 row above says the explicit registration call lands **in the same change** as the removal. That is
not achievable in phase 1 and has been superseded. There is **no existing "provisions game access" hook**
to attach registration to: all 18 `GameService` endpoints and `OlioService`'s 5 call `getOlioContext`
inline, and `GET /game/newGame` is a read. The only plausible host, `GameUtil.adoptCharacter` (`:936`),
**cannot satisfy `registerUser`'s own authorization rule** — it is reached from `GameService.java:829,:841`
where a normal `@RolesAllowed("user")` caller adopts a character *for themselves*, so the actor is neither
an admin-role member nor the org admin.

**Ratified staging:**

- **Phase 1 lands the mechanism only** — the `enrolActingUser` config field (default **false**), gates on
  **both** enrolment branches (`:267-273` every-run *and* `:282` first-run), and the audited
  `registerUser(actor, user, asAdmin)`. Book contexts get `false`. `getGridContext`/`getArenaContext` and
  every direct config-building test site explicitly set **`true`**.
- **Wiring the game side is its own later change**, at which point the opt-ins come out.
- **Honest statement of where this leaves things:** after phase 1, "nothing on a read path ever enrols" is
  true for **book contexts** and **not yet true for the game path**. Phase 1 must not be described as
  having delivered Q6 in full.

**The nine opt-in sites** (enumerate them as a checklist; grep `new OlioContextConfiguration`):
`OlioTestUtil.java:97`, `TestGameUtilSync.java:551`, `TestOlio.java:67`, `TestOlio.java:219`,
`TestOlio2.java:138`, `TestOlioGameFeatures.java:530`, `TestRealm.java:22`, `TestRealm.java:137`,
**`TestSD.java:93`**. Adding `setEnrolActingUser(true)` at each **is nine test edits** — phase 1 is not
"zero test edits". It is zero *behavioural* test change: no assertion is weakened, and the two
`TestPictureBookKnownIssues#TestKi35*` tests (which assert that constructing a context enrols) keep
passing unmodified because the grid path still enrols.
| **Q7** chapter semantics | **Copy, as proposed (§3.5).** Copy carries all seven foreign sub-records; lineage via a `chapterSource` binding. | Required by `deleteGroupRecursive`'s stated no-sharing invariant (`PictureBookUtil.java:4243-4245`). |

**Scope ratified for this implementation run: phases 1 through 4 (backend complete).** Phases 5 (Ux752)
and 6 (migration) are out of this run. Q15/Q17/Q19/Q12 below remain open but block only phases 3b/5,
outside this run's scope — except **Q17 (orphan-world reconcile)**, which phase 3 touches.

### Open questions that still block work

| Q | Blocks | Note |
|---|---|---|
| Q15 | canvas cast entities (phase 5a) | "Meadow Herd" is a collective — bind to `auth.group` or a new `olio.pb.castGroup`? |
| Q17 | book deletion (phase 3) | orphan-world reconcile shape |
| Q19 | phase 1 scope | relocating `~/Roles/Olio *` to group paths needs a migration — in or out? |
| Q12 | phase 3b | Comfy: one-node-one-call first, Swarm stays default? |

Q1-Q11, Q13-Q14, Q16, Q18, Q20 are answered inline. §10.

### Verified-but-unconfirmed items — do not restate as fact

- **ISO42001's role-to-role wiring may be inert** (§5.3, "SUSPECTED DEFECT"). Code-reading inference;
  a verification procedure is given. Confirm before acting on it.
- **Rocket's private `Rocket.enrollInCommunityLifecycle`/`enrollInCommunityProject`** were not read —
  only the public wrappers. Their participation writes and the `getViewPermissionForMapType` permission
  argument have no direct AM7 analogue and need mapping. §5.3.

### Suggested cleanup KIs to log (not part of PB2)

1. Dead effective-role infrastructure: `effectiveRoleTemplate.sql`/`effectiveActorRoleTemplate.sql` call
   `roles_to_leaf`, **undefined in AM7**; no Java reads the materialised views;
   `refreshMaterializedViews()` has one caller (`Console7/AdminAction.java:193`).
2. `configureWorldAuthorization`'s `parentId`-only `findRecord` (`OlioContext.java:188-189`) is
   first-row-wins and can grant on an unrelated universe/world. Live today on multi-universe DBs.
3. `initialized = true` (`:379`) precedes authorization (`:394-395`) inside a swallow-all catch (`:403-406`).
4. `OlioContextUtil`'s cache key omits `organizationId` — cross-tenant.
5. `AccessPoint.setPermitBulkContainerApproval` is a global, non-`volatile` authorization relaxation.
6. Olio grants `Delete` on `/Library/*`, exceeding `LibraryUtil`'s own CRU.
7. `PictureBookService.java:56` javadoc path is stale (`~/PictureBooks` vs `~/Data/PictureBooks`).

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
2. **PB2 never builds a path from `schema.getGroup()`.** Every create passes an explicit world-scoped
   path. `"~/" + schema.getGroup()` at `PictureBookUtil.java:2169, :2190` is precisely what produced the
   KI-60 collision target — it prefixes the **acting user's home** onto the hint.
   *Clarified 2026-08-14: this rule is about the `"~/" + …` synthesis, **not** about the model-level
   `"group"` declaration.* The model-level `"group"` is a legitimate, widely-used **name hint for where
   an instance is saved relative to its parent** (57 models declare one) and PB2 models should declare it
   like any other model. What is banned is a caller turning that hint into a home-relative path. Other
   live instances of the banned shape: `CharPersonFactory.java:35, :44-50`.
3. **No schema `default` on any config-ish field** (see above).

**How new tables land without a reset.** `IOSystem.open`
(`AccountManagerObjects7/src/main/java/org/cote/accountmanager/io/IOSystem.java:120-153`) always scans
`ModelNames.MODELS`; for an identity model with no table it runs `dbUtil.generateNewSchemaOnly(schema)`
(`:125`), and for an existing table it emits `ALTER TABLE … ADD COLUMN` patches (`:135-139`).
`properties.isReset()` is never needed. Add JSON + register in `OlioModelNames.MODELS` → tables appear
on the next Tomcat boot or JUnit run. Tables land as `A7_olio_pb_book_0_1` etc. Column drops stay
behind the off-by-default `isDropColumns()`.

### 2.2 The models

> **READ THIS BEFORE WRITING THE JSON — three ratified corrections apply to every field list below**
> (full reasoning in Appendix D, "Model-definition corrections"):
> 1. **"(indexed)" below never means `index: true`.** `DBUtil.java:88` sets
>    `useFieldIndexGuidance = false`, so a field-level `index` flag creates **no database index** — and
>    `PolicyUtil.java:255` *does* read `isIndex()`, adding a per-query foreign-record read-policy scan. Real
>    indexes come from **`constraints`** (unique) and **`hints`** (non-unique). Read every "(indexed)" as
>    *"needs a `hints` entry"*, and reverse edges (`binding.node`, `binding.sourceNode`,
>    `binding.sourceArtifact`, `artifact.producedByNode`, `artifact.selected`, `node.workflow`,
>    `node.handle`) are **`hints`**.
> 2. **Two fields are renamed.** `olio.pb.scene.index` → **`sceneIndex`** (`index` is not in
>    `DBUtil.reservedWords` and would be emitted unquoted); `olio.pb.artifact.current` → **`selected`**
>    (and "one `selected` per `(node, role)`" is **not** expressible as a unique constraint — booleans are
>    never NULL, so it would forbid a second *superseded* row; constrain
>    `(producedByNode, role, revision, organizationId)` and enforce single-`selected` in
>    `PbArtifactUtil.setSelected` with a post-write re-read).
> 3. **All eight models carry `urn`** (decision 8) — `common.baseLight` omits it, so it must be added
>    explicitly. Because `UrnProvider` composes from `name` (not `handle`) and `common.urn` has **no**
>    uniqueness constraint to catch a collision, `node`/`binding`/`artifact`/`run` names must be derived to
>    be unique within their group (from `node.handle`, and `role + bindingOrdinal` for bindings).
>    `common.groupExt` *does* supply a virtual `groupPath`; `likeInherits` imports no fields, so
>    `data.directory`'s `name, groupId, organizationId` constraint is **not** inherited and every invariant
>    needs its own explicit `constraints` entry.

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
`name`, `book` (foreign), `sceneIndex` (int, indexed — renamed from `index`), `title`, `description`, `summary`, `setting`,
`action`, `mood`, `blurb`, `userEdited`, `characters` (foreign list `olio.charPerson`,
`dedicatedParticipation`), `sceneNode` (foreign `olio.pb.node`).
Replaces the per-scene `data.note` JSON (`PictureBookUtil.java:2900-2922`). Scene order becomes an
indexed column instead of array position in a blob; `PUT /scenes/order` becomes N patches on `sceneIndex`.

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
- `revision` (int), `supersedes` (foreign self), `selected` (boolean — renamed from `current`) — version
  chain, so old images stay viewable instead of being overwritten
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
   (`PictureBookUtil.java:4212-4289`, the KI-32 fix), generalised over the world's groups —
   so every delete passes `AccessPoint.delete` and the `{bookSlug} Writer`
   Delete grant is the gate. **Note the existing enumeration inside `deleteGroupRecursive` uses the
   unauthorized `getSearch().findRecords` (`:4220,4228,4255,4281`); generalising it is the moment to fix
   that, not to propagate it.**
   **CORRECTED 2026-08-12 — do NOT use `getWorldGroups` for this.** An earlier draft of this point (and
   the `WorldUtil` API list in the paragraph above) said to generalise `deleteGroupRecursive` "over the
   world's groups via `getWorldGroups` (`:312`)". **`WorldUtil.getWorldGroups` does not return the
   world's groups.** `:312-315` queries `MODEL_GROUP` with `parentId = world.get(population.id)` — it
   returns the children of the world's **Population** group. Using it would enumerate population
   subgroups only and **silently miss `Book`, `Workflow`, `Artifacts`, `Gallery` and 35 of the 36 world
   groups**, i.e. "delete a book" would leave the entire graph and every artifact behind. Use the same
   deterministic enumeration Blocker 3 specifies: the world record's own foreign `auth.group` fields plus
   the world container resolved **by name** via `findPath`, then its children by `parentId`. Add a javadoc
   note on `getWorldGroups` recording what it actually does, so this trap is not re-entered.
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

  **CORRECTED 2026-08-12 — the verification as first drafted asserts nothing. Two independent defects:**

  1. **`isInitialized()` is useless as a grant check.** `initialized = true` is set at `:379`, *before*
     the `configureWorthAuthorization` calls at `:394-395`, and the whole body sits inside the
     swallow-all `catch (Exception e)` at `:403-406` which logs and returns normally. So
     `isInitialized()` returns **true even when authorization threw**. Asserting it proves only that
     `:379` was reached. **Fix:** add a distinct `authorizationConfigured` flag set only after
     `:394-395` completes, and have `getCreateBookContext` assert *that*. (Moving `:379` itself is
     §10 Q5 — a live behaviour change for grid/arena — and stays out of scope.)
  2. **"Probe one authorized create" cannot detect a skipped grant among ~36 groups.** A single probe
     hits one group; a grant missing on a different one passes. **Fix:** verify grants across the whole
     deterministically-enumerated group set (the same set Blocker 3 specifies), not one sample.

  **Consequence for the null-group-field question:** an earlier revision proposed downgrading
  `:170-172`'s throw to skip+warn on the book path, relying on this post-init verification to catch the
  result. Given defect 1, that trade was unsound — it removed a loud failure and replaced it with a
  check that could not fire. **Decision: keep the throw on both paths.** `WorldFactory.implement()`
  creates all 36 groups unconditionally, so a null foreign group field on a book world is a genuine
  anomaly and should abort loudly. If one is ever observed, handle it with a **named allowlist** of
  legitimately-nullable fields — do not pre-emptively soften an error that has not yet fired.

### 3.4 `OlioContextConfiguration` changes

**Corrected in design review round 1 — `requireRealms` alone is not enough.** `OlioContext.java:394-395`
unconditionally calls the **2-arg** `configureWorldAuthorization`, which resolves the org-wide
`~/Roles/Olio Admin` / `~/Roles/Olio User` by `makePath` at `:163-164`. §5.3's isolation argument
requires those calls **not** to fire for book worlds, and an "additive only: `requireRealms`" config
gives `initialize()` no way to select the role-parameterised overload. **§5.7 property 1 is
unimplementable without this.** So the config also gains:

- `BaseRecord authorizationUserRole` / `authorizationAdminRole` (nullable). When both are set,
  `initialize()` passes them to the new role-parameterised `configureWorldAuthorization`; when null it
  uses today's org-wide pair, so grid/arena/agent behaviour is unchanged.
  **These two fields are also the fix for the instance-field trap — see below.**
- `boolean enrolActingUser = false`. *(Corrected 2026-08-12: this line previously read `= true`, which
  contradicted the ratified Q6 row in §0. The default is **false** — safe by default — and every
  existing caller opts in explicitly. See the Q6 staging amendment in §0 for the nine opt-in sites.)*
  When false, `configureEnvironment` skips the unconditional enrolment at **both** `:267-273` (every-run,
  including the warn at `:270-272`) and `:282` (first-run only), so PB2's explicit `POST /members` (§5.4)
  is the only way in.

**The role instance-field trap — must be fixed by construction, not by convention.** `adminRole` and
`userRole` are `OlioContext` **instance fields** (`:125-126`). It is not enough for the role-parameterised
overload to take roles as parameters and leave the fields alone: `configureEnvironment` sets **both fields
unconditionally** before `initialize()` ever reaches `:394-395` (`:248` + `:267-268` on the early-return
branch, `:278-279` on first run). So on a book context those fields hold the **org-wide `~/Roles/Olio
Admin` / `Olio User`**, and every role-less public entry point silently acts on the wrong tier:

- `enroleReader(user)` / `enroleAdmin(user)` (`:128-133`) enrol into the **org-wide** role — precisely the
  self-enrolment §5.1 exists to eliminate. Live callers: `Console7/.../OlioAction.java:299`,
  `OlioTestUtil.java:376`.
- `scanNestedGroups(cfgWorld, fieldName, userWrite)` (`:206-209`, reading the fields at `:217-218`) grants
  the **org-wide** role CRUD on the book world's groups. Live caller:
  `Service7/.../OlioService.java:211` (the Gallery grant), plus six `OlioAction` sites.

A null field would have thrown and been noticed; an org-wide role **grants**, silently, in the
isolation-losing direction. **Fix:** the role-less overloads resolve
`config.getAuthorizationUserRole()/AdminRole()` first and fall back to the instance field only when those
are null. Grid/arena is then bit-for-bit unchanged (config fields null), and book contexts are correct
**even when reached through the legacy overloads**. This is the same shape as the "per-org value in
process-global state" rule in `architecture.md` — a per-scope value read from a field a different scope
wrote — and is worth adding there as its own rule line.

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

#### RATIFIED 2026-08-11 — listing universes is fine. PARTLY SUPERSEDED 2026-08-12 on the mechanism.

> **Reconciliation, so this section is not read as current in full.** The *authorization scope* ruling below
> stands and is final: `Olio User` gets Read at the top so universes can be listed, and book **existence**
> being org-wide discoverable is intended (Q14 closed) — see also the root-reference principle in §5.6b,
> which explains *why* an explicit entry-point grant is the mechanism for reachability.
> **What does NOT stand** is this section's claim that **role hierarchy is the inheritance mechanism** and
> that grants therefore "only need to be written at the book tier" (which it also credited with resolving
> Q10 scale). That was superseded by the 2026-08-12 analysis further down: the `parentId` axis and the
> role-member-of-role axis are different things, AM7's live path resolves only the former, and PB2's
> two-tier need produces a **cycle** if expressed by membership edges. **Primary design is explicit grants
> at both tiers** (§5.3 point 2, decision 8 in §0) — so Q10's scale cost is real and bounded, not removed.

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

#### PROPOSED DESIGN (Stephen, 2026-08-12) — overload the hierarchy check for role membership

> *"For role-axis I had started to implement it — TestAuthorization and AuthorizationSchema — but I think
> a simpler way might just be duplicate/overload the parent hierarchy check for role membership check, so
> it would be similar, look for the roles which the child role is a member of."*

**This is the right instinct and it should be the design.** It avoids everything wrong with the SQL path
that was started: `roles_to_leaf` is **undefined in AM7** (an AM6 artefact), the materialised views it
feeds are **read by no Java code**, and `refreshMaterializedViews()` has exactly one caller
(`Console7/AdminAction.java:193`, a manual admin action) — so that path needs a function that doesn't
exist plus a refresh nobody triggers. An in-Java walk needs neither.

**The pattern is a participation-table join, not a recursive walk** (Stephen, 2026-08-12: *"I don't know
if that role-role member check is there but the pattern should be using the participation table join"*).
An earlier draft of this section proposed recursing in Java, one query per level — wrong shape, and it
reintroduces exactly the per-level cost that got the old design dropped.

**What exists today.** Verified: `auth.role` inherits `common.nameId`, `common.path`, `common.parent`
(hence the `parentId` tree) and declares `dedicatedParticipation: true`, but has **no declared
member/roles list field**. So role→role membership is *representable* generically —
`ParticipationFactory.getParticipantModel` takes `participantModel` straight from the actor's schema
(`:21-30`), so `MemberUtil.member(owner, containerRole, memberRole, null, true)` writes
`participationModel = auth.role` / `participantModel = auth.role` with no special-casing. **The storage
side already works; nothing consults it.** What blocks the read is `findMembers` filtering
`participantModel` from the *actor's* schema (`MemberUtil.java:75, 91-93`), so a `participantModel =
auth.role` row can never come back for a `system.user` actor.

**The join.** Both facts live in the same (role-dedicated) participation table and share a column — the
container role id — so it is a single **self-join on `participationId`**:

```
-- "actor reaches role R because R is a member of some role S that actor belongs to"
FROM <roleParticipation> P1                       -- R is a member of S
JOIN <roleParticipation> P2                       -- actor is a member of S
  ON P2.participationid = P1.participationid      -- ...the same S
WHERE P1.participantid = :roleId  AND P1.participantmodel = 'auth.role'
  AND P2.participantid = :actorId AND P2.participantmodel = :actorSchema
  AND P1.participationmodel = 'auth.role'
  AND P1.organizationid = :orgId
```

One indexed join, no recursion, no CTE, no `roles_to_leaf`, nothing to refresh.

**Idiomatic AM7 formulation (preferred over raw SQL, since `ColorUtil.java:167-200`'s raw-SQL read is
already flagged as a PBAC-bypass smell):** express it as two indexed `Query`s using the existing
`ComparatorEnumType.IN`, which is the same join executed in two steps:

1. `S` = the actor's direct role ids — `participation WHERE participantId = actor.id AND participantModel
   = actor.schema AND participationModel = auth.role`, projecting `participationId`.
2. Permit if `R.id ∈ S`, **or** `exists participation WHERE participantId = R.id AND participantModel =
   auth.role AND participationId IN S`.

Step 1's result is also worth reusing — it is the actor's role set, which `checkEntitlement` currently
re-derives per permission per object.

**Where it hooks in.** `AuthorizationUtil.checkEntitlement:234-244` already calls
`isMember(actor, role, null, true)` for every role-attached entitlement, so adding the join beneath that
call makes it live everywhere without touching call sites — which is also why it is a **core-PBAC
semantics change** (see point 5 below).

##### Five things the design must get right

1. **Direction — CORRECTED 2026-08-12; an earlier draft of this section stated it wrongly.**
   The rule, taken from the one live writer of role-in-role data in the codebase, is:

   > **A user in role X receives the entitlements of every role that X is a member of.**

   `ISO42001Provisioning.grantRoleToRole(adminUser, parentRole, memberRole)`
   (`AccountManagerISO42001/.../schema/ISO42001Provisioning.java:102-114`) is called as
   `grantRoleToRole(adminUser, accountUsersReaders, certifiers)` (`:83-84`) — i.e. `certifiers` is made a
   **member of** the broad system role `accountUsersReaders`, with the entitlement sitting on
   `accountUsersReaders`, so that a user in `certifiers` inherits read on `system.user`. Its own comment
   (`:78-83`) states the purpose: *"Without this, a legitimate certifier's MODIFY is AUDIT-DENIED."*

   So the join must resolve, from the **granted** role, the set of roles that are **members of** it, and
   ask whether the actor belongs to any of them. Equivalently and more usefully for implementation:
   resolve the actor's role set, then close it upward over "is a member of". The earlier draft asserted the
   opposite edge and labelled this one privilege escalation — that was wrong, and it is the shape the
   codebase already relies on.

2. **The cycle problem is real for PB2, and this is the finding that changes the recommendation.**
   An earlier draft claimed a fixed single level makes cycles moot. It does not, because PB2 needs **both**
   edges between the same pair of roles:
   - `bookRole` ⊂ `universeRole` — so book members can read the universe corpora (the two-role
     requirement in §5.3; entitlement on the universe tier). Same shape as ISO's usage.
   - `universeRole` ⊂ `bookRole` — so universe members can read **every** book (entitlement on the book
     tier).

   Together those are a **cycle at depth 1** between `universeRole` and `bookRole` — precisely the
   condition the "fixed depth is safe" argument assumed away. Any traversal must therefore carry a visited
   set regardless of depth, and the two-tier model cannot be expressed by membership edges alone without
   creating that cycle.

   **Consequence — recommendation changed.** PB2's primary design stays **explicit grants at both tiers**
   (the fallback below): no cycle, no core-PBAC change, and PB2 does not become coupled to a change that
   alters ISO42001's authorization the moment it lands. The membership join remains worth building **on its
   own merits and on ISO's timeline** — see the finding below — but it is no longer a PB2 dependency, and
   PB2 must not be sequenced behind it.
3. **Cost — and the index the join needs already exists, by design.** `system/participationModel.json`
   declares three composite `hints`, and the middle one is exactly the inverse-direction lookup:
   ```json
   "hints": [
       "participationId, participationModel",                                   // members OF x
       "participantId, participantModel",                                       // what x is a member OF
       "participationId, participationModel, participantId, participantModel"
   ]
   ```
   Both sides of the self-join seek on `(participantId, participantModel)`, and the join column
   (`participationId`) is covered by hints 1 and 3. **This carries to `auth.role`'s dedicated table:**
   `auth.role` declares `dedicatedParticipation: true`, and `DBUtil.java:481-483` generates that table from
   the `system.participation` schema, with `generateIndices(baseSchema, schema)` (`:479`) building indexes
   from that schema's hints — so the dedicated role participation table gets all three. The participation
   table was evidently designed to be queried in both directions; this design uses a capability that is
   already paid for, which is the strongest argument for it over the SQL/materialised-view route.
   One real caveat remains: `checkEntitlement` runs this **per permission per object** in its existing loop
   (`:212-248`), so the actor's role set from step 1 must be resolved once and reused rather than
   re-derived inside the loop.
4. **Both axes on at once = union semantics.** A role tree that *also* nests by `parentId` would get both
   walks. For PB2, pick one: **flat role paths at the group path (Rocket style) with explicit membership
   links**, so exactly one mechanism is in play and the grant set is predictable.
5. **This is a core-PBAC semantics change, not a PictureBook change.** Because `checkEntitlement` passes
   `browseHierarchy=true` unconditionally, turning this on changes authorization for **every** role-attached
   entitlement in the system — grid, arena, game, ISO, everything. So it needs the existing authorization
   test suites as its gate, and it is worth landing behind a flag so it can be switched off without a
   redeploy if it misbehaves.

##### SUSPECTED DEFECT (inference from code, NOT yet verified) — ISO42001's role-to-role wiring may be inert

Recorded here because it is the strongest justification for building the join, and because it must not be
repeated as fact until a test confirms it.

**The claim.** `ISO42001Provisioning` wires six role-to-role memberships (`:73-76`, `:83-84`) —
`certifiers`/`admins` into `requestUpdaters`, `approvers`, and `accountUsersReaders` — intending users in
the ISO roles to inherit those roles' entitlements. But with a **user** actor,
`AuthorizationUtil.checkEntitlement:234-244` calls `isMember(actor, grantedRole, null, true)`, and
`MemberUtil.isMember:226-247` only (a) looks for a participation whose `participantModel` matches the
**actor's** schema, and (b) walks the granted role's **`parentId`** chain. Neither reaches a
`participantModel = auth.role` row. **So the wiring appears to grant nothing.**

**Why it looks correct in code but may not work.** `grantRoleToRole`'s own idempotency guard at `:107`
calls `isMember(memberRole, parentRole, null)` where the actor **is a role** — so `participantModel`
matches `auth.role` and that check succeeds. The write and its guard both behave; only the
user-authorization read fails to consult the edge.

**What that would mean.** The AUDIT-DENIED condition the `:78-83` comment says this wiring fixes may still
be live for certifiers, and the join would be a **fix for an existing defect**, not merely a PB2
enhancement. It also means enabling the join **changes ISO42001 authorization outcomes** — in the
direction ISO intended, but it is still a behaviour change requiring the ISO suites as its gate.

**How to verify before believing any of this** (do not skip; this is a code-reading inference):
enrol a shared test user in the ISO `certifiers` role only, then attempt the operation the comment names —
a certification-request MODIFY carrying a `requestedCertifier` `system.user` reference — and observe
whether it is AUDIT-DENIED. If denied, the defect is real and reproducible; if permitted, some other path
is already satisfying it and this analysis is wrong. Per §9's environment split this is a **REST/App-layer
check against the `am7test` containers**, not an Objects7 JUnit test.

##### What this buys PB2 (if it is built)

If it lands, the universe tier needs **no grants of its own**: grant each book's groups to the book-tier
roles only, make each book role a member of the corresponding universe role, and universe membership
reaches every book through the walk. That is strictly less grant-writing per book than the explicit
multi-tier alternative below (which remains the fallback and needs no core change), and it removes the
Q10 scale concern entirely. **Recommendation: design for the fallback, implement this if it tests clean** —
that way PB2 is never blocked on a core-PBAC change, and adopting it later is a simplification rather
than a migration.

---

#### FALLBACK / REFERENCE: THE ROCKET PATTERN — needs no core change

Stephen directed me to the original design: Propellant (schema) + Accelerant (logic/DAL) in
`C:\Projects\GitHub\AccountManager`, with `RocketCommunity` as the worked example, noting **Olio Universe
== Rocket Community (Lifecycle)** and **Olio World == Rocket Project**. Read and verified; it supersedes
both the `~/Roles/Olio/Books/...` paths proposed in the table above and the role-hierarchy optimisation
just described.

**Three findings, all verified in code.**

**1. Role paths mirror GROUP paths, not user paths.**
`RocketSecurity.getRoleByGroup(name, parentId, organizationId)`
(`Accelerant/.../rocket/RocketSecurity.java:452-464`):
```java
DirectoryGroupType parent = getDirectoryById(parentId, organizationId);
denormalize(parent); populate(parent);
role = findRole(RoleEnumType.USER, parent.getPath() + (name != null ? "/" + name : ""), organizationId);
```
So `getLifecycleRoleByName(lc, "AdminRole")` resolves a role at **the lifecycle's own group path** +
`/AdminRole` (`:412-417`). This is exactly Stephen's "the roles are built along the same path, not the
user path" — and it is the real fix for "not easy to manually set", because AM7's
`~/Roles/Olio User` resolves under **olioUser's** home (`PathUtil.java:72-79`), which is why no
administrator can find it. Roles belong beside the data they govern.

**2. There is a role *bucket* per container, with named child roles under it.**
`getRoleByGroup(null, groupId, …)` returns the bucket (a role at the group path itself, `:400-411`);
`getRole(name, parentRole, …)` returns a named child role *under* that bucket (`:465-470`). The named set
is `AdminRole, UserRole, AuditRole, ManagerRole, ArchitectRole, TesterRole, DeveloperRole, AuthorRole,
EditorRole` (`:65-73`), partitioned into `readerRoles` and `writerRoles` (`:78-79`).

**3. Cross-tier access comes from EXPLICIT MULTI-TIER GRANTS, not from role-hierarchy inheritance.**
This is the finding that matters most. `setupBulkProjectStructure` (`:191-237`) resolves three buckets —
`rRole` (app/Rocket), `lRole` (lifecycle/community), `bRole` (project) — and then grants **each project
directory to all three**:
```java
setupRolesToReadContainer(adminUser, rRole, readerRoles, dir);
setupRolesToEditContainer(adminUser, rRole, writerRoles, dir);
setupRolesToReadContainer(adminUser, bRole, readerRoles, dir);
setupRolesToEditContainer(adminUser, bRole, writerRoles, dir);
setupRolesToReadContainer(adminUser, lRole, new String[]{ROLE_USER,ROLE_AUDIT,ROLE_MANAGER,ROLE_TESTER,ROLE_ARCHITECT,ROLE_DEVELOPER,ROLE_AUTHOR,ROLE_EDITOR}, dir);
setupRolesToEditContainer(adminUser, lRole, new String[]{ROLE_ADMIN}, dir);
```
`setupRolesToReadContainer`/`setupRolesToEditContainer` (`:120-151`) iterate the named roles under the
given bucket and add a role→group participation granting view (read) or view/edit/delete/create (write).

So **"membership in a first-tier role gives access to all books in that universe" is produced at
creation time by granting every project container to the lifecycle tier's roles** — not by an
evaluation-time hierarchy walk. Note the asymmetry the original chose deliberately: the lifecycle tier
gets **read** for the eight reader-ish roles but **edit only for `ADMIN`**.

##### This resolves Stephen's open concern

> *"the code to calculate role inheritance was far more complicated/flexible in the original while the new
> version kept it simpler but might prevent this from working at the PBAC level unless the role hierarchy
> check is extended (maybe)"*

**No extension is required for the tier semantics.** Rocket never obtained them from role hierarchy — it
obtained them from explicit grants to the parent tier at container-creation time. That works against
AM7's entitlement resolution exactly as it stands, including the exact-`groupId` match at
`effectiveGroupObjectEntitlementTemplate.sql:10` and with no reliance on group recursion (which the
architect confirmed does not exist). **This is strictly better news than the hierarchy plan it
replaces**: it needs no new PBAC capability, and it cannot fail in the isolation-losing direction,
because a missing grant denies rather than over-permits.

Role hierarchy *is* still used in Rocket, but for a different question: `getIsUserInEffectiveRole`
(`RoleService.java:371-393` → `EffectiveAuthorizationService.getIsActorInEffectiveRole`) gates **who may
enrol someone** — e.g. `enrollInCommunityProject` checks
`getIsUserInEffectiveRole(getProjectAdminRole(proj), adminUser)` (`RocketCommunity.java:1186`), and
`enrollInCommunityLifecycle` checks the lifecycle admin role (`:1143`), each falling back to
`isFactoryAdministrator`. That is authorization *of the enrolment operation*, not of data reads. Keep the
two questions separate; conflating them is what produced the earlier confusion.

##### The resulting PB2 role design (replaces §5.3's table)

```
/Olio/Universes/Books                        <- group;  role bucket at the same path
    AdminRole, UserRole                          <- named child roles (universe tier)
/Olio/Universes/Books/Worlds/{bookSlug}      <- group;  role bucket at the same path
    AdminRole, UserRole                          <- named child roles (book tier)
```
Every group of a book world is granted to **both** tiers at creation time: the book tier's `UserRole`
(read) / `AdminRole` (write), **and** the universe tier's `UserRole` (read) / `AdminRole` (write). Then:
- universe `UserRole` membership ⇒ read **every** book in the universe;
- book `UserRole` membership ⇒ read **only that** book;
- universe `AdminRole` ⇒ administer all books; book `AdminRole` ⇒ administer one.

This is Stephen's stated semantic, implemented by the mechanism the original used.

##### Enrolment, and the KI-35 fix

`RocketCommunity`'s enrol methods are the template for the explicit registration that replaces the
auto-enrolment removed in §5.4 — and they show what a correct one looks like:
`enrollReaderInCommunity` / `enrollAdminInCommunity` / `enrollReaderInCommunityProject` /
`enrollAdminInCommunityProject` (`RocketCommunity.java:1043-1130`), each of which (a) opens an **audit**
(`AuditService.beginAudit`), (b) resolves the target user by objectId, (c) **checks the caller is an
admin of that tier** via `getIsUserInEffectiveRole`, (d) delegates to a private
`enrollInCommunityLifecycle`/`enrollInCommunityProject`, and (e) records permit/deny. That is the shape
PB2's `registerUser` and `POST /{book}/members` should take — audited, tier-scoped, admin-checked —
rather than an unconditional `member()` call inside context construction.

##### Still to extract before implementing

- **The "navigate the parent" special consideration.** Stephen flagged that special care is taken to let
  a user traverse the parent when they otherwise lack access. The lifecycle-tier read grant on project
  dirs (`:217`) is part of it, and `Rocket.getBasePath()`-level grants are likely the rest, but **I have
  not fully traced this path and am not going to claim I have.** It matters directly: without it, a book
  a user *can* read may be unreachable because an ancestor group denies traversal. Extract it from
  `Rocket`/`RocketSecurity` before building the grant sequence.
- **`Rocket.enrollInCommunityLifecycle` / `enrollInCommunityProject`** (the private implementations the
  public methods delegate to) — what participations they actually write, and whether a permission object
  is required alongside the role (both public methods resolve
  `AuthorizationService.getViewPermissionForMapType(NameEnumType.GROUP, …)` and pass it in, which has no
  direct AM7 analogue and needs mapping).
- **Whether the 9-role set should be reduced.** PB2 plausibly needs only `AdminRole`/`UserRole` (and
  perhaps `AuthorRole`/`EditorRole`), not all nine. Reducing it is a judgment call — flagged as
  **§10 Q20** rather than assumed.
- **`getIsActorInEffectiveRole`'s actual AM7 equivalent** and whether it walks the role tree, which
  determines whether a universe `AdminRole` member automatically passes the book-tier admin check when
  enrolling someone into a single book. Test it; don't infer it.

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
- **Scene-addressed endpoints never authorize the owning book.** ~~`generateSceneImage` (`:3277`),
  `regenerateBlurb`/`setSceneStatus` (`:3879`) and `prepare-images` (`:3840`)~~ Each must resolve the
  scene's book and re-authorize.
  **FIXED 2026-08-14, with two corrections to this entry.**
  *(a) The line numbers are in `PictureBookUtil.java`, not `PictureBookService.java`* (which is only 722
  lines). Actual pre-change locations: `generateSceneImage` find at `:3277`, `regenerateBlurb` find at
  `:3879`, `prepareSceneImagePrompts` find at `:3840`, and **`setSceneStatus` find at `:1328`** — *not*
  alongside `regenerateBlurb` as this entry grouped it.
  *(b) The "direct object reference" framing overstates the PB1 blast radius — measured, not assumed.*
  An **unentitled** user is already denied at the *note* level: `AccessPoint.find` on another user's scene
  note returns null, so the request 404s. In PB1's single-owner `~/Data/PictureBooks/...` layout an
  arbitrary authenticated user could **not** drive someone else's book.
  **The real gap** is a user holding Read+Update on the book's **`Scenes` group** but nothing on the
  **book group** — reachable today via any explicit group grant, and **exactly the shape PB2's shared
  world creates**. That user could read and write another user's scene notes; the test asserts the
  precondition directly (`AccessPoint.update` on A's note succeeds for B) before asserting the fix. Now
  403.
  **Implementation:** `PictureBookUtil.authorizeSceneAccess(user, sceneObjectId, READ|WRITE)` beside
  `findBookGroup` — resolves scene `groupId` → group → `parentId` → book group by **id-based
  `AccessPoint.findById` at every hop, never path resolution** (§5.6b: there is no read-up), checks via
  `AuthorizationUtil.canRead/canUpdate`, and **returns the scene** so callers don't re-query. Scenes not
  under a `Scenes` group (the legacy `~/Chat` single-image fallback) authorize against their own group —
  the check is never skipped. In the `prepare-images` batch the authorize call sits **outside** the
  per-scene `try/catch`, which exists to tolerate LLM failures; swallowing a denial there would turn
  "you may not act on this book" into a silent 200.
  **Status codes follow this file's existing convention** rather than a new one: `findBookGroup` already
  collapses absent and PBAC-denied into 404, so unreadable/absent scene → **404**, scene readable but book
  denies → **403** (which leaks nothing — a caller who can read the scene already knows its book exists).
  **Service7 gained zero authorization logic.**
  **Related, NOT fixed — follow-up:** `tagApparelSceneIndex` (`PictureBookUtil:1171`,
  `PUT /character/{objectId}/apparel/{apparelObjectId}/scene-tag`) resolves an apparel record by objectId
  with **no book check** either. Same shape, out of scope for that patch.
- **`cancel` discards the principal.** `PictureBookService.java:475-476`:
  ```java
  ServiceUtil.getPrincipalUser(request);            // :475 — return value discarded
  SummarizeProgress progress = cancelRegistry.get(key);  // :476
  ```
  The registry is a static process-wide map (`:85`) keyed by a client-supplied path param, so **any
  authenticated user can cancel any other user's in-flight extraction.** Today those ids are hard to
  obtain; under PB2 world browsing makes them discoverable. Key by `(principal, key)` and check
  ownership. *(This is a pre-existing defect worth fixing regardless of PB2.)*

### 5.6b The root-reference principle (Stephen, 2026-08-12) — there is no "read up"

> *"A basic tenet of parent/child and groupId based authZ is all access is denied unless owned or admin.
> This is why a user is given root references like their home group/role/permission as they don't have
> access to read up. For Olio, a similar concept applies where the Olio root reference is needed or found
> by path because a user shouldn't have access to read the root '/'."*

This corrects a wrong assumption carried by earlier drafts of this plan. I had recorded "navigate the
parent" as an unтraced *traversal grant* — something Rocket must be doing to let a user walk an ancestor
chain — and listed it as a risk that a readable book might be unreachable because an ancestor denies
traversal. **That inverts the model.** Traversal upward is never granted; it is denied by default and
stays denied. What makes anything reachable is that the user is handed a **root reference** — their home
group/role/permission — from which they navigate *downward*.

**Consequences for PB2, and they are simplifying:**

1. **No traversal grants are needed on `/`, `/Olio`, or `/Olio/Universes` for a user to reach a book.**
   The earlier draft's worry about ancestor denial is void. Remove it from the risk list.
2. **The book record's foreign keys *are* the root reference.** §3.1b already has `olio.pb.book.world` →
   `olio.world`, and `olio.world` carries all 36 group references as foreign fields. So resolving a book's
   storage is: book → `world` FK → group ids, **by direct reference, never by path traversal**. This is
   the mechanism that makes "universe/world hidden inside PictureBook" work, not merely a convenience.
3. **Therefore book reads must resolve by id, not by resolving a path as the acting user.**
   `PathUtil.findPath(owner, …)` resolves each segment as the passed principal, so a path-based lookup by
   the acting user would demand exactly the read-up that does not exist. Two acceptable shapes: resolve by
   FK from the book record (preferred), or resolve the path as `olioUser` and then authorize the *target*
   through `AccessPoint`. **Never** resolve a book path as the acting user and treat success as
   authorization — that conflates traversal with permission.
4. **Listing books needs its own root reference, and that is what the universe-tier grant is for.**
   Stephen's earlier ratification — *"it should get read access to the top to list universes — then role
   inheritance kicks in"* — is this same principle: the entry point is granted explicitly so a listing can
   render, and nothing above it is readable. §5.3's universe-tier Read on `/Olio`, `/Olio/Universes` and
   the `Books` container is therefore **the root reference for books**, deliberately, not an over-grant.
   `configureEnvironment:299` already establishes exactly this pattern for the org-wide Olio role.
5. **Book existence being discoverable is a property of the design, not a leak** (§10 Q14, now closed):
   the entry point is readable so books can be listed; book *content* is gated by the per-book roles.

**What this means for the grant sequence** (§5.3 step 4): grants are needed on the book world's own groups
and on the universe entry point — and **not** on the intermediate path. That is fewer grants than the
earlier draft assumed, and it removes the phase-1 item "extract Rocket's navigate-the-parent handling
first", which was chasing a mechanism that does not exist.

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
- **~~Dropping `setPermitBulkContainerApproval` is not a one-liner.~~ CORRECTED 2026-08-12 — it *is* a
  one-liner, and this bullet was wrong.** Verified by reading `AccessPoint.java`: `:159-161`
  `update(contextUser, BaseRecord[])` is the **only** reader of the field, and `:152-153`
  `create(user, BaseRecord[])` delegates to it. The singular `update(user, BaseRecord)` (`:275`) and
  `create(user, BaseRecord)` (`:317`) never touch it. Enumerating all 87 `getAccessPoint().create|update(`
  sites in Objects7 `src/main`, exactly four use the array form: `io/Queue.java:66,69`,
  `parsers/data/DataParseWriter.java:31` and `parsers/geo/GeoParseWriter.java:66` **already pass `true`**;
  `parsers/data/WordParser.java:289` (`loadTraits`) is the sole one that does not, and is therefore the
  only init-path call that consumes the flag. **So: change `WordParser.java:289` to pass `true`, matching
  its two sibling writers, then delete the four `setPermitBulkContainerApproval` calls
  (`OlioContextUtil.java:43/:83`, `:91/:119`). No signature threading, no loader performance change.**
  State honestly that hardcoding `true` at `:289` widens bulk approval for `loadTraits` to every caller,
  not just Olio init — the same unconditional shape the two sibling writers already have.
  **Gate caveat:** the test harness itself turns the flag on and mostly never turns it off
  (`OlioTestUtil.java:94`, `TestOlio.java:66,218`, `TestOlio2.java:137`, `TestGameUtilSync.java:550`,
  `TestOlioGameFeatures.java:529`, `TestRealm.java:21,136`), so §9's `TestBookWorld` assertion 9 is
  order-dependent in a shared JVM and will flake unless `TestBookWorld` sets it false in setup or runs
  isolated.
- **`CacheService` is not a one-line hook.** `clearCaches()` is a no-arg static (`CacheService.java:63-69`)
  reachable from `GET /cache/clearAll` with `@RolesAllowed({"admin","user"})`. A targeted
  evict cannot hang off it, and wiring context eviction there would let any
  authenticated user drop every cached Olio context process-wide. Add a distinct, admin-gated
  targeted-evict path, and say which one is meant.
  **Correction 2026-08-12:** the warning above describes a state that **does not exist yet** — it would be
  *created* by the proposed wiring. Today `OlioUtil.clearCache()` (`:67-69`) delegates **only** to
  `ProfileUtil.clearCache()`; it clears neither `dirNameCache` nor any Olio context. And
  `OlioContextUtil.clearCache()` (`:27-29`) has **zero callers repo-wide**. So `/cache/clearAll` currently
  drops no Olio context at all.
  **Wire contract:** the targeted-evict endpoint must take an **objectId**, derive `organizationId` from
  `ServiceUtil.getPrincipalUser(request)`, and delegate to **one** Objects7 facade method that assembles
  the cache key internally. An endpoint taking `(orgId, userName, universeName, worldName)` puts
  key-shape logic in Service7, lets an admin name another org's `orgId` (the exact tenant boundary
  Blocker 2 defends), and bakes the phase-1 *name* key into a wire contract that phase 1b must then break.

**Phase 1b — Thread universe/world ids through Service7 + Ux752** (the ratified direction in §4
Blocker 2). Optional `universeObjectId`/`worldObjectId` on every endpoint that constructs an Olio
context, defaulting to the current pair; a current-world selection in the Ux that survives navigation
and is sent on every Olio call; the hardcoded `/Olio/Universes/My Grid Universe/Worlds/My Grid World`
in `games/wordGame.js:16` becomes the default rather than a literal.
*Exit:* existing game/Olio e2e specs green with no ids supplied (proving the default path), plus a new
test that two different world ids from one user in one session yield two different worlds' data.

**Phase 2 — Persisted models + graph utilities (Objects7).** Split into 2a-2d during implementation,
because the authorization diff has a different regression baseline from the model diff and because
constraints/hints are irreversible once a table exists (see Appendix D). Sub-phases in order:

- **Phase 2a — two-tier role split + recursive world grant. DONE 2026-08-14.**
  `universeAuthorizationUserRole`/`AdminRole` (null-default, so grid/arena keep the org-wide pair);
  `OlioContext.scanNestedWorldGroups()`; `registerUniverseUser`; both-tier creator enrolment; two-role
  `verifyGrants`. Done first and alone because it is authorization-only, its regression baseline is the
  existing green gate, and the universe tier had to exist before the two-role property could be asserted.
  *Verified:* `TestBookWorld` 21/21 + the 83-test gate. As-built under ratification 5; measurements in
  `PictureBook2ImplementationState.md` §3.
- **Phase 2b — the eight `olio.pb.*` model JSONs, registered, with their tables and indexes verified.**
  The JSONs; the `OlioModelNames`/`OlioFieldNames` constants; the new enums; the one-line
  `SDAPIEnumType.COMFY` addition (ratification 12); registration in `OlioModelNames.MODELS`.
  *Exit:* the eight tables exist (`DBUtil.getTableName`) and every declared constraint and hint appears in
  `pg_indexes`. **The earlier 2b/2c split — write-but-don't-register plus a DDL pre-flight test — was
  withdrawn**: it existed only because Appendix D said constraints and hints could never be added after
  the table exists, which phase 1's own `generatePatchIndices` fix had already made false, and `am7db` is a
  resettable container besides. Verify the indexes landed; don't build ceremony to avoid needing to.
- **Phase 2c — the utilities** (was 2d; 2c's register-and-verify step folded into 2b above).
  `PbConfigUtil`, `PbWatchedFields`, `PbGraphUtil` (build /
  `validateAcyclic` / `computeInputHash` / `markStaleDownstream` / `recomputeStatus` (compute-only, see
  ratification 2) / `nextRunnable`), `PbArtifactUtil` (persist + sanitize + supersede chain +
  `setSelected` with a post-write re-read), `PbBookUtil`, `PbSharingUtil` (promote/copy).
  *Exit:* `TestPbGraph` green (including the `planMost(true)`-terminates case for the
  `workflow.lastRun` ↔ `run.workflow` cycle), `TestPbSecurity` green, and the role-hierarchy direction
  test run with its result recorded in Appendix D.

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

### Which backend each test layer runs against (Stephen, 2026-08-12)

This is a hard constraint, not a preference, and an earlier draft of this plan said only "live backend",
which is not specific enough to act on:

| Layer | Target | Why |
|---|---|---|
| **Objects7 JUnit** (`TestBookWorld`, `TestPbGraph`, `TestPbSecurity`'s Objects7-level assertions, `TestPictureBookCustom`) | the **dev** Postgres — `am7db`, **host port `15432`** (see the correction below) | **Key location dependency.** The vault/keystore location is tied to that store, so Objects7 tests cannot be repointed at a *different* database — in particular **never at the `am7test` container DB**, whose keys will not match. Changing the *port* to reach the same `am7db` is fine and expected. |

**Port — RE-CORRECTED 2026-08-14, read this and not the 2026-08-12 note it replaces.** The committed
`AccountManagerObjects7/src/test/resources/resource.properties:9` reads
`jdbc:postgresql://localhost:15432/am7db`, it is not modified in the working tree, and the `postgres`
container (`pgvector/pgvector:0.8.2-pg18-trixie`) publishes `0.0.0.0:15432->5432` — so **15432 is correct
and every phase-1/2a test run used it.** The 2026-08-12 note claimed the file had been repointed to
**15430** because nothing listened on 15432; whatever was true that day, it is not true now, and the
15430 value was propagated into `PictureBook2ImplementationState.md` §6 and repeated in a verification
report before anyone re-read the file. A second container `am7-pg` publishes `15433`.
*Do not* work around a connection failure with a port forwarder or by pointing at the `am7test` stack —
check the file, then `docker ps`. `AccountManagerConsole7`'s `resource.properties:11` reads
`15432/am72db`: **same host and port, different database, and `am72db` must never be reset or dropped.**

### Running the gate — the suites are excluded in the pom (discovered 2026-08-12)

**`AccountManagerObjects7/pom.xml` sets `<skipTests>true</skipTests>` (`:19`) and excludes 154 test
classes (`:108-297`).** Two consequences that invalidate the gate as §7/§9 originally wrote it:

1. **`mvn … test` alone reports `BUILD SUCCESS` having run nothing** (`Tests are skipped.`). Always pass
   `-DskipTests=false`, and **always confirm a `Tests run: N` line with N > 0** — a bare `BUILD SUCCESS`
   is not evidence.
2. **Every Olio/game/arena suite the phase-1 gate depends on is excluded** — `TestOlio` (`:241`),
   `TestOlio2` (`:240`), `TestOlioRules` (`:218`), `TestOlioGameFeatures` (`:202`), `TestGameUtil`
   (`:200`), `TestGameUtilSync` (`:199`), `TestRealm` (`:244`), `TestNestedStructures` (`:245`), plus
   `TestLandscape`, `TestSD`, `TestPictureBookUtilE2E` and `TestPictureBookCustom`.
   **`TestPictureBookKnownIssues` is not excluded and runs normally.**

**An exclude cannot be overridden from the command line.** Verified: both `-Dtest=TestOlio#TestGrid` and
`-Dsurefire.excludes=…` yield `Tests run: 0, BUILD SUCCESS`, because the `<excludes>` configuration is not
bound to a user property. To run an excluded suite you must **comment out its `<exclude>` line in
`pom.xml`**. Restore the pom afterwards (`git checkout -- AccountManagerObjects7/pom.xml`) unless the
un-exclusion is intended to be permanent.
| **REST / App / Ux** (Playwright, raw REST checks, `TestPictureBookRestContract`) | the **containers** — `am7test` stack per `DockerComposeDesign.md` (`docker compose -p am7test -f docker-compose.test.yml`), app `9443`, pg `15433`/`am72db`/`am7user` | Isolated, and **resettable/rebuildable on demand**, which the dev DB is not. |

Corollary for the PB2 work: **the standing "never reset the schema" rule still holds for `am7db`**, but the
`am7test` stack is explicitly disposable (`down` + `rm -rf ./docker-data` for a full reset, per
`DockerComposeDesign.md:108-109`). So any test that genuinely needs a virgin org — e.g. proving that a
first-ever book creation grants correctly, or that a failed creation leaves no orphan world (§3.1b) —
belongs on the **container** side, not in Objects7 JUnit.

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
- **No *new* Delete grant naming a PB role exists on `/Library/*` after N book creations.**
  *(Corrected 2026-08-12. The original assertion — "no role holds Delete on `/Library/*`" — cannot pass on
  `am7db`. `setEntitlement` only **adds**: `OlioContext.java:185,200` has been granting the org-wide user
  role `{Read,Update,Create,Delete}` on `/Library/*` on every grid/arena run, nothing revokes, and `am7db`
  is deliberately not resettable. So the original form would fail for a reason unrelated to the change.
  Narrowing the grant to CRU is **not retroactive** — every existing org stays over-granted until a
  separate revoke utility runs. State that; do not let the narrowing read as a repair. A genuinely
  virgin-org assertion of the original form belongs on the resettable `am7test` container side.)*
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
- ~~**Drop `"group": "<fallback name>"`.**~~ **WRONG — corrected 2026-08-14 (Stephen). Keep the hint.**
  The model-level `"group"` is **a name hint for where an instance should be saved relative to its
  parent** — it is not a field, and **57 models in `resources/models/` declare one**. Removing it from
  PB2 would make these eight inconsistent with the rest of the schema for no benefit.
  **The defect §2.1 Rule 2 is actually about is caller-side:** synthesizing a path as
  `"~/" + schema.getGroup()`, which prefixes the *acting user's home* onto a relative name hint. That is
  what produced the KI-60 collision target. Live instances: `PictureBookUtil.java:2169` and `:2190`
  (deleted in phase 3) and `CharPersonFactory.java:35, :44-50`.
  **Rule for PB2:** declare `"group"` freely as a relative hint; **never** build a path from it, and
  always pass an explicit world-scoped path on every create. The thing to verify is that no PB2 code
  contains `"~/" + …getGroup()`, not that the hint is absent.
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

## Appendix D — Phase 1 as-built (2026-08-12)

Phase 1 is implemented. Deviations from the plan, and defects the plan itself contained, recorded here so
phase 2 builds on what exists rather than on what was drafted.

**Defect in the plan's own grant call — §5.3 step 4 is wrong.** It specifies
`configureWorldAuthorization(world, bookReader, bookWriter, true)`. On a `userWrite=true` call the
*user-role* argument receives full CRUD **including Delete**, so passing `Reader` there would grant a role
literally named *Reader* delete rights on the whole book world. As-built creates
`~/Roles/Olio/Books/{slug}/Writer` (→ `authorizationUserRole`) and `~/Roles/Olio/Books/{slug}/Admin`
(→ `authorizationAdminRole`). **No `Reader` role is created in phase 1** — nothing would grant to it or
enrol into it yet. Phase 2 must add the Reader tier deliberately, not by re-reading §5.3 step 4.

**Open isolation gap for phase 2 — one role pair serves both tiers.** `initialize()` passes the *same*
role pair to the universe call and the world call, so a book's `Admin` role receives CRUD (incl. Delete)
on the **universe's own non-shared groups**. §5.3 wants a distinct `Books Reader`/`Books Writer` tier
there. Closing it needs either two role pairs on `OlioContextConfiguration` or a second explicit call;
the ratified config was pinned to exactly two role fields, so this was implemented as specified and
flagged rather than silently widened. **Phase 2 must close it.**

**Grant-target widening (intended, but a real grid/arena change).** `resolveGrantTargets` collects the
world's foreign `auth.group` fields **including non-shared ones**, which the old code discarded. Where a
foreign group field points outside the world container, that group is now granted.

**`/Library/*` narrowing, precisely.** Shared groups get `userWrite ? CRU : Read` for the user role and
`CRU` for the admin role — never Delete. A flat "always CRU" would have *widened* the universe call, which
grants only `Read` today. **Not retroactive:** `setEntitlement` only adds, so existing orgs keep their
`Delete` until a revoke utility exists.

**Additions not in the plan, both required:** `OlioContext.getAuthorizationGroups(cfgWorld, containerPath)`
(public — `resolveGrantTargets` is private and `PbOlioContextUtil` is in another package, but the
post-init check must verify the *whole* group set, not one sampled probe); and
`OlioContext.OLIO_USER_NAME` (the olio principal must be resolved before `initialize()` so the book roles
`makePath` under the right owner and `~` expands correctly).

**The container group itself is not a grant target** — only its children, matching today's behaviour.
One line in `resolveGrantTargets` if that turns out to be wrong.

**Dead test classes — the gate is smaller than §7/§9 assumed.** `TestOlio`, `TestRealm` and `TestSD` have
**every** `@Test` inside `/* */` blocks (`TestSD` also still uses a removed 10-arg constructor). They
contribute zero coverage. The live phase-1 gate is `TestGameUtil` (25), `TestGameUtilSync` (15),
`TestOlioGameFeatures` (15), `TestNestedStructures` (3), `TestOlio2` (1), `TestOlioRules` (1) = **60**,
plus `TestPictureBookKnownIssues` (15) = **75 total, 0 failures** as of 2026-08-12.
Consequently there were only **four** live `setEnrolActingUser(true)` opt-in sites, not nine:
`OlioTestUtil.java:97`, `TestGameUtilSync.java:551`, `TestOlio2.java:138`, `TestOlioGameFeatures.java:530`.

**Six `<exclude>` lines in `AccountManagerObjects7/pom.xml` are commented out as `PB2-GATE`** so the gate
can run. Restore them (`git checkout`) if the gate is retired. **Better mechanism, flagged for Stephen:**
commented-out build config is a forgettable manual step; a surefire **profile** (`-Ppb2-gate`) that removes
the excludes would leave the default build unchanged. As it stands, six live-DB integration suites
(including the slow `TestGameUtil`/`TestGameUtilSync`) now run in the default `mvn test` for everyone.

### Phase-2 preconditions — these debts become defects if phase 2 does not honour them

Recorded from the architect's final sign-off. Each is safe *today* only because of a condition phase 2
can silently remove.

> **STATUS 2026-08-14 — preconditions 1 and 2 are CLOSED by phase 2a.** The role pair is split
> (`universeAuthorizationUserRole`/`AdminRole`, null-default) and nothing auto-enrols into either admin
> role; the nested-grant write gap is closed by `OlioContext.scanNestedWorldGroups()`, world tier only.
> Precondition 3 (B1 TOCTOU) is unchanged and is resolved by ratification 7 below, in phase 2b.
> Measured evidence and the bounds that survive are in `PictureBook2ImplementationState.md` §3.

1. **The single role pair across tiers is safe only because nothing auto-enrols into the book `Admin`
   role.** `getCreateBookContext` enrols the creator into **`Writer` only**; `registerUser(..., asAdmin=true)`
   requires the org admin or an existing Admin member. The universe call runs `userWrite=false`, so
   `Writer` is **Read-only** on the universe and the Delete exposure is **`Admin`-only**.
   ⇒ **Phase 2 must not add any automatic Admin enrolment before it splits the role pair.**
2. **`scanNestedGroups` is a WRITE gap, not just a read gap.** The `PbOlioContextUtil` javadoc frames the
   missing nested grant as "will be unreadable". Grants are Read/Update/Create/Delete together, so
   phase 3's portrait writes into `{gallery}/Characters` will be **denied**, not merely invisible.
   ⇒ Must be closed before anything writes below the world's own group tier.
3. **The B1 TOCTOU remedy named in the javadoc does not exist yet.** It cites "a uniqueness constraint
   surfaced as a create failure", but `olio/worldModel.json` has **no `constraints` block**. That remedy
   requires a model change plus a schema migration on a DB only Stephen resets. ⇒ Phase 2 should plan a
   per-slug lock, or budget the model change. Not a phase-1 blocker — the race is over a slug nobody owns.

### §5.3 residue NOT yet satisfied — do not read §5.3 as partially done

- ~~**The Books (universe) tier roles do not exist at all.**~~ **DONE in phase 2a (2026-08-14).** §5.3
  required `~/Roles/Olio/Books/Reader` and `~/Roles/Olio/Books/Writer` plus a two-part membership rule
  (*"`{slug}` role **and** Books role"*). Both roles now exist per organization, the universe grant pass is
  addressed to them, and `getCreateBookContext` enrols a genuine creator in `{slug}/Writer` **and**
  `Books/Reader` — so the membership rule is implemented for the create path. **Scope to keep stated:** it
  is implemented for *creation only*. Opening an existing book enrols nothing by design, so phase 4's
  member/sharing flow has to enrol into both tiers itself. And per ratification 3 the split is not
  retroactive, so the negative half ("the book role alone cannot read the corpora") holds only for books
  created after the split — asserted on a slug created inside `TestBookWorld` case19. Measured there:
  **37 universe-own groups** carry Read for `Books/Reader` and carry **nothing** for either per-book role,
  with the **7 shared `/Library` corpora** partitioned out by `parentId` because the *world* pass grants
  those to the book role legitimately. The same case reads a universe `Traits` record through PBAC as the
  creator, which is the check that distinguishes "corpora access relocated" from "corpora access removed".
- **§5.3's verification test 1 (role-hierarchy inheritance direction, `roles_to_leaf`) was never run.**
  No `TestBookWorld` case exercises parent-role → child-member entitlement. The plan designated this a
  phase-1 one-run settlement and **it is still open**; §10 Q10 (per-book grant scale) depends on the
  answer. Consistent with "explicit grants at both tiers now, hierarchy optimisation later" — so not a
  defect, but not answered either.

### Phase-2 ratifications — RECORDED 2026-08-13 (Stephen)

| # | Decision | Consequence |
|---|---|---|
| **Universe tier is corpora-only** | The universe `UserRole` gets **Read on the `Books` universe's own corpora groups** (words, names, colours, apparel templates). **Book worlds are NOT granted to the universe tier at all.** | **Deliberate deviation from §5.3's Rocket table**, which says universe membership ⇒ read every book. It satisfies §5.3's actual requirement — *"the book role alone is useless because apparel templates and colours live in the universe"* — without creating a read-every-book role before a use case exists. Reversible later by adding the grants; the reverse (revoking) is not, since `setEntitlement` only adds. Also avoids the §10 Q10 scale cost of a second full grant set per book. |
| **Run the role-hierarchy direction test in phase 2** | ~30 lines: grant a permission on a scratch group to a **parent** role, enrol a user in the **child** role only, assert whether `AccessPoint` permits. | Settles §5.3's "two things must be verified" test 1, which was designated a **phase-1** one-run settlement and slipped. §10 Q10 (per-book grant scale) stays open until answered. May also confirm or refute the §5.3 **SUSPECTED DEFECT** that ISO42001's role-to-role wiring is inert. **Record the observed direction in this appendix either way.** |

### Model-definition corrections to Appendix A — verified in code 2026-08-13

Appendix A's model guidance is wrong in two places and incomplete in a third. These drive the phase-2 JSON.

- **`index: true` creates NO database index — and adds PBAC cost.** `DBUtil.java:88` sets
  `useFieldIndexGuidance = false`, and `generateIndices` (`:553-604`) only walks field-level `index` flags
  inside that guard. Real indexes come from **`constraints`** (UNIQUE) and **`hints`** (non-unique).
  Meanwhile `PolicyUtil.java:255` *does* read `fs.isIndex()` (with `dynamicPolicy` defaulting **true**,
  `FieldSchema.java:66`) to decide whether a field gets a foreign-record read-policy scan. So Appendix A's
  *"add `index: true` to `binding.node`, `binding.sourceNode`, `binding.sourceArtifact`,
  `artifact.producedByNode`, `artifact.current`, `node.workflow`, `node.handle`"* would add **zero indexes
  and a per-query PBAC scan on the exact path that carries downstream propagation.**
  ⇒ **Reverse edges are `hints`, not `index: true`.**
- ~~**Constraints and hints are IRREVERSIBLE after the table exists.**~~ **WITHDRAWN 2026-08-14 — this was
  true when written and is not true now, and the plan should not be read as if it were.** It said an
  existing table gets `generatePatchSchema`, which emits `ALTER TABLE … ADD COLUMN` only, so there was no
  add-index-later path — and concluded that every constraint and hint had to be final in the commit that
  first registers the models, front-loaded by a DDL pre-flight test.
  **Phase 1's own DAL work removed the premise:** `DBUtil.generatePatchIndices(schema)` now exists and is
  wired into `IOSystem.open` (`IOSystem.java:162`), applied **after** the ADD COLUMN patches, one statement
  per constraint/hint, each `CREATE [UNIQUE] INDEX IF NOT EXISTS`, with per-statement
  error-log-and-continue. A hint or constraint added to a model whose table already exists **is** created
  on the next boot or JUnit run. (This is Gap B in `PictureBook2ImplementationState.md` §3.)
  **And `-Dreset` is available on this database.** Stephen, 2026-08-14: `am7db` and `am7test` may be
  reset; **`am72db` must never be reset or dropped** — it shares host:port `localhost:15432` with `am7db`,
  so read the database name, not the port. `am7db` lives in a disposable container
  (`pgvector/pgvector:0.8.2-pg18-trixie`, `0.0.0.0:15432->5432`).
  ⇒ **No pre-flight ceremony, no write-but-don't-register step.** Get the constraints right because
  they are the model's invariants, not because the DDL is a one-way door. **What does remain one-way**
  (and is cheap here, since the DB is resettable): *dropping or narrowing* an index is not automatic — a
  changed constraint leaves the old index in place, still enforcing, until dropped by hand (the
  `auth.group` case in §4 of the state doc) — and `generatePatchSchema` emits `ADD COLUMN` only, so
  changing a field's declared **type** does not alter the existing column.
- **"One `current` artifact per `(node, role)`" is not expressible as a unique constraint.** Booleans are
  never NULL, so a UNIQUE index over `current` would forbid a second *superseded* row — the normal case.
  ⇒ Constrain `(producedByNode, role, revision, organizationId)`; enforce single-`current` in
  `PbArtifactUtil.setCurrent` with a post-write re-read assertion, covered by a test.
- **Appendix A is wrong about `groupPath`, right about `urn`.** `common.groupExt` supplies **both**
  `groupId` and a virtual `groupPath` (`PathProvider`); `common.baseLight` has no `urn`. So PB2 models
  **do** get `groupPath` (virtual — must be planned/populated) and **do not** get `urn`. The constraint
  claim stands: `likeInherits` imports no fields, so `data.directory`'s
  `name, groupId, organizationId` is **not** inherited and every invariant needs an explicit `constraints`
  entry.
- **`CryptoUtil` is unusable for `inputHash` as-is:** `defaultHashAlgorithm` (`:79`) is a **mutable
  static** currently set to SHA-512, and `getDigestAsString(String)` (`:158-159`) hashes with the
  **platform default charset**. `computeInputHash` must name SHA-256 at the call site and encode an
  explicit UTF-8 canonical string, with `-` for every null (never `""`, never `"null"`), bindings sorted
  by `(role, bindingOrdinal)` via `String.compareTo`, and doubles via
  `BigDecimal.stripTrailingZeros().toPlainString()`. Pinned by a checked-in golden vector plus a
  Turkish-locale case.

**Field rename:** `olio.pb.scene.index` → **`sceneIndex`** (§2.2 says `index`, which is not in
`DBUtil.reservedWords` and would be emitted unquoted — legal in Postgres, questionable in H2). Phases 4-5
must use `sceneIndex`.

### Phase-2 design notes — RATIFIED 2026-08-14 (Stephen)

**1. `workflow.lastRun` ↔ `run.workflow` mutual reference — capture, don't re-shape.**
`QueryPlan.checkRecursion` (`QueryPlan.java:281-298`) only catches the immediate case where the parent
plan's `(modelName, fieldName)` equals the child field's `(baseModel, name)`. The two-hop cycle
`workflow → lastRun (olio.pb.run) → workflow → lastRun …` never matches it, the `pathSet` guard keys on
`planPath()` (which grows a unique string per level), and `maximumDepth = 500` (`:218`) only **logs** —
there is no `return`. So `planMost(true)` on either model recurses. This matters because
`GET /rest/model/{type}/{objectId}/full` is generic and uses `planMost(true)`.
**Disposition:** recorded as a known shape, not designed around. Use `planCommon` / an explicit
`QueryPlan` on both models, add a `FULL_PLAN_FILTER`-style exclusion, and add a `TestPbGraph` case that
calls `planMost(true)` on both and asserts it terminates — that test is the thing that will catch a
regression. Breaking the cycle by declaring `lastRun` as a `long` remains available and is DDL-neutral
(both shapes emit `bigint` under the same column name), so this stays reversible.

**2. `recomputeStatus` must not write on a read path.** §2.3 has it writing `nodeStatus` while being
"invoked on opening a book's workflow view". With ratified Q1 (uniform olioUser ownership) that is either
a privileged write triggered by any reader, or a caller-owned write that fails silently into a discarded
update result — the `LibraryUtil` shape `architecture.md` warns about.
**Disposition (approved):** split it. `recomputeStatus` **computes and returns** derived status; only an
explicitly authorized write path persists. This is consistent with §2.3's own rule — *the hash is truth;
the status is a repairable cache* — so nothing depends on the cache being written during a read.

**3. The role split is not retroactive — accepted, no migration.** `setEntitlement` only adds, so per-book
roles created before the two-tier split keep their universe grants on `am7db` permanently. Stephen's
call: **not an issue.** Books created before phase 2 are broken for other reasons, and a universal grant
on the existing system is acceptable for now, to be fixed manually if it ever matters.
⇒ Do **not** build scoping or migration machinery for this. §9's `TestPbSecurity` assertion *"a user
holding the book role but not the universe role cannot read the universe corpora"* is therefore scoped to
a **book created after the split**, and that scoping is a test-fixture detail, not a product requirement.

**4. URN — include it, and make the names distinct.** `common.baseLight` omits `urn` (which is why PB2
would not get one); `common.groupExt` **does** supply a virtual `groupPath`, so `UrnProvider`'s
`MODEL_DIRECTORY` branch would work as-is. URN exists for **portability**: a human-readable, row-id-free
reference so an object can be exported with urns for its foreign references and imported into another
system whose ids differ. That is squarely PB2's case — the graph is nothing but cross-references
(`binding.sourceNode`, `binding.sourceArtifact`, `artifact.producedByNode`, `scene.book`,
`workflow.book`), phase 6 is a migration, and §3.5 copies records between worlds.
**Caveat to honour:** `UrnProvider` composes `schema + org path + groupPath + name` and then
`getNormalizedString` lowercases and strips non-alphanumerics, and `common.urn` declares `identity: true`
but carries **no uniqueness constraint** — so similarly-named machine-generated records would produce
colliding urns with nothing to catch it. If PB2 adopts urn, `node`/`binding`/`artifact`/`run` names must
be derived to be unique within their group (e.g. from `node.handle`, or `role + bindingOrdinal`), because
the provider reads `name`, not `handle`.

**5. Second role pair on `OlioContextConfiguration` — APPROVED 2026-08-14 (Stephen): "add universal for
grid use".** `OlioContextConfiguration` gains `universeAuthorizationUserRole` / `universeAuthorizationAdminRole`,
**both defaulting to null**. `initialize()` uses them for the **universe** grant pass only when both are
non-null; otherwise it falls through to today's single pair. So:
- **Book contexts** set both and get a genuine two-tier split (closing Appendix D precondition 1 — the
  book `Admin` no longer receives CRUD on the universe's non-shared groups).
- **Grid/arena/agent** leave them null and keep using the **universal org-wide `~/Roles/Olio User` /
  `Olio Admin`** exactly as today — byte-identical behaviour, no migration.

This supersedes the earlier "config is pinned to exactly two role fields" constraint. Note
`effectiveUserRole()`/`effectiveAdminRole()` must stay bound to the **world** pair — the universe pair
must never become the fallback for `enrole`/`scanNestedGroups`, or the isolation-losing direction reopens.

> **AS-BUILT 2026-08-14 (phase 2a).** Implemented as ratified, with three additions the code forced:
> (a) the two tiers resolve **independently** — universe pair → world pair → org-wide for the universe pass;
> world pair → org-wide for the world pass, never the universe pair — because a context could otherwise
> carry a universe pair and silently fall to the org-wide pair for both;
> (b) a **half-configured** universe pair throws instead of falling back, since the fallback is a silent
> re-grant of the per-book roles on the universe and no grant failure is loud;
> (c) the creator must be enrolled in the universe user role or the split *removes* corpora access rather
> than relocating it — so `registerUser` was factored into a shared `register(...)` with a
> `registerUniverseUser` beside it, keeping one authorization check, one org-scope check and one audit shape
> across both tiers. Nothing auto-enrols into either admin role, which is what closes precondition 1.

**6. The two live auth defects are HOISTED — approved 2026-08-14.** `/cancel` discarding its principal
(`PictureBookService.java:474-476`; static process-wide `cancelRegistry` at `:86` keyed by a
client-supplied path param ⇒ **any authenticated user can cancel any other user's extraction**) and the
scene-addressed endpoints not authorizing their owning book (`generateSceneImage` `:3277`,
`regenerateBlurb`/`setSceneStatus` `:3879`, `prepare-images` `:3840`) ship as a **standalone patch ahead
of the phase queue**, not at phase 4. Both are exploitable today and independent of all PB2 work.
The scene authorization check must live in an **Objects7 utility**, never as an `if` in the resource
method, or it becomes business logic in Service7.

**7. B1 TOCTOU — APPROVED.** `olio.pb.book`'s unique `(slug, organizationId)` is the serialization point:
create the **book row first**, then the world, then patch the book's `world` FK (PATCH-shaped — `schema` +
`id` + `objectId` + **`name`** + `world`, and the update result must be asserted, never discarded). A second
racer's create fails on the unique index. **No `olio/worldModel.json` change and no schema migration** — the
per-slug JVM lock is the fallback only if the unique violation does not surface as a create failure, and if
used it must be documented as per-process only.

**8. URN — INCLUDE.** All eight `olio.pb.*` models carry `urn`. Because `UrnProvider` composes from
`name` (not `handle`) and `common.urn` has **no uniqueness constraint** to catch a collision,
`node` / `binding` / `artifact` / `run` names must be **derived to be unique within their group** — from
`node.handle`, and `role + bindingOrdinal` for bindings. `book` and `series` are already unique by slug
and name. This is what makes a book graph portable: export by urn, import into a system with different
row ids and foreign keys intact.

**9. `olio.pb.artifact.current` → RENAMED to `selected`.** Stephen: rename now. `current` is legal in both
PostgreSQL and H2 but is a word to avoid on principle, and a column rename after the fact is an
add-plus-orphan two-step. `selected` also matches §6b.2's own language for the concept ("the chosen
keeper") and the codebase's bare-adjective boolean style (`pinned`, `required`, `userEdited`, `vaulted`).
**All references change**: the `producedByNode, selected` hint, `PbArtifactUtil.setSelected`, and §6b.2's
mapping table.

**10. Q17 orphan worlds — RESOLVED by the two-tier role split.** Stephen: *"should be fixed w/ 3 or adding
user into new roles."* The reason an orphan world was unreclaimable is that nothing referenced it and there
is no list-worlds API. With the universe-tier role in place (decision 5 above), a member of the universe
role can enumerate the `Worlds` container and reclaim an orphan. **No separate admin-only reconcile utility
is needed.** Creating the `olio.pb.book` row first (decision 7) also makes the orphan case rare rather than
routine. Q17 is closed.

**11. Q9 `reset()` — proposed answer ADOPTED.** `reset()` **clears artifacts and marks nodes STALE but
KEEPS the graph** — the ComfyUI mental model. The topology (nodes, bindings, handles, canvas geometry) is
the user's work; the artifacts are reproducible output.

**12. Q12 Comfy — BACKLOGGED.** Stephen: *"it's new so we'll want an intentional use case for it. Maybe
backlog Comfy for now."* **Phase 3b is removed from the current scope.** `SDAPIEnumType.COMFY` still lands
in phase 2 (the enum value must exist before `olio.pb.artifact.backend` can validate against it — a
one-line addition with no behaviour), but the `…/olio/sd/comfy/` package, `ComfyGraph`/`ComfyUtil`/
`ComfyClient` and `TestComfyBackend` are deferred until there is a concrete use case. **SwarmUI remains
the only backend.** §6 stays in this document as the design of record for when it is picked up.

**13. Q15 cast/group entities — NO NEW MODEL.** (Stephen deferred to my recommendation.) A collective like
"Meadow Herd" is represented as **N bindings sharing one `role`, distinguished by `bindingOrdinal`** —
the field already added in phase 2 precisely so a multi-valued role needs no table rebuild. Each binding
carries `refModel = olio.charPerson` + `refObjectId`. The canvas chip groups by the consuming node's
`handle` (`character_@herd`). Rationale: `auth.group` is an authorization container and overloading it as
a cast list conflates two meanings, while an `olio.pb.castGroup` model adds a table, a lifecycle and a
grant surface for something the binding edge already expresses. **Revisit only if a collective needs its
own attributes** (a name, a description, a shared style ref) rather than just membership — at which point
`castGroup` becomes justified. Q15 closed for now.

**14. Q5 `initialized = true` before authorization — FIX IT: throw.** Stephen: *"Throw an exception there
if it shouldn't be allowed."* `OlioContext.java:379` sets `initialized = true` before
`configureWorldAuthorization` at `:394-395`, and the swallow-all `catch` at `:403-406` turns an
authorization failure into a context that reports itself initialized with **no grants applied** — the
single most likely source of future "the PBAC is broken" reports. **Change:** an authorization failure
must propagate as an exception rather than be swallowed, for **all** callers (grid/arena included), not
just designed around by PB2's `authorizationConfigured` flag. This is a live behaviour change for
grid/arena, so it lands with the existing gate as its non-regression check. The `authorizationConfigured`
flag stays — it is still the honest signal for "grants completed" — but it stops being the only defence.

**15. Q19 relocating `~/Roles/Olio *` — WON'T DO.** Stephen: *"Don't migrate, not worth it. I'll manually
reset membership as needed."* The roles stay under olioUser's home. Consequence to keep stated: they
remain awkward to administer through the role-membership UI, and membership changes are manual. Closed.

**16. Q18 access-request completion — BACKLOG.** Book sharing stays **add-by-writer-only with no
request/approval trail** until `access.accessRequest` gets a UI. The backend scaffolding exists (models,
`AccessRequestFactory`, the policy operations, `AccessRequestService`); only the Ux752 flow is absent.
Tracked as a design note, not phase 2-4 work.

**17. Q10 grant scale — WITHDRAWN as a question.** Phase 1 measured cold book creation at ~3s (and
~16-26s for a full grid generation), which is not a problem at the scales in play. The only live part is
whether the role-hierarchy optimisation is available at all, and the direction test approved for phase 2
answers that. Nothing is blocked either way, because explicit grants at both tiers work regardless.

**18. Q12 Comfy — see decision 12 above (backlogged).** **Q16 canvas rendering — use an npm library**
(Stephen), likely direct-canvas; a research prompt is being prepared for a web-enabled session rather
than guessed at here.

**19. Q16 canvas rendering — ANSWERED: DOM cards + `@panzoom/panzoom`, no canvas/graph library.**
Researched 2026-08-14 via two independent web-enabled sessions (ChatGPT and Grok) against
`aiDocs/CanvasLibraryResearchPrompt.md`. **They converged on the same recommendation, the same runner-up
and the same rule-outs**, which is the main reason to trust it. (A third response, Gemini, was supplied as
a PDF that could not be text-extracted in this environment — no `poppler`, no `pypdf`/`fitz`/`pdfminer` —
so it is **not** reflected here.)

**Recommendation:** absolutely-positioned DOM cards inside one `transform: translate() scale()` "world"
element, with **`@panzoom/panzoom`** (MIT, ~3.7 KB gzipped, zero runtime deps, framework-agnostic,
imperative — mounts into a DOM node Mithril owns) handling *only* the viewport: pan, wheel/pinch zoom,
zoom-to-cursor, screen↔world conversion.
**Runner-up:** `d3-zoom` (ISC) — choose it only if the interaction model needs to become substantially
custom; it is not actually small in practice (pulls `d3-selection`, `d3-drag`, `d3-interpolate`,
`d3-transition`, `d3-dispatch`).

**Why DOM beats canvas here** (both responses, independently): tens-to-low-hundreds of image cards is
well inside DOM territory — the cost is image decode, not a few hundred positioned `div`s; text stays
crisp and selectable at any zoom; and **accessibility comes free**, which matters concretely because
`@axe-core/playwright` is already in the Ux752 suite and a canvas board would mean re-implementing focus,
ARIA and keyboard reachability from scratch. Native `<img>` also beats manual texture upload for
decode/caching/memory, and there is no scene-graph serialization format to fight — the four integers
(`canvasX/Y/W/H`) stay ours.

**Ruled out, with reasons worth keeping:** `tldraw` — custom license, free for development only,
**production requires a commercial key** (a real trap in a corporate setting); `@xyflow/react` (React
Flow) — MIT and excellent but **React-only**, so it would mean mounting a React root inside a Mithril
vnode with dual reconciliation; `JointJS`/`@joint/core` — MPL-2.0 plus a commercial upsell;
`cytoscape` / `sigma` / `@antv/x6` — graph-theory and auto-layout engines, and **PB2 needs no auto-layout
and no edge routing** (the reference UI draws no visible edges at all); `konva`/`fabric`/`pixi.js` —
maintained and fine, but canvas-based, 50-245 KB, and they push text rendering and a11y back onto us.

**What still has to be hand-rolled** (neither library solves these): card-drag versus canvas-pan
disambiguation (pointer-down on a card drags it; on empty space it pans), selection + popover, the
dotted-grid background (CSS `background-image` on the transformed layer), geometry persistence, and any
optional SVG edges. Mithril integration is `oncreate`/`onremove` for mount/destroy, a container vnode
whose children the library does not manage, and `m.redraw()` only on our own state changes — never fed
back from panzoom events, or the viewport re-creates in a loop.

This **supersedes §6b.4's guess** ("absolute-positioned divs … adds no dependency"). The direction was
right; the correction is that the ~3.7 KB is worth paying rather than hand-rolling pinch-zoom,
zoom-to-cursor and trackpad-versus-wheel behaviour.

*Not independently verified here:* the package sizes, licences and publish dates above come from the
research sessions and were not checked against the npm registry from this machine.

#### Phase-5 implementation rules from the research — capture these, they are the expensive lessons

**THE invariant: Panzoom must never own board geometry.** Its maintainers have said publicly that making
it a complete dynamic infinite-canvas abstraction (auto content bounds, fit-to-content, element-centred
zoom) would make it considerably larger, and declined. So the split is:
- **the app owns** `canvasX / canvasY / canvasW / canvasH` — *application* state, persisted;
- **Panzoom owns** `viewportX / viewportY / scale` — *UI* state, **never serialized onto the card**.

Conflating them is the mistake that makes coordinate handling painful later.

**Ownership boundary — Mithril must never re-render a node whose `transform` Panzoom owns:**
```
.board-viewport            Mithril owns; CSS grid background lives here
└── .panzoom-world         Panzoom owns transform; Mithril must not touch style.transform
    └── .card-layer        Mithril reconciles ONLY this
        ├── Card …
```
Mount in `oncreate`, `panzoom.destroy()` in `onremove`. Do **not** call `m.redraw()` from a `panzoomend`
handler unless another component genuinely needs viewport state — that is the redraw loop.

**Card drag must divide by scale.** `newCardX = startCardX + (clientX - startClientX) / scale`. At 200%
zoom, 100 CSS pixels of pointer movement is 50 world units. Screen→world:
`worldX = (clientX - viewportLeft - x) / scale`.

**Drag versus pan** is a UX decision the library does not make: card body → move card; empty canvas →
pan; middle-mouse and optionally space+drag → pan; touch decided by whether the gesture starts on a card.
Panzoom has an **exclusion mechanism** for interactive children (buttons/links inside the panzoom
element) — use it rather than fighting event bubbling.

**Grid caveat:** a grid inside the transformed world **scales with the board**. For a Figma-style grid
that stays visually constant, put it on `.board-viewport` and drive `background-position`/`background-size`
from the transform.

**Zoom sharpness is not a rendering-engine problem.** Canvas cannot manufacture image data either; a 512px
source scaled 2× interpolates identically. The real optimisation is serving an appropriately sized source —
relevant because PB2's generated art is 512-1024px. Use `<img decoding="async" loading="lazy">` with
explicit `width`/`height`.

**Effort estimate for what is hand-rolled** (~200 LOC total, not a canvas engine): card drag 50-100,
drag/pan arbitration 20-50, keyboard movement 30-50, coordinate conversion ~20, persistence 20-50,
inertia 30-50 *only if user testing shows it is expected*, dotted grid ~5 lines of CSS.

**Accessibility checks to write against `@axe-core/playwright`:** every card Tab-reachable; card has a
meaningful accessible name; image has meaningful `alt`; action buttons named; focus visible **at all zoom
levels**; popover focus management; Escape closes popover; keyboard movement updates geometry; dragging
never makes a card unreachable; no duplicate accessibility representation; **and an axe scan at 25%, 100%
and 200% zoom**. This list is the concrete reason DOM was chosen — on canvas, every one of these means
building a parallel accessibility tree.

**Maintenance signal, for the record:** `@panzoom/panzoom` 4.6.2 shipped April 2026; `d3-zoom` is still
3.0.0, last published roughly five years ago. Mature and stable, but that is why it is the runner-up
rather than the pick.

### Runtime verification scope (corrected)

`verifyGrants` as first implemented checked the **world tier only** (`ctx.getWorld()` /
`getWorldPath()`); the universe tier was covered solely by `TestBookWorld` case03 — a test, not a runtime
guard — so a universe-tier grant failure would still have returned a context reported as verified. Any
summary claiming "the whole enumerated group set (46 world + 44 universe)" was describing the test, not
the product. Extended in the sign-off fix round to verify both tiers, checking **Read** on the universe
(the universe pass runs `userWrite=false`, so asserting CRUD there would fail a correct system).

---

## Appendix C — design review round 2 (architect), phase-1 findings

Recorded 2026-08-12 against the phase-1 implementation plan. N1 (role instance-field trap), N2 (the
`isInitialized()` verification asserting nothing), N5 (the Q6 default contradiction) and N6 (nine opt-in
sites, not seven) are folded into §3.3, §3.4 and §0 above. What remains:

**N3 — `TestBookWorld` must live in the production package.** Phase 1 ships `BookContext` plus a
**package-private** `PbOlioContextUtil.assembleBookContext(BaseRecord world)` taking an already-resolved,
already-authorized `olio.world`; the slug-addressed public entry does **not** exist until phase 2, when
`olio.pb.book` exists and the entry can be `AccessPoint.find(user, book)` → `book.world` FK →
`assembleBookContext` (satisfying ratified decision 9 and §5.6b point 3 by construction). But the existing
Olio tests live in `org.cote.accountmanager.objects.tests.olio`, from which a package-private method is
**not callable** — §9's `TestBookWorld` cases 2 and 12 would not compile, and the predictable "fix" is to
widen the method to public, silently re-opening the finding. **Put `TestBookWorld` in
`src/test/java/org/cote/accountmanager/olio/`.** Precedent: `TestChatMemoryPipelineWiring.java` sits in
the production package for exactly this reason.

**N4 — `Factory.java:232` is not a callable primitive.** It sits inside the *private*
`getCreateUser(adminUser, name, group, orgId, skipSetup)`. The new find-only `Factory.findUser` must wrap
`context.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, name, 0L, 0L, organizationId)` directly.
Both `findUser` and `WorldUtil.findWorld` are **unauthorized reads** (`WorldUtil.java:47` uses
`getSearch().findRecord`; `findUser` passes a `null` contextUser) — say so in their javadoc, and ensure
`BookContext` never surfaces the olioUser record to a caller.

**N7 — `Decks.clearAll()` must NOT hang off `OlioUtil.clearCache()`.** Three decks self-refill when empty
(`Decks.java:61-63,71-73,163-165` — `patternDeck`, `colorDeck`, `traitDeck`), so clearing them is a cheap
memoization drop. The **four name decks** (`maleNamesDeck`, `femaleNamesDeck`, `surnameNamesDeck`,
`occupationsDeck`) have **no lazy-refill guard** — they are repopulated only by an explicit `shuffleDecks`
(`:133-147`). Today nothing clears them; wiring `clearAll()` into the `@RolesAllowed({"admin","user"})`
`/cache/clearAll` path would let any `user`-role caller empty them mid-run, and the read path's only guard
is a log line (`CharacterUtil.java:408`) before `randomPerson` at `:415` — the failure is
`rand.nextInt(0)`, not a slow rebuild. **Either give the four name decks the same lazy-refill guard, or
keep `Decks.clearAll()` behind the admin-only evict.** Prefer the latter in phase 1 (smaller change).
Describe what *does* stay on `clearAll` as **self-refilling memoizations**, not "corpus memoizations".

**N8 — evict-by-world must remove every entry for that world.** The phase-1 cache key includes the user
name (`OlioContextUtil.java:31` keys on `FIELD_NAME` today), so one world has **N** cached contexts, one
per user. The admin evict endpoint takes a world objectId and must remove **all** matching entries; a
single-key delete leaves other users holding a stale context after a book delete or reset.

**ColorUtil — two accuracy fixes to the phase-1 cache claim.**
- `defaultColorMap` is **read at `ColorUtil.java:125-126` and never written** — there is no `.put` for it
  anywhere in the file. Bounding a map that is never populated is dead work: delete it, or state plainly
  that it is dead.
- Keying `colorComplements` (written at `:206`, keyed by hex while the lookup is scoped by
  `world.get(colors_id)` at `:166`) **is** a behaviour change in a multi-world JVM — that is precisely the
  fix. Describe it as *"no change for single-world processes; corrects cross-world colour leakage
  otherwise"*, **not** as "no behaviour change".

**The deferred `~/Colors` removal is lower-severity than §2.1 Rule 2 implies — but still a violation.**
The `makePath("~/Colors")` at `ColorUtil.java:140` runs as the **record owner**, not the org admin
(`:136-140` reads the user by `ownerId`; on the REST path that owner is the acting principal via
`f.newInstance(MODEL_CHAR_PERSON, user, …)` at `OlioService.java:337`). So it is a *self-scoped* write on
a read path — **not** the `LibraryUtil` shape that `architecture.md`'s "Read paths must not create, and
never as the org admin" is built around, and not a privilege escalation. The §5.5 audit row must say
**owner-scoped, not admin-scoped**, or it will be triaged at the wrong priority. Phase 1 must not describe
`ColorUtil` as fixed. The live read path is `GET /olio/roll/{gender}` (`OlioService.java:353,355`,
`@RolesAllowed({"user"})`).

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
