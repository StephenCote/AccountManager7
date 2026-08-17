# PictureBook 2.0 — Implementation State

**As of:** 2026-08-17 · **Pause point:** phase-3 code complete and compiling; live level-1 and level-2
verification green; **the flag-off non-regression gate and the 113-test gate had not finished at the time of
writing** (see the Phase 3 entry in §3). Nothing committed.
**Next up:** finish those two gates, then Phase 4 (REST). **Read §3's Phase 3 entry first**, then Phase 2c's.
The `AccessPoint.list` question that gated phase 4 is **DISPOSED** (2026-08-17) — constrain at the Objects7
utility layer, `AccessPoint.list` unchanged, logged as **KI-67**; the phase-4 constraints it implies are
recorded there and in `PictureBook2Plan.md` Appendix D.
**Design of record:** `PictureBook2Plan.md` — read **Appendix D** first (as-built + every ratified
decision), then Appendix C, then the body. Where the body and Appendix D disagree, **Appendix D wins**.

---

## 1. Status at a glance

| Work | State | Tests |
|---|---|---|
| Phase 0 — ratification | **DONE** | — |
| Phase 1 — Olio plumbing / book compartment | **DONE, signed off** | 95 green |
| Hoisted — two live auth defects | **DONE** | 7 green |
| DAL — index generation on schema patch | **DONE** | 9 green |
| DAL — participation-table index/column patch | **DONE** | (in the 9) |
| `PathUtil` — characterize + fix | **DONE except F3** | 14/15 + 1 |
| Phase 2 — plan + 2 design-review rounds | **DONE** | — |
| **Phase 2a — two-tier role split + recursive world grant** | **DONE** | 21 + 83 green |
| **Phase 2b — the eight `olio.pb.*` models, registered + verified** | **DONE** | 13 + 113 green |
| **Phase 2c — the six utilities + graph/security tests** | **DONE** | 15 + 10 green |
| **Phase 3 — pipeline wired to the graph behind `picturebook.v2`** | **DONE, partially verified** | 25 green (2c) + 2 green (live level 1 + level 2); flag-off gate + 113-gate pending |
| Phase 4 | **NOT STARTED** | — |
| Phase 5 (Ux) / 6 (migration) | out of scope this run | — |

**One test is RED, deliberately.** See §4. Everything else passes.

---

## 2. Full test inventory (all against `am7db`)

```
TestBookWorld                 21    olio book compartment, fresh-org H2 cases, evict scoping,
                                    + phase 2a: case19 two-tier split, case20 recursive world grant
TestGameUtil                  25 ┐
TestGameUtilSync              15 │
TestOlioGameFeatures          15 ├ the phase-1 non-regression gate = 60
TestNestedStructures           3 │
TestOlio2                      1 │
TestOlioRules                  1 ┘
TestPictureBookKnownIssues    15    KI-34 / KI-35 / KI-42
TestOlioCacheScope             1    dirNameCache is NOT self-refilling
TestSchemaIndexPatch           9    index DDL generation + participation tables
TestPictureBookSceneAuthz      7    scene->book authorization, cancel-registry ownership
TestPathUtilBehavior          15    path characterization  (14 pass, 1 RED — §4)
TestPathUtilKi60Watch          1    the KI-60 diagnostic marker fires
TestPbModelSchema             13    phase 2b: the eight olio.pb.* models, tables, pg_indexes, round trip
TestPbGraph                   15    phase 2c: cycle refusal, propagation, hashing (golden vector +
                                    Turkish locale), artifact revisions/selection, sanitization,
                                    PATCH shape, planMost termination over the workflow<->run cycle
TestPbSecurity                10    phase 2c: the Objects7-level isolation properties, the two-tier
                                    membership rule, the fresh-org create ordering, and the ratified
                                    ROLE-HIERARCHY DIRECTION test
TestPictureBookWorkflow        2    phase 3, LIVE Swarm+LLM, flag ON, single-threaded: level-1
                                    structural + level-2 differential (KI-59). NOT excluded in the pom.
                                    ~180s and ~310s respectively - two/three FLUX.2 composites.
TestPictureBookCustom          1    the phase-3+ NON-REGRESSION GATE, run with the flag OFF and
                                    NEVER EDITED. Its pom <exclude> is already inside a <!-- --> block,
                                    so it needs no pom change - do not comment it again (that makes a
                                    nested XML comment and the POM stops parsing).
```

**Running them — three traps that will waste your time:**
1. `pom.xml:19` sets `<skipTests>true</skipTests>`. **Always pass `-DskipTests=false`.**
2. **A bare `BUILD SUCCESS` proves nothing** — confirm a `Tests run: N` line with N > 0.
3. `pom.xml:108-297` excludes 154 classes and **`-Dtest=` does NOT override an exclude.** Six are
   currently commented out as `PB2-GATE` so the gate can run (see §6).
4. Do **not** pipe `mvn` through `head` — it truncates before the summary. Redirect to a file.

`TestOlio`, `TestRealm` and `TestSD` are **dead classes** — every `@Test` is inside `/* */`. They
contribute zero coverage; don't count them.

---

## 3. What shipped

### Phase 1 — the book compartment (Objects7 + 1 Service7 delegate)
A `Books` universe with one world per book; `BookWorldInitializationRule` (three PB groups + a minimal
`CONSTRUCT` root event, no locations); a find-only read path behind a narrow final `BookContext`;
role-parameterised deterministic grants; an org+universe+world-keyed bounded context cache; an audited,
idempotent, org-scoped `registerUser`; and an admin-only cache-evict endpoint.

**Four `Objects7`-wide defects fixed on the way, none PictureBook-specific:**
- `MemberUtil` never invalidated the `system.participation` query cache, so a just-written grant was
  invisible to `checkEntitlement` **in the same process** — and the same hole could hide a **revocation**.
- `AccessPoint.setPermitBulkContainerApproval` was a process-global, non-`volatile` PBAC relaxation with
  **no `finally`** — an exception during init left it on for every thread, forever. Removed. Measured
  cost of removal: **none** (A/B on identical generation: 20,068 ms with the flag vs 16,829-26,217 ms
  without; the paths it covered either bypass PBAC or already pass the boolean explicitly).
- `configureWorldAuthorization` resolved its grant target with a `parentId`-only `findRecord` —
  first-row-wins, able to grant on an unrelated universe. Now name-resolved.
- The `OlioContextUtil` cache key omitted `organizationId`, so one user name served one context across
  tenants — carrying the `olioUser` **principal** across orgs.

### Hoisted — two live auth defects
`/cancel` discarded its principal (static process-wide registry keyed by a client-supplied param ⇒ any
authenticated user could cancel any other's extraction) — now a principal-scoped registry in Objects7.
Scene-addressed endpoints never authorized the owning book — now `PictureBookUtil.authorizeSceneAccess`,
resolving scene → group → parent → book **by id at every hop, never path resolution**.
**Measured correction to §5.6's framing:** an *unentitled* user was already blocked at the note level
(404). The real gap was a user with Read+Update on the `Scenes` group but nothing on the book group —
exactly the shape PB2's shared world creates. Now 403.

### Phase 2a — the two-tier role split + the recursive world grant

**Authorization-only diff, entirely inside Objects7; no Service7, Console7 or Ux change.** Every consumer
of `OlioContextConfiguration` is Objects7-internal and none of them sets the new fields, so grid/arena/
agent keep the org-wide `~/Roles/Olio *` pair on the default path.

**Verified 2026-08-14 against `am7db`, no reset, no DDL:** `TestBookWorld` **21/21**, and the
non-regression gate re-run **83/83** — `TestGameUtil` 25, `TestGameUtilSync` 15, `TestOlioGameFeatures` 15,
`TestOlioRules` 1, `TestOlio2` 1, `TestNestedStructures` 3, `TestPictureBookKnownIssues` 15,
`TestPictureBookSceneAuthz` 7, `TestOlioCacheScope` 1. The gate matters here specifically because
`initialize()`'s grant block and `registerUser` are on the grid/arena path, not only the book path.

**The split.** `OlioContextConfiguration` gains `universeAuthorizationUserRole` /
`universeAuthorizationAdminRole`, both null-default. `initialize()` now resolves the two tiers
independently: the **universe** pass uses the universe pair when set, else the world pair, else the
org-wide pair; the **world** pass uses the world pair, else the org-wide pair, and **never** the universe
pair. `effectiveUserRole()`/`effectiveAdminRole()` stay bound to the world pair, so `enrole` /
`scanNestedGroups` cannot reach a role every book shares. A **half-configured** universe pair throws
rather than falling back to the world pair — that fallback would silently re-grant the per-book roles on
the universe, and a grant never fails loudly (`setEntitlement` logs a failed membership only under trace).

`PbOlioContextUtil` creates two more roles per organization — `~/Roles/Olio/Books/Reader` (Read on the
`Books` universe corpora) and `~/Roles/Olio/Books/Writer` (Create/Update, plus Delete on the universe's own
groups). Neither can collide with a per-book role container: `BOOK_SLUG_PATTERN` is lowercase-only, so no
slug can be named `Reader` or `Writer`.

**The creator is enrolled in BOTH tiers, and that is not optional.** Since the split the per-book roles
hold nothing on the universe, so a creator in the book `Writer` role alone could not read the apparel
templates, colours and word lists the pipeline needs. `registerUser` was refactored into a shared
`register(actor, user, role, authorizingRoles, label)` with a new `registerUniverseUser` beside it —
one authorization check, one org-scope check, one audit shape, both tiers. Nothing auto-enrols anybody
into **either** admin role, which is what closes Appendix D precondition 1.

**Measured on `am7db`, on a book created after the split:** 37 universe-own groups each carry Read for the
universe `Reader` role, and **neither** per-book role holds Read on any of them; the 7 shared `/Library`
corpora are partitioned out by `parentId` because the *world* pass legitimately grants those to the book
role. The creator can still read a universe `Traits` record through PBAC — so corpora access was
*relocated*, not removed. That last check is the one that would have caught the split as a regression.

**The recursive world grant (Appendix D precondition 2).** New `OlioContext.scanNestedWorldGroups()`,
invoked from `initialize()` behind `scanNestedWorldGroups` (default false; the book config sets it). It
grants the **world-tier** pair recursively beneath every child of the world container. Three deliberate
bounds:
- **World container only, never the universe container.** The universe container has `Worlds` among its
  children, and `Worlds` holds every book's container — recursing there would hand the shared universe role
  CRUD over every book in the organization.
- **The container group itself is not a target**, matching `resolveGrantTargets`: it holds the `olio.world`
  record, and `userWrite=true` would give the world role Delete on it. Children are enumerated by
  `parentId`, so the shared `/Library` corpora (foreign fields of the world, but parented under `/Library`)
  are never reached and cannot pick up the `Delete` that `configureWorldAuthorization` withholds.
- **It repairs the tree that exists when it runs.** A sub-subgroup created later in the session is not
  covered and its write path must grant on the group it just created (the pattern `OlioService`'s Gallery
  grant already uses); a re-open repairs it, since `MemberUtil.member(..., true)` is idempotent.

**Measured across an evict-and-reopen (`TestBookWorld` case20), two levels deep:**
```
CASE 20 BEFORE — entitlement lvl2=false lvl3=false, PBAC read of the deep record=false
CASE 20 AFTER  — entitlement lvl2=true  lvl3=true,  PBAC read of the deep record=true
```
The "before" leg is what makes the "after" leg evidence: both groups are created *after* the context was
built, so a green result cannot be an earlier run's grant. **The plan's open question about the granting
principal is answered by this:** `scanNestedGroups` grants as the **olio user** while
`configureWorldAuthorization` grants as the **org admin**, and the olio-user path demonstrably lands the
grant (a failed `setEntitlement` is silent, so this had to be measured rather than reasoned about). The two
paths still use two principals — left as-is deliberately, since changing `scanNestedGroups` to the org admin
would widen the granting authority for its six existing `OlioAction` callers and `OlioService:211`, which is
a grid/arena behaviour change with no demonstrated need.

**Test changes worth knowing about, because one is a fixture correction and not a weakening:**
- `case09` (entitlements do not recurse) now probes a **randomly-suffixed** sub-subgroup. It measures a
  *platform* property, so its target must be a group no grant pass could have covered — and `SLUG_ALPHA` is
  a fixed slug on a live database, so a literal `Gallery/Characters` would survive between runs and be
  granted by the new recursive pass on the *next* run. Same shape, same assertions, same property.
- `case03`'s universe leg now addresses the **universe Reader** role. Addressed to the book role it would
  assert the coupling the split removes, and would pass only on books old enough to still carry the legacy
  grants.
- `case19` / `case20` are new: the split (on a slug created inside the case) and the recursive grant
  (before/after across an evict-and-reopen, asserting a PBAC read at depth 2, not just an entitlement row).

**Not retroactive, by ratification.** `setEntitlement` only adds, so books created before the split keep
their per-book roles' universe grants; `verifyGrants` therefore checks the world tier against the book role
and the universe tier against the universe role, which is correct for both old and new books.
**Known bound carried forward:** opening an *existing* book enrols nothing (case14's whole point), so a
user shared into a book by a future phase-4 member flow needs enrolling in **both** tiers or they will hold
the book role and no corpora access.

### Phase 2b — the eight `olio.pb.*` models, registered, tables + indexes verified

**Verified 2026-08-14 against `am7db` (`localhost:15432/am7db`, read from
`resource.properties:9`), no reset.** `TestPbModelSchema` **13/13**, plus **113/113** across
the PB/schema-adjacent suites and the phase-1 non-regression gate: `TestBookWorld` 21,
`TestPictureBookKnownIssues` 15, `TestSchemaIndexPatch` 9, `TestPictureBookSceneAuthz` 7,
`TestOlioCacheScope` 1, `TestGameUtil` 25, `TestGameUtilSync` 15, `TestOlioGameFeatures` 15,
`TestNestedStructures` 3, `TestOlio2` 1, `TestOlioRules` 1. The gate matters here because registering
the eight models adds them to `ModelNames.MODELS`, which **every** test's `IOSystem.open` scans — this
is not a PictureBook-only diff. All eight tables were created by `IOSystem.open` on the first JUnit run, with
**zero** errors and zero `cannot be indexed` warnings, and every declared constraint and hint is
present in `pg_indexes` with the right columns and the right uniqueness — read out of
`pg_indexes.indexdef` itself, not from the DDL the generator would emit, because a rejected statement
is logged-and-continued (`IOSystem.java:169-178`) and a generated-DDL assertion would pass straight
over it.

```
A7_olio_pb_book_0_1     UNIQUE (slug, organizationid), UNIQUE (name, groupid, organizationid), (series)
A7_olio_pb_series_0_1   UNIQUE (name, groupid, organizationid), (universe)
A7_olio_pb_scene_0_1    UNIQUE (name, groupid, organizationid), (book, sceneindex), (scenenode)
A7_olio_pb_workflow_0_1 UNIQUE (book, organizationid), UNIQUE (name, groupid, organizationid)
A7_olio_pb_node_0_1     UNIQUE (name, groupid, organizationid), (workflow), (handle), (nodestatus)
A7_olio_pb_binding_0_1  UNIQUE (node, role, bindingordinal, organizationid), UNIQUE (name, groupid,
                        organizationid), (node), (sourcenode), (sourceartifact), (role)
A7_olio_pb_artifact_0_1 UNIQUE (producedbynode, role, revision, organizationid), UNIQUE (name, groupid,
                        organizationid), (producedbynode, selected)
A7_olio_pb_run_0_1      UNIQUE (name, groupid, organizationid), (workflow), (runstatus)
```
(plus the inherited `id` / `objectId` / `urn` hints on all eight, and
`A7_olio_pb_scene_system_participation_0_1` for the scene's `characters` list.) Every constraint and
hint column is indexable, which is why every string field carrying one has an explicit `maxLength` —
an unbounded `text`/`varchar` column makes `generateIndex` return **null** and the index is silently
never created.

**The tables existing is not the same claim as the schema working**, so there is a create/read
round trip: one `olio.pb.book` created through `AccessPoint`, read back, `urn` composed *from the
name* (`…:book.pbschema.6785f134`, not the objectId fallback), the serialized `sdConfig` round-tripped
as an `olio.sd.config` record, the enum read back UPPERCASE, and **a second book with the same slug
rejected** — which is what makes the ratification-7 create-race remedy a measured fact rather than a
declared one.

**Four traps found while proving that round trip. Phase 2c's create paths must handle all four; two
are silent.**
1. **`RecordUtil.applyNameGroupOwnership` does not set `name` on these models.** It sets it only when
   the record inherits `common.name` (`RecordUtil.java:762-764`), and the ratified plain-`name`
   convention deliberately does not. The helper still applies group and ownership, so the call looks
   like it worked. Found by writing a book with a null name. **`olio.narrative` has the identical
   shape and the identical trap.** Pinned by `TestApplyNameGroupOwnershipDoesNotSetNameOnPbModels`.
2. **A null `name` defeats the unique `(name, groupId, organizationId)` constraint** — PostgreSQL
   treats NULLs as distinct, so two null-named rows in one group coexist and produce two identical
   urns, which is exactly the collision ratification 8 asked to close. **The schema cannot close
   this:** the only schema-level guard is the `$notEmpty` `\S` rule the convention exists to avoid,
   and `FieldSchema.isRequired()` is read **only** by `RecordTranslator`, never by the writer or the
   validator. ⇒ every PB2 create path sets the derived name explicitly, or the invariant is unenforced.
3. **`name` and `urn` had to be added to the models' `query` array** (see the deviation list below).
4. Non-query fields are opt-in as documented — `sdConfig` needs an explicit `request`/`planMost`.

**Three deviations from §2.1/§2.2, each deliberate:**
- **`olio.sd.config` is NOT a database-persisted model** (`ioConstraints: ["unknown"]` ⇒
  `DBUtil.isConstrained` true, no table, no `id`). §2.2's *"foreign `olio.sd.config` — **real
  persisted records**, making config queryable"* is therefore not buildable as written: a `foreign`
  field emits a `bigint` column nothing could ever populate. `book.sdConfig`,
  `book.compositeSdConfig` and `artifact.sdConfigSnapshot` are **non-foreign** `model` fields,
  serialized into a `text` column — the shape `olio.pictureBookMeta` already uses, except not
  `ephemeral`. **Consequences to keep stated:** config is *not* queryable by field, and this is one of
  the few things that is **not** DDL-neutral to reverse (non-foreign emits `text`, foreign emits
  `bigint`, and `generatePatchSchema` emits `ADD COLUMN` only) — cheap today only because the columns
  are new and empty. `TestSdConfigFieldsAreSerializedNotForeign` asserts the premise, so it fails the
  day that changes and forces the decision.
  **Stephen approved *planning* the persistence 2026-08-14** — written up as `PictureBook2Plan.md` **§6c**
  (plan only, not scheduled). Headlines: 80 fields; 38 Java references but only **3** in `src/main`
  (`PictureBookUtil`, `SDUtil`, `OlioModelNames`); **five** competing storage shapes for the same config
  today, including **two independent per-character systems that do not talk to each other** (the book
  pipeline's `pictureBookCharacterStyle.sdConfig` vs the Ux's `<name>-SD.json` blob in
  `~/Data/.preferences`); a **silent `groupPath` collision** (the model's own `groupPath` means *output
  destination*, `common.groupExt`'s means *where the record lives*, and depth-first last-wins would
  shadow one) that must be renamed first as its own step; and the Ux being better placed than expected
  because `forms.sdConfig` is **already schema-driven** off `am7model.getModelField("olio.sd.config", …)`,
  making it a storage swap rather than a form rewrite. §6c also notes that `configOverride` must stay a
  sparse JSON string regardless, and that S6 (PB2 fields → references) is materially cheaper **before**
  phase 3 starts writing artifacts.
  **§6c step S1 is already DONE (2026-08-14):** `olio.sd.config.groupPath` → **`imagePath`**, pairing with
  its sibling `imageName`. It was a **live wire contract**, not a dead field — an initial grep of
  `SDUtil`/`PictureBookUtil` showed only *method parameters* named `groupPath`, which made it look unused;
  the actual reader is `OlioService.generateArt:571` (required, 400 if absent) and the writers are five
  bodies in `AccountManagerUx752/src/cardGame/services/artPipeline.js`. Renamed atomically across all three
  layers with no compatibility shim (both sides ship together; the deprecated Ux7 never sent it, and a
  stale client fails loudly with `400 imagePath is required`). `SD_OVERRIDE_SKIP` and the Ux
  `APPLY_EXCLUDE` keep their `groupPath` entries — they become correct for the right reason at S2.
  **Found in passing, behaviour unchanged:** `reimageWithConfig` derives its path from the source data
  record's own group (`OlioService.java:165-172`) and never reads the config field, so the cardGame
  *character* portrait assignment was **already inert** — the character art does not land in the deck art
  dir and never did. Flagged in a code comment rather than fixed (out of scope).
  *Verified:* Objects7 `install` + Service7 `compile` BUILD SUCCESS, `npx vite build` clean,
  `npx vitest run` **445 passed** (the one failing file, `dialog.test.js`, fails to import identically
  with the change stashed out — measured, not assumed), backend **46/46** including a new
  `TestPbModelSchema#TestSdConfigHasNoCollidingGroupPathField` guard.
  *Not verified:* no live card art was generated (needs the Docker stack + SwarmUI), so the end-to-end
  `generateArt` round trip is untested.
- **`query` is `[id, groupId, objectId, ownerId, organizationId, urn, name]`**, not §2.1's five.
  `common.nameId` is where `"query": ["name"]` normally comes from and these models deliberately do
  not inherit it, so without `name` **every default read returns a record whose name is null** — and
  the documented PATCH rule (carry `name`, taken from what you already know) would feed that null
  straight back into a patch. `urn` is there because ratification 8's whole point is portability;
  `common.base` lists it in its own `query` for the same reason.
- **Three constraints beyond the ratified list**, all invariants rather than convenience:
  `(name, groupId, organizationId)` on all eight (this **is** ratification 8's urn-collision guard,
  which is why the derived names matter — an artifact name must include its `revision`, a run name its
  instant), `(book, organizationId)` on `workflow` (§2.2's "one per book"), and
  `(node, role, bindingOrdinal, organizationId)` on `binding`. **`sceneIndex` is deliberately not
  unique**: reordering writes overlapping indices transiently and a unique index would reject the
  intermediate state, which is the whole point of "N patches on `sceneIndex`".

**Enum values the plan did not enumerate** — `PbBookStatusEnumType` (UNKNOWN/DRAFT/EXTRACTING/
EXTRACTED/GENERATING/COMPLETE/FAILED) and `PbRunStatusEnumType` (UNKNOWN/PENDING/RUNNING/COMPLETED/
FAILED, **no CANCELLED** — runs are synchronous with no cancel endpoint, so a value nothing can set
would be a false affordance). Everything else is verbatim from §2.2. Every enum field carries an
explicit `maxLength`, without which `getDataType` emits unbounded `varchar` and `nodeStatus`/`runStatus`
would not have been indexable.

**`olio.pb.artifact.text` → `artifactText`** (Stephen, 2026-08-14: name it for what it contains). §2.2
says `text`, which is both uninformative and the SQL type name; the field holds the **inline payload for
the artifactTypes that have no bytes** — extracted scene text (`TEXT`), the resolved prompt (`PROMPT`),
a serialized structure (`JSON`) — while image artifacts leave it null and carry bytes in `data`.
`artifactText` also reads with its sibling `artifactType`. **Phases 4-5 must use `artifactText`**, the
same way they must use `sceneIndex` and `selected`. The already-created orphan `text` column was dropped
by a targeted `ALTER TABLE … DROP COLUMN IF EXISTS text` on `A7_olio_pb_artifact_0_1` — **0 rows**, and
the column was minutes old from this same work; `db.schema.dropColumns` was left `false` and no other
table was touched. Verified after: the table has `artifacttext` and no `text`.

**Two null-name book rows, created by an intermediate state of the round-trip test before trap 1 was
understood, were deleted** from `A7_olio_pb_book_0_1` (ids 1 and 3). Nothing else in `am7db` was
deleted, dropped or reset; all other DDL was additive.

### Phase 2c — the six utilities, and five defects found by running them

**New (Objects7 main), all in `olio/picturebook/`:** `PbConfigUtil` (sparse override, the §2.4
four-tier precedence merge, `configHash`, and the locale-free hashing primitives every PB2 hash
shares), `PbWatchedFields` (the declared watched field sets + `refHash`), `PbGraphUtil` (build /
`validateAcyclic` / `computeInputHash` / `markStaleDownstream` / `recomputeStatus` **compute-only** /
`nextRunnable` / runs), `PbArtifactUtil` (persist, supersede chain, `setSelected` with a post-write
re-read, structural request sanitization), `PbBookUtil` (ratification-7 create ordering, scenes,
reorder-by-N-patches), `PbSharingUtil` (two-tier membership, promote/copy).
**New (Objects7 test):** `objects/tests/TestPbGraph.java`, `objects/tests/TestPbSecurity.java`.
**Modified (docs, deliberately):** `.claude/rules/model-api.md` — see defects 1 and 2 below.

**Verified 2026-08-16 against `am7db` (`localhost:15432/am7db`, read from `resource.properties:9`),
no reset:** `TestPbGraph` **15/15**, `TestPbSecurity` **10/10**. The 113-test non-regression gate was
re-run (result recorded in §1). Nothing in this phase changed a model JSON, so no DDL was emitted.

**Two answers the phase was required to produce, both now in Appendix D:**
- **Role-hierarchy direction (ratified, §5.3 verification test 1, §10 Q10):** grant on **PARENT** +
  member of **CHILD** → **DENIED**; grant on **CHILD** + member of **PARENT** → **PERMITTED**.
  Membership flows *down* the role tree, grants do not flow *up*. Usable optimisation is therefore
  **enrol high, grant low**; the intuitive inverse silently denies.
- **`olio.sd.config` persistence timing (§6c S2-S6):** the **serialized shape stands**, not scheduled
  ahead of phase 3 — with a correction to §6c.5's cost claim (S6's migration surface is two columns per
  *book*, not per artifact, because §6c.3.3 keeps `sdConfigSnapshot` serialized permanently).

**Five defects found by running the tests. Two were mine; three are pre-existing platform behaviour.**

1. **MINE — a patch built with `RecordFactory.newInstance(model)` overwrites EVERY field.** The bare
   overload materialises every field of the model at its default, and the writer persists everything
   present on the record. Measured: an `olio.pb.book` patch that set only `world` blanked `slug` and
   `description` and reset `bookStatus` to `UNKNOWN`. **`update` returned success**; the damage surfaced
   only as a later `find` on `slug` returning nothing. Fixed by taking the changed field names and using
   `newInstance(model, String[])`. **`model-api.md`'s own PATCH example was the bare form** — corrected
   there, because that example is where the mistake came from.
2. **MINE — a query condition on a `foreign` `model` field takes the RECORD, not its id.**
   `Query.field` routes the value through `FieldUtil.setFlex`, which calls `setModel()` for a
   `MODEL`-typed field, so a `Long` is rejected, the condition silently becomes `<field> = null`, and
   nothing is logged at the call site (`StatementUtil.java:1367` casts the value to `BaseRecord` and
   reads its id itself). Five queries were affected. `model-api.md`'s "Typed query field values" section
   named only `organizationId`/`groupId`; it now states the general rule and this case.
3. **PLATFORM — `AccessPoint.list` is NOT a per-record authorization boundary, and §9's org-wide-list
   assertion is false today.** `find` authorizes the query shape *and then* runs `canRead` on the result
   (`AccessPoint.java:513-517`); `list` (`:623-636`) authorizes the shape and returns whatever `search`
   returned, unfiltered. Measured with two users in one organization: a by-objectId read of another
   user's node is `AUDIT DENY`, while an org-wide list with an explicit numeric `organizationId`
   condition returns it (`AUDIT PERMIT`). **Pre-existing and general to every group-scoped model, not
   introduced here** — but PB2 raises the stakes, because a book's whole graph becomes listable by any
   authenticated user in the organization. Pinned as a labelled characterization
   (`TestPbSecurity#case02_theListPathIsNotASecurityBoundary_MEASURED_DEFECT`) rather than deleted or
   left red, the same way `TestSdConfigFieldsAreSerializedNotForeign` pins a known-wrong shape.
   ⇒ **Phase 4 must not expose a list endpoint over the `olio.pb.*` models until this is resolved** —
   either by constraining on `groupId` / filtering per record in the REST layer, or by fixing
   `AccessPoint.list`. **Needs Stephen's disposition.**
4. **PLATFORM — `planMost(true)` on `olio.pb.workflow`/`olio.pb.run` TERMINATES, but the read returns
   nothing.** Ratification 1's requirement is satisfied: plan construction over the two-hop
   `workflow.lastRun ↔ run.workflow` cycle completes (measured with `lastRun` actually populated, under
   a hard time bound on another thread, so a non-terminating plan could not hang the build). The read
   then fails for an unrelated reason: the plan lists the foreign `book` field while the generated SELECT
   does not emit that column, so the reader throws `PSQLException: The column name book was not found in
   this ResultSet` and `find` reports "No results". ⇒ **the generic
   `GET /rest/model/{type}/{objectId}/full` route would return nothing for these models** — a safer
   failure than unbounded recursion, and the reason every PB2 utility uses an explicit `setRequest`.
   Pinned by `TestPbGraph#case01`, with a positive control proving the explicit projection does work.
5. **PLATFORM — `RecordFactory` cannot instantiate any model under a Turkish default locale, and the
   failure is cached.** `getBaseModel` upper-cases a field's declared type with the *default* locale, so
   `"string"` becomes `STR<dotted-I>NG`, `FieldEnumType.valueOf` throws, the model is built broken, and
   the broken result is cached in `looseBaseModels` for the rest of the JVM — poisoning unrelated tests.
   Found while writing the ratified Turkish-locale case (which now hashes an already-built canonical
   string instead of re-deriving it). **Not fixed: it is not PB2 code**, and the fix is a `Locale.ROOT`
   argument. Worth logging as its own KI.

**Two measured facts phase 4 needs:**
- **The book `Writer` role alone cannot enrol another user.** `OlioContext.register`'s authorizing role
  is the **Admin** tier, so `PbSharingUtil.shareBook` by a mere Writer is refused (403). Since nothing
  auto-enrols into Admin (Appendix D precondition 1), phase 4's member flow needs the org admin or an
  explicit Admin grant — or `registerUser`'s authorizing set has to be widened deliberately.
  `TestPbSecurity#case05` asserts whichever way it behaves and logs which.
- **The ratification-7 create ordering works in an organization with no Olio history.** `createBook`
  writes the book row before the world, which pre-creates the
  `/Olio/Universes/Books/Worlds/{slug}/Book` group skeleton ahead of the universe and world *records*;
  `makePath` is get-or-create, so the subsequent universe/world creation adopts those groups. Measured in
  a randomly-named virgin organization (`TestPbSecurity#case09`), including the duplicate-slug refusal
  and writes into the `Workflow`/`Artifacts` groups the skeleton did not include.

**One deviation from §2.2, forced by ratification 7.** §2.2 says the book lives in `{world}/Book`, and
it does — but that group is created by `BookWorldInitializationRule` *during* `initialize()`, while
ratification 7 requires the row *before* the world. Reconciled by `makePath`-ing `{container}/Book`
directly (olio-user-owned, get-or-create — the rule then adopts it). **The alternative was rejected on
isolation grounds:** the universe's own `Book` group (where `olio.pb.series` lives) is a child of the
universe container, so `resolveGrantTargets` grants Read on it to the shared organization-wide universe
`Reader` role — and every book creator is a member of that role, so every book in the organization would
become listable by every other book's creator.

**One row deleted from `am7db`:** `A7_olio_pb_book_0_1` id 28, created minutes earlier by this same work
and corrupted by defect 1 (null `slug`, blanked `description`, `bookStatus` reset). It blocked the test
fixture. Nothing else was deleted, dropped or reset, and no DDL was executed in this phase.

**Not verified in phase 2c:** no LLM or SD service was called, no image was generated, no REST endpoint
was exercised, and the Docker stack was not rebuilt or started — phase 2c is Objects7-only and talks to
`am7db` directly. `PbSharingUtil.promoteToUniverse` / `copyToChapter` are **covered only by the
authorization refusals in `TestPbSecurity`**; their copy semantics (the seven foreign sub-records per
character, §3.5) are not exercised, because the per-model sub-record routing lands in phase 3 with the
pipeline that knows which groups those are. Said plainly rather than implied by a green suite.

### Phase 3 — the pipeline wired to the graph behind `picturebook.v2`

**Three pre-work items, settled first, none deferred.**

**1. `test.llm.ollama.server` — REPOINTED to `http://192.168.1.42:11434`** (`resource.properties:51`).
Confirmed by Stephen 2026-08-17: *"Use .42 for llm; .39/localhost is running this session and swarm"* and
*"And .42 for embeding"* (`test.embedding.server` was already `.42:8123` — no change needed).
**Safe swap, measured rather than assumed:** `/api/tags` on both hosts lists `qwen3:8b` at the **identical
digest** `500a1f067a9f`, so nothing breaks. Note `way-local:latest` does **not** match across the two
(local `2a206f419e0a` vs `.42` `0b9f7fe5fd0e`) — do not generalise the identical-digest result.
**Two consequences that must be stated, because the one-line fix does not cover them:**
- **Persisted connections are unaffected.** A chat config is get-or-create by name and carries an
  `system.connection` record created with whatever URL was current then. Counted on `am7db`:
  **48 rows at `http://localhost:11434`, 4 at `http://192.168.1.42:11434`.** The property change affects
  only *newly created* connections; the 48 keep pointing at the local box.
- **The gate test was already correct by accident.** `TestPictureBookCustom`'s own connection
  (`PictureBook gpt-oss:120b 4.chat Connection`, id 10) already reads `192.168.1.42:11434` — created when
  the property held `.42`. And `gpt-oss:120b` exists **only** on `.42`. So the misconfiguration was
  invisible precisely because the tests that used `qwen3:8b` (present on both) silently ran on the wrong
  hardware and passed, exactly as §6 predicted.
- Found in passing, not fixed: `PictureBook KI qwen3:8b.chat Connection` exists **14+ times** in
  `a7_system_connection_0_1`. A get-or-create is producing duplicates. Not this phase's scope.

**2. The stack was rebuilt and verified, not assumed.** `mvn -o -pl AccountManagerObjects7 install` →
BUILD SUCCESS; `mvn -o -pl AccountManagerService7 package` → BUILD SUCCESS; then
`docker compose -p am7test -f docker-compose.test.yml up --build -d`. Image `am7:latest` rebuilt
(created `2026-08-16T17:00:46Z`), container `am7test-am7-1` **recreated** from it (`StartedAt`
`2026-08-16T17:00:57Z`, running). Probed from the build host:
`GET /AccountManagerService7/rest/setup/state` **200**, `GET .../rest/schema` **200** (293,572 bytes).
**The WAR genuinely serves this work**, which is the claim that matters: the schema JSON contains all
eight `olio.pb.*` models (`book`, `series`, `scene`, `workflow`, `node`, `binding`, `artifact`, `run`),
the phase-2b rename **`artifactText`**, and the §6c-S1 rename **`imagePath`**. Windows `curl -k` worked
here despite §6's HTTP-000 caveat.
Services re-probed the same day: Swarm `localhost:7801` **302** and `192.168.1.39:7801` **302**; ollama
`.42:11434/api/tags` **200**; embedding `.42:8123/docs` **200**; TTS `.42:8001` **200**; ComfyUI **0.32.0**
via `localhost:7801/ComfyBackendDirect/system_stats` **200** and `localhost:7821` **200**.

**3. `AccessPoint.list` — DISPOSED for PB2; whether to fix `list` itself is REOPENED.** Constrain at the
Objects7 utility layer so phase 3/4 are unblocked; `AccessPoint.list` not changed in this phase. Reasoning
in `PictureBook2Plan.md` Appendix D, logged as **KI-67**. What stands: the behaviour is deliberate and
documented in place (`AccessPoint.java:600-605` is the AM5/AM6 design note — `list` authorizes a *query
shape*), and PB2 already has the correct shape, since every PB2 list is reached from an authorized `find`
of the book (§5.6b's root-reference principle).
**What does NOT stand — my error, corrected by Stephen the same day.** I argued that fixing `list` means an
`AuthorizationUtil.canRead` evaluation **per row**, and made that the load-bearing reason. It is wrong: for
a **parent- or directory-scoped** record the check resolves **at that level first**.
`PolicyUtil.java:761-789` rewrites the policy resource to the **group's urn** for anything inheriting
`data.directory` (`policyBase = g.replaceAll(grp.get(FIELD_URN))`), and `:795-804` does the same via
`parentId`. So N records in one group share **one** policy key — one evaluation, then decision-cache hits,
with a `conditionalPopulate` of identity fields per row (`AuthorizationUtil.java:158`). Every `olio.pb.*`
model inherits `common.groupExt`, so they are exactly the cheap case. ⇒ **the cost objection is void,
fixing `list` is much cheaper than I claimed, and the recommendation is now to fix it.** The one real
remaining consideration is that rows would stop appearing in existing lists — the defect, not a
regression, but still a product-wide change wanting its own baseline.
**Phase 4 constraint recorded:** no generic `/rest/model/search` over `olio.pb.*`, and no listing on a
caller-supplied `groupId`/`organizationId`. **Residual risk left open and stated:** an Objects7 caller
that fabricates a record from an id without an authorized `find` can still list another user's rows.

**New (Objects7 main):** `olio/picturebook/PbFeatureFlag.java` (the `picturebook.v2` switch — deployment-
global, boot-pinned, `volatile`, default **OFF**, wired in all three hosts: `BaseTest`,
`RestServiceEventListener`, `ConsoleMain`), `olio/picturebook/PbPipelineUtil.java` (the seam recorder),
`olio/picturebook/PbSubRecordUtil.java` (sub-record destinations + the canonical narrative path).
**New (Objects7 test):** `objects/tests/TestPictureBookWorkflow.java`.
**Modified:** `PictureBookUtil.java` (v2 seams + the two deletions), `PbArtifactUtil.java` (two fixes,
below), `resource.properties` (`picturebook.v2=false`, the ollama repoint).

**The seam map is the existing `PictureBookProgressNotifier` call sites, not a new cut:**
Stage 0 → `LANDSCAPE_PROMPT` + `SCENE_PROMPT`; `"face"` → one `PORTRAIT` node per character (keyed on the
**character**, not the scene, so the pipeline's existing reuse branch does not fork a version chain);
`"landscape"` → `LANDSCAPE`; `"auto_awesome_mosaic"` → `REFERENCE_STRIP`; `"image"` → `COMPOSITE`.

**`PbPipelineUtil` is FIND-ONLY for the book and its world.** A render is a *use* of a book; a use that
created the book (and so a universe, a world, three groups and a role pair) is the `LibraryUtil`
read-path-that-creates shape `architecture.md` names, and it has been hit twice. A missing book logs at
WARN and v2 recording is skipped. `PbBookUtil.createBook` stays the one creation path. Every v2 call site
in `generateSceneImage` is wrapped in `try/catch(Exception)` and continues: the graph is provenance, and
losing provenance must never lose an image the GPU spent three minutes producing.

**§2.5 artifacts now persisted:** the classic composite canvas and the Kontext stitched strip (both were
`FileUtil.emitFile("./comp-*.png")` / `("./land-*.png")` dumps into the process working directory — those
calls survive only on the v2-**off** path); the **FLUX.2 letterboxed references**, which previously existed
only as base64 inside the request, now `IMAGE` artifacts whose objectIds replace that base64 in the stored
`generatorRequest`; the landscape; portraits; a **per-artifact** `sdConfigSnapshot`; and real attribution
via `portrait0`/`portrait1` bindings where PB1 passes `null, null`.

**`prepareForeignSubModelGroups` + `createPersistedForeignInstance` are DELETED**, along with
`copyBaselineFieldValues` (moved). All eight call sites go through `PbSubRecordUtil`, which resolves the
destination from the `olio.world` group fields (`narratives`, `profiles`, `statistics`, `stores`,
`instincts`, `personalities`, `states` — all already declared in `worldModel.json`) and **falls back to the
legacy `~/{schemaGroup}`** when there is no context, because `createCharPerson` is on the flag-off path.
Narratives now go through **`NarrativeUtil.getCreateNarrative`**, which already builds into
`{world}/Narratives`, creates-or-patches, links back via `Queue.queueUpdate` and flushes. This removes this
pipeline from the set of `~/Narratives` writers — **KI-60's collision target** — without claiming to fix
KI-60, which stays reachable from any `makePath` caller.

**Two defects found by running the test, both mine, both fixed:**
1. **`PbArtifactUtil.SANITIZE_KEYS` held the Java field names, not the serialized ones — so sanitization
   stripped NOTHING and `isSanitized()` reported a FALSE CLEAN.** `SWTxt2Img` annotates
   `@JsonProperty("initimage")` / `("promptimages")` (SwarmUI's wire contract), so `"initImage"` /
   `"promptImages"` never appear in the JSON. A 1.6 MB base64 payload was persisted while the guard
   reported success, because `isSanitized` counts removals and found none. Fixed: both spellings listed and
   matching is **case-insensitive**. Caught by the level-1 assertion on a real FLUX.2 request — a
   substring check would have caught the payload, but only the structural test caught the *false clean*.
2. **A nested foreign record is not usable as an FK value.** Attaching `profile.portrait` (reached through
   its parent) as the artifact's `data` made `AccessPoint.create` return null for both reused portraits,
   with **no artifact row written at all**. Stephen's diagnosis, and the correct one: the query planner
   restricts fields on sub-models to prevent recursion, so the record is not fully identified. Fixed by
   re-reading it as a **top-level `data.data`** (`PictureBookUtil.readDataRecord`) — so the artifact still
   references the character profile's own portrait, not a copy. My first fix copied the bytes into the book
   gallery instead; Stephen corrected that, and the corrected form is what ships.
   **Verification gap on this one, stated because a green run would otherwise imply more than it proves:**
   the level-1 run after the correction passed, but it did **not exercise** the corrected path. The
   copy-based build had already written `portrait r1` for both characters, so `findSelected` returned
   non-null and the reuse branch short-circuited before reaching `readDataRecord`. Proving the corrected
   form requires deleting those two artifact rows (created minutes earlier by this same work) and
   re-running. Until that is done, **the corrected portrait reference is compiled and reviewed but not
   measured.**
   **A third, contributing defect fixed on the way:** `persistArtifact` reported a null create as "the
   unique (producedByNode, role, revision) index rejects a duplicate", which sent the investigation down
   the wrong path. `AccessPoint.create` returns null for a denial *and* for a constraint rejection, so the
   message now names both.

**Verified 2026-08-17 against `am7db` + live Swarm/LLM, no reset:**
- `TestPbGraph` **15/15**, `TestPbSecurity` **10/10** (25 tests run) — the phase-2c suites still green
  after the phase-3 diff.
- `TestPictureBookWorkflow#TestSceneGraphIsRecordedAndStructurallySound` **1/1 PASSED**, 195 s, against
  live Swarm and the real catatone.docx book (Duña/Duna + Jideon de Rosa — the user's real source content,
  not a stand-in). Measured: **7 nodes** recorded (2 prompt, 2 portrait, landscape, reference, composite);
  composite **1,569,441 bytes, decoded 1024×768**, recorded dimensions equal to the decoded ones;
  landscape **1,603,185 bytes, decoded 1024×768** — matching the pipeline's forced size; **3** FLUX.2
  reference artifacts, each **decoded 1024×1024**. Three references is the data-side proof that
  `flux2IncludeLandscapeRef=true` took effect, i.e. the `landscape reference SUPPRESSED` path did not fire
  — asserted from persisted data rather than by scraping the log line, which is the stronger evidence since
  a log assertion passes even if the reference never reached the request.
- The composite was **exported and actually looked at**. It is genuinely catatone scene 1: Jideon carrying
  Duña toward the waiting cab in the rain, graffitied wall, wet cobbles, night, neon. **Visible quality
  defects, recorded rather than glossed:** three human figures for a two-character scene (Duña appears
  twice — once carried, once in the car), the carried figure's hair is blonde where Duña was imprinted
  **Auburn**, confused anatomy around the carried figure, and garbled neon text. These are FLUX.2
  multi-reference likeness behaviours, **not** introduced by the graph wiring — phase 3 changed provenance,
  not generation — but they are real and worth their own issue.

- **Level 2 (the differential, KI-59) PASSED** — `TestLandscapeReferenceChangesTheComposite` **1/1**,
  311 s, two live composites at the **same fixed seed** and the same config, differing only in
  `flux2IncludeLandscapeRef`:
  ```
  revision 4  9d0ce7aa05ff2a3599c3029bd59965e843a271e372a82f03958043dad597fbbb  (landscape ref BOUND)
  revision 5  4f0b5a70a5b33453bf53d1458f276b9e6b893133c9eb6ef99f2837c3f18cb79f  (landscape ref SUPPRESSED)
  ```
  The hashes differ and the revision chain advanced 4 → 5, so the two are a genuine supersede pair rather
  than two unrelated images. The `landscape reference SUPPRESSED` log line fired **exactly once** across
  the whole run — in leg B only — which is the direct confirmation that the lever did what the test
  claims and that leg A did not silently suppress it. **This is the first human-free evidence that the
  landscape reference reaches the model**, which is what KI-59 asked for.
  **The same-seed-same-hash corollary is deliberately NOT asserted:** seed determinism has not been
  established for this Swarm/FLUX.2 pair, and §9 says drop the corollary rather than weaken it into a
  tautology. Consequence to keep stated — a differing pair is also what a non-deterministic backend
  produces, so this PASS is **necessary but not sufficient**; establishing determinism would upgrade it.
- **The exported composites were looked at, both legs.** With the reference bound and with it suppressed,
  both are recognisably the scene; with it suppressed the setting still lands, carried by prompt text
  only. Both show the same likeness defects recorded above.
- **Style fidelity is wrong, and the prompt CONTRADICTS ITSELF. Found by Stephen 2026-08-17, correcting a
  wrong claim of mine** — I first wrote that "the request honours the style", which it does not.
  `style=photograph` does reach the request (*"**Photograph** taken with a Kodak Brownie box camera and
  Canon FD 50mm f/1.8 lens using Lomography 100 film processed with Kodacolor by James Van Der Zee."*),
  and then **the same positive prompt says "no photograph"**:
  ```
  ... Photograph taken with a <camera/lens/film/process/artist>. Preserve facial identity ...
  ... Do not draw the reference images themselves - no photograph, poster, screen, mirror,
      billboard, framed picture or character sheet anywhere in the scene. ...
  ```
  FLUX.2 is an instruction-following edit model and this clause is in the **positive** prompt, so the
  medium is asserted and negated in one breath. That explains the digital-art output far better than the
  step budget does, and it is why my first diagnosis (4 steps / the `8k ultra realistic` boosters) was at
  best secondary.
  **`SWUtil.java:311-319` already knows about this bug class and the earlier fix was incomplete.** The
  comment at `:317-319` records removing `"photographic"` from this very clause because *"saying it twice
  in different words is how a comic-styled book ended up being told 'photographic' mid-prompt"* — but
  `"no photograph"` was left in the same sentence at `:315`, so the word survived in its **negated** form
  and now collides with the style vocabulary instead of duplicating it.
  **One-word fix, deliberately NOT applied in this run:** drop `photograph` from the forbidden-objects
  list at `:315-316` — `poster, screen, mirror, billboard, framed picture, character sheet` already
  expresses "do not render the reference as a depicted object", and `photograph` is the only item that
  collides with the medium. It is left unapplied because `SWUtil.newFlux2SceneTxt2Img` is the **shared**
  builder (chat uses it too), the change alters generation output on the v2 **and** flag-off paths, and the
  non-regression gate was already in flight against the current string — changing it mid-gate would
  invalidate the gate being reported. Logged as **KI-68**.

- **The flag-off non-regression gate PASSED.** `TestPictureBookCustom#TestPictureBookCustomPipeline`
  **1/1**, 196 s, live Swarm, `BUILD SUCCESS`, with the test file **unedited** — that is the condition
  that makes it a gate. Two checks that make the result mean what it claims: the run emitted **zero** PB2
  recording lines (`picturebook.v2=false` was genuinely in force, so this exercised the PB1 path, not v2
  silently succeeding), and `git diff` on both `TestPictureBookCustom.java` and `pom.xml` is **empty**.
  **Correction to an earlier note in this file:** `pom.xml:110`'s `TestPictureBookCustom` exclude is
  already inside a `<!-- -->` block, so it needed **no** pom edit. My first attempt commented it again and
  produced a nested XML comment (illegal — *"in comment after two dashes (--) next character must be >"*),
  which made the POM non-parseable and the "gate run" never started. Restored; the pom is untouched.

- **The non-regression gate PASSED: `Tests run: 152, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS**
  — the 113-test gate plus the phase-2b/2c suites, re-run after the phase-3 diff (which touched
  `PictureBookUtil` and `BaseTest`, so this was not a formality). Per suite: `TestGameUtil` 25,
  `TestBookWorld` 21, `TestGameUtilSync` 15, `TestOlioGameFeatures` 15, `TestPbGraph` 15,
  `TestPictureBookKnownIssues` 15, `TestPbModelSchema` 14, `TestPbSecurity` 10, `TestSchemaIndexPatch` 9,
  `TestPictureBookSceneAuthz` 7, `TestNestedStructures` 3, `TestOlio2` 1, `TestOlioRules` 1,
  `TestOlioCacheScope` 1.

**NOT verified — say so plainly:**
- **NO PORTRAIT WAS EVER GENERATED IN ANY OF THESE RUNS. Every run relied on the persisted test
  portraits** (Stephen, 2026-08-17). All five live runs logged *"Reusing persisted portrait for Jideon de
  Rosa (no re-render)"* / *"… for Duña"*, so the **portrait RENDER branch never executed**. Consequences,
  all of them real:
  - the `PORTRAIT` artifact rows in `am7db` (ids 41-42) were written by the **reuse** branch, carry
    `nodeStatus = DONE_UNVERIFIED`, and describe a portrait whose config and seed are genuinely unknown —
    which is why that status exists, but it is not the same claim as "the portrait node works";
  - the render branch's artifact recording — including `readDataRecord` on the freshly created image, the
    `portCfg` snapshot and the `extractSeedFromImage` seed — is **entirely unexercised**;
  - level 1 therefore never asserted on a freshly-rendered portrait, only on the landscape, the three
    references and the composite.
  - the corrected profile-portrait reference (defect 2) is also unexercised for the same reason: the reuse
    branch short-circuits on the artifact the earlier copy-based build already wrote.
  **What would actually exercise it:** force a re-render rather than deleting fixture data — set
  `params.isBookOverride = false` (the render-use-delete fallback), or give a character scene-tagged
  apparel so `selectSceneApparel` returns true and the reuse branch is bypassed by design, or bump
  `TestPictureBookCustom`'s `REIMAGE_CHARS`. Then assert a `PORTRAIT` artifact at `revision >= 1` whose
  node is `DONE` (not `DONE_UNVERIFIED`), with a non-null seed and a `sdConfigSnapshot`.
- **THE SUB-RECORD REROUTE IS UNEXERCISED, and it is the riskiest part of the phase-3 diff.** Deleting
  `prepareForeignSubModelGroups` / `createPersistedForeignInstance` touched **8 call sites** inside
  `createCharPerson`, plus the narrative path. Nothing in this session ran it: the flag-off gate reused the
  cached catatone content (log: *"Reusing existing catatone book"*, *"Reusing 41 cached catatone scenes
  (skipped LLM extraction)"*), so `createFromScenes` → `createCharPerson` never executed — grepping the
  gate log for `PbSubRecordUtil` / `getCreateNarrative` / `Could not pre-resolve` returns **0**. It
  compiles and the call sites were repointed one by one, but "compiles" is not "works".
  **What would actually exercise it:** a character creation from scratch — a fresh book slug or
  `iter`/`clearSceneCache` bumped in `TestPictureBookCustom` so `createFromScenes` runs a real extraction,
  then assert that the seven sub-records (`profile`, `narrative`, `statistics`, `store`, `instinct`,
  `personality`, `state`) land with non-zero ids and that the narrative carries `sdPrompt`. Both the
  world-group destination (with an `OlioContext`) and the legacy `~/{schemaGroup}` fallback (without one)
  need a case, since the fallback is what keeps flag-off behaviour identical.
- No REST endpoint exercised the v2 path, and no Playwright ran. Phase 3 is Objects7-only.

### DAL — index generation
`generateIndices` is now recalled on the **schema-patch** path, not only at CREATE TABLE, with
`CREATE [UNIQUE] INDEX IF NOT EXISTS` and per-statement error-log-and-continue. Indexability is keyed off
the **emitted SQL type**, so `type:"model", foreign:true` fields (plain `bigint` columns) are indexable at
last. Extended to dedicated participation tables, columns included.

**9 indexes created on `am7db`, 0 failures** — the six models whose logs had been warning
`cannot be indexed in the database`. `data.groupExport`'s documented "one export per source group per
organization" invariant is now **actually enforced**. All three unique indexes created cleanly, so no
existing row violated a declared constraint.

Two gaps existed and they are **different**, which matters for reading old logs:
- **Gap A** — foreign-model fields rejected at *CREATE TABLE* time, so those indexes never existed at all.
  This was live and explains the 9.
- **Gap B** — indexes never patched onto an *existing* table, i.e. a hint added to a model whose table
  already exists is silently never created. **Latent in `am7db`** (`pg_indexes` 728 before, 728 after)
  because its tables were created after the hints existed. No manual DDL was ever needed or performed.

Also found and fixed: PostgreSQL truncates identifiers at 63 bytes, so 8 participation index names
exceeded it and the "already exists" skip missed every one, re-issuing no-op DDL on every open.

### `PathUtil`
**D1 reproduced and fixed.** `~` expansion did **no normalization**, so a remainder that already began
with the home path re-emitted the whole path beneath itself — `~/home/<u>/X` → `/home/<u>/home/<u>/X`,
returning an ordinary group with **no signal to the caller**. Now normalized, with a WARN naming input,
home and resolved path so the offending call site becomes findable.
**F4 fixed** — a pre-create re-read on the constraint key runs before the insert, so a
guaranteed-to-collide INSERT is never attempted. Zero `duplicate key value violates` lines in the whole
verification run, where there were several before. This is KI-60's *"the INSERT is still attempted on
every run"* symptom.
**F5 fixed** (new defect, not previously catalogued) — the `utype` override forced the *lookup* type to
`DATA` for `home`/owner segments while the *create* used the original type, writing a node its own lookup
could never see. Reaches only non-DATA groups (`BUCKET`, `USER`, `ACCOUNT`), which are rare — **not
presented as resolving KI-60.**
**KI-60 watch added** — marker `KI60_WATCH_MARKER`, fires on both adoption branches, read-only, carries
requested vs effective type, adopted id/name/parent/org/type/urn, path and segment index; `ANOMALY` at
ERROR on name mismatch; and a **one-shot uncached re-probe** that records in words whether the cache is
implicated. First live sample already says `reprobe=MISSED … the miss is below the cache`.

**Two standing KI-60 theories eliminated empirically** (recorded in `KnownIssues.md`): the
`CacheDBSearch` query-key theory (cached and uncached return identical ids; `fieldKey` includes the
*value*; `addToCache` only caches `count > 0`, so a miss is never cached) and the wrong-*name* adoption
(did not reproduce — recorded honestly as "not reproduced under a different pre-state", not "disproven").
**The live KI-60 miss — a DATA lookup failing to see a DATA row — remains unexplained.** KI-60 stays OPEN.

---

## 4. The one RED test — needs a decision

```
TestPathUtilBehavior.TestD3MismatchedTypeRequestIsNotSilentlySatisfiedByAnotherType:518
  expected:<[DATA]> but was:<[BUCKET]>
```

**The two D3 tests are mutually exclusive under every safe implementation.** Part 1 (green, a designated
guard) requires the same row back — i.e. return the BUCKET row. Part 2 requires that row's type to be
DATA.

| implementation | part 1 | part 2 |
|---|---|---|
| adopt (current) | ✓ | ✗ |
| throw | ✗ (turns 3 green guards red) | ✗ |
| add `type` to the `auth.group` constraint | ✗ (two rows, different id) | ✓ |
| retype the existing row in place | ✓ | ✓ |

Only retyping satisfies both, and it was **rejected**: `PathUtil` writes via `writer.write` with only a
*create* policy evaluation, so retyping would let any caller with create rights silently mutate an
existing record's type — including an `auth.role` type, which changes authorization semantics — from what
is nominally a path resolution. That is an unauthorized-write defect, not a fix.

Throwing was **measured, not assumed**: it turns `TestD1IntermediateSegmentOfAnotherTypeStillResolves…`,
`TestD2SiblingSetRecovery…` and `TestD3MismatchedTypeRecoveryReturnsARecordWithTheRequestedIdentity` red
and still doesn't make part 2 green. Experiment reverted.

**Constraint change — recommended AGAINST.** Adding `type` to `auth.group`'s constraint relaxes it, so no
existing row could violate the new index, **but the old index is not dropped automatically** and would
keep enforcing the collision until dropped by hand. Worse, it is semantically wrong: paths resolve by
*name*, so permitting a DATA and a BUCKET `/home/u/X` makes every type-less path lookup ambiguous and
lands in `makePath`'s `nodes.length > 1` branch — which logs `Invalid search …` and then **falls through
without updating `node`/`parentId`**, returning the *previous* segment's node. The type-less constraint is
load-bearing; the real fix is at call sites.

**Current behaviour:** adopt, and log the conflict at ERROR naming both types and stating plainly that the
caller did not get what it asked for.

**Options for the red test — your call:**
1. `@Ignore` it with a javadoc pointing at this section (keeps the suite green, keeps the gap visible).
2. Leave it RED as a standing reminder (honest, but `mvn test` is red).
3. Fix the call sites so a type-mismatched path request never happens, then it becomes moot.

It was left RED rather than quietly weakened.

---

## 5. Files

**New (Objects7 main):** `olio/picturebook/BookContext.java`, `olio/picturebook/PbOlioContextUtil.java`,
`olio/picturebook/PictureBookCancelRegistry.java`, `olio/rules/BookWorldInitializationRule.java`.

**New (Objects7 test):** `objects/tests/TestPathUtilBehavior.java`, `objects/tests/TestPathUtilKi60Watch.java`,
`objects/tests/TestPictureBookSceneAuthz.java`, `objects/tests/TestSchemaIndexPatch.java`,
`olio/TestBookWorld.java`, `olio/TestOlioCacheScope.java`, `olio/picturebook/BookContextTestAccess.java`.

> **Note the two test packages.** `TestBookWorld`, `TestOlioCacheScope` and `BookContextTestAccess` sit in
> the **production** package `org.cote.accountmanager.olio[.picturebook]` deliberately — to reach
> package-private members (`PbOlioContextUtil.assembleBookContext`, `OlioUtil.nameInDirExists`) **without
> widening any production modifier**. Precedent: `olio/llm/TestChatMemoryPipelineWiring.java`. Do not
> "tidy" them into `objects.tests.*`; the package-private boundary is a deliberate authorization control.

**Modified in phase 2a (Objects7 main):** `olio/OlioContextConfiguration.java` (universe role pair +
`scanNestedWorldGroups`), `olio/OlioContext.java` (two-tier grant passes, `registerUniverseUser` +
`register` extraction, `scanNestedWorldGroups()`), `olio/picturebook/PbOlioContextUtil.java` (the two
universe roles, both-tier creator enrolment, two-role `verifyGrants`).
**Modified in phase 2a (Objects7 test):** `olio/TestBookWorld.java` (case03 universe leg re-addressed,
case09 fixture suffixed, case19 + case20 added).

**New in phase 2b (Objects7 main):** eight model JSONs under
`src/main/resources/models/olio/pb/` (`bookModel.json`, `seriesModel.json`, `sceneModel.json`,
`workflowModel.json`, `nodeModel.json`, `bindingModel.json`, `artifactModel.json`, `runModel.json`);
six enums in `schema/type/` (`PbBookStatusEnumType`, `PbGraphStatusEnumType`, `PbNodeTypeEnumType`,
`PbNodeStatusEnumType`, `PbArtifactTypeEnumType`, `PbRunStatusEnumType`).
**Modified in phase 2b (Objects7 main):** `olio/schema/OlioModelNames.java` (eight `MODEL_PB_*`
constants + registration in `MODELS`), `olio/schema/OlioFieldNames.java` (the `FIELD_PB_*` constants
and the three group-name constants), `olio/sd/SDAPIEnumType.java` (`COMFY`, one value, no behaviour).
**New in phase 2b (Objects7 test):** `objects/tests/TestPbModelSchema.java`.

**New in phase 2c (Objects7 main):** six utilities under `olio/picturebook/` — `PbConfigUtil.java`,
`PbWatchedFields.java`, `PbGraphUtil.java`, `PbArtifactUtil.java`, `PbBookUtil.java`,
`PbSharingUtil.java`. No model JSON, constant or enum changed, so phase 2c emitted **no DDL**.
**New in phase 2c (Objects7 test):** `objects/tests/TestPbGraph.java`, `objects/tests/TestPbSecurity.java`.
**New in phase 3 (Objects7 main):** `olio/picturebook/PbFeatureFlag.java`,
`olio/picturebook/PbPipelineUtil.java`, `olio/picturebook/PbSubRecordUtil.java`.
**New in phase 3 (Objects7 test):** `objects/tests/TestPictureBookWorkflow.java`.
**Modified in phase 3:** `olio/picturebook/PictureBookUtil.java` (the v2 seams; `readDataRecord`; the two
deleted methods and their 8 repointed call sites; `SceneGenerationParams.bookSlug`),
`olio/picturebook/PbArtifactUtil.java` (wire-name `SANITIZE_KEYS` + case-insensitive matching; the
misleading create-failure message), `objects/tests/BaseTest.java` (flag wiring),
`src/test/resources/resource.properties` (`picturebook.v2=false`, ollama repointed to `.42`),
Service7 `rest/config/RestServiceEventListener.java` and Console7 `console/ConsoleMain.java` (flag wiring).
**`picturebook.v2` is DECLARED in all four config surfaces**, matching how `llm.ollama.unload` is handled -
it is read by code in three hosts, so a key that is read but never declared is undiscoverable:
`AccountManagerObjects7/src/test/resources/resource.properties:37`,
`AccountManagerConsole7/src/main/resources/resource.properties:45`,
`AccountManagerService7/src/main/webapp/WEB-INF/web.xml:52` and `docker/web.xml.template:52`
(both `<context-param>`, hardcoded `false`, **no `${...}` envsubst placeholder** - deliberately matching
`llm.ollama.unload`; `docker/entrypoint.sh` regenerates `web.xml` from the template on every boot, so a
value edited into a deployed `web.xml` is silently discarded). Caught by Stephen 2026-08-17: the first pass
declared it only in the Objects7 test properties while the Console7 and Service7 readers were already wired,
so the flag existed but was invisible in two of the three hosts.
*Verified after:* `web.xml` still parses, `mvn -o -pl AccountManagerService7 package` and
`-pl AccountManagerConsole7 compile` both BUILD SUCCESS.

**Phase 3 emitted no DDL** - no model JSON, constant or enum changed.

**Modified in phase 2c (docs):** `.claude/rules/model-api.md` — the PATCH example was itself the bare
`newInstance(model)` form that caused defect 1, and the typed-query-field section named only the two id
fields; both corrected, plus a new section stating that `AccessPoint.list` is not a per-record
authorization boundary.

**Modified (Objects7 main):** `factory/Factory.java`, `io/IOSystem.java`, `io/db/DBUtil.java`,
`olio/AddressUtil.java`, `olio/CharacterUtil.java`, `olio/ColorUtil.java`, `olio/Decks.java`,
`olio/GeoLocationUtil.java`, `olio/OlioContext.java`, `olio/OlioContextConfiguration.java`,
`olio/OlioContextUtil.java`, `olio/OlioUtil.java`, `olio/WorldUtil.java`,
`olio/picturebook/PictureBookUtil.java`, `parsers/data/WordParser.java`, `util/MemberUtil.java`,
**`util/PathUtil.java`** *(note: `util/`, not `io/`)*.

**Modified (Objects7 test):** `objects/tests/olio/OlioTestUtil.java`, `TestGameUtilSync.java`,
`TestOlio2.java`, `TestOlioGameFeatures.java` — each **only** `setEnrolActingUser(true)`; no assertion
touched.

**Modified (Service7):** `rest/services/CacheService.java`, `rest/services/PictureBookService.java`.

**Docs:** `aiDocs/PictureBook2Plan.md` (Appendix C + D + ratifications), `aiDocs/KnownIssues.md` (KI-60
characterization), `aiDocs/CanvasLibraryResearchPrompt.md` (new), this file.

---

## 6. Environment & commit notes

- **DB — CORRECTED 2026-08-14.** `am7db` is at **`localhost:15432`**, in the disposable `postgres`
  container (`pgvector/pgvector:0.8.2-pg18-trixie`, `0.0.0.0:15432->5432`). A second container `am7-pg`
  sits on `15433`. The earlier claim in this file that `test.db.url` had been repointed to **15430** was
  **wrong** — the committed `AccountManagerObjects7/src/test/resources/resource.properties:9` reads
  `jdbc:postgresql://localhost:15432/am7db` and is not modified in the working tree, so every phase-1 and
  phase-2a test run went to **15432**. Read the file, not this line, if it matters again.
  **Never use the docker `am7test` DB for Objects7 JUnit — the keys won't match.**
- **Reset permission (Stephen, 2026-08-14) — supersedes the blanket prohibition** in
  `.claude/rules/architecture.md` "Hard prohibitions" and `CLAUDE.md`, both of which still say never reset
  and that Stephen does it himself. **`am7db` and `am7test` MAY be reset. `am72db` must NEVER be reset or
  dropped** — it is Console7's target (`resource.properties:11`, `15432/am72db`) and shares host:port with
  `am7db`, so the *database name* is the thing to check, not the port. The two rules files have not been
  edited to match; that is Stephen's call.
- **`pom.xml`:** six `<exclude>` lines are commented out as `PB2-GATE` so the gate can run. Stephen will
  set these to his liking. A surefire profile (`-Ppb2-gate`) would be the cleaner mechanism than
  commented-out build config.
- **Services — RE-PROBED 2026-08-14, supersedes the previous bullet. Phase 3 is no longer blocked on
  reachability.**
  **Canonical topology (Stephen, 2026-08-14): SwarmUI on `localhost` / `192.168.1.39`; LLM and embedding
  on `192.168.1.42` (the Spark).** Measured with `curl` from the build host:

  | Service | Intended host | Probe | Result |
  |---|---|---|---|
  | SwarmUI | localhost / .39 | `localhost:7801`, `192.168.1.39:7801` | **302** (up, both) |
  | Ollama (LLM) | **.42** | `192.168.1.42:11434/api/tags` | **200** (up) |
  | Embedding | **.42** | `192.168.1.42:8123` | **up** — `/docs` 200 (FastAPI); `/` and `/embed` 404, so don't probe those and conclude it's down |
  | TTS | .42 | `192.168.1.42:8001` | **200** (up) |
  | ComfyUI | **bundled with Swarm** | `localhost:7821/system_stats`, `localhost:7801/ComfyBackendDirect/…` | **200 — RUNNING** (see below) |

  The previous claim that embedding/ollama were unreachable is **stale** — all of them are up.
  Note the `000` vs `404` distinction: a 404 means the HTTP server answered, so it is **up**; only `000`
  is a connection failure.

  **ComfyUI is NOT a separate install — SwarmUI bundles and self-starts it** (Stephen, 2026-08-14:
  *"Self-Start ComfyUI-0 on port 7821 started."*). This corrects two earlier notes in this file and in
  §6/ratification 12 of the plan, both of which probed **`.39:8188`** — the wrong port — and concluded
  Comfy was unavailable. Measured: **ComfyUI 0.32.0**, device `cuda:0 AMD Radeon(TM) 8060S Graphics :
  native` (the local Strix Halo iGPU), reachable two ways:
  - **direct:** `localhost:7821` — **localhost only**; `192.168.1.39:7821` is `000`, so from another host
    this route does not exist;
  - **through Swarm:** `localhost:7801/ComfyBackendDirect/…` and `192.168.1.39:7801/ComfyBackendDirect/…`
    both 200 — the route to prefer, since it works off-box and needs no second port opened.

  **This does not un-backlog Comfy** (ratification 12 stands: `SDAPIEnumType.COMFY` exists, no behaviour,
  SwarmUI remains the only backend). It does mean that when §6 is picked up there is **nothing to install**
  and the port question is already answered.

  **⚠ One config line disagrees with the canonical topology, and it fails silently.**
  `AccountManagerObjects7/src/test/resources/resource.properties:51` reads
  `test.llm.ollama.server=http://localhost:11434`, but LLM belongs on **`.42`**. A **local** ollama is
  also listening on `localhost:11434` (it answered 200 and holds `qwen3:8b` 5.2 GB + `way-local:latest`
  24.5 GB), so tests will happily run against the **wrong box** and pass — no error, just the wrong
  hardware. `test.embedding.server` is already correct at `192.168.1.42:8123`, and
  `test.swarm.server=http://localhost:7801` is correct for Swarm.
  **Left unedited deliberately** — it is Stephen's config file, nothing is broken today, and the fix is
  one line. Decide before Phase 3: repoint `:51` to `http://192.168.1.42:11434`, or confirm the local
  ollama is intended for tests.
- **Docker: available, but the stack must be REBUILT before it reflects this work.** Phase 2b changed the
  Objects7 jar (new models, constants, enums) and phase-2b/S1 changed the Service7 WAR
  (`OlioService.generateArt`) and the Ux752 bundle (`artPipeline.js`). A running container built before
  today serves none of it. Rebuild + redeploy via the repo-root `docker-compose.yml` / `Dockerfile`
  (`aiDocs/DockerComposeDesign.md`) before any REST or Playwright verification, or you will be testing
  yesterday's WAR and blaming the code. Objects7 JUnit does **not** need the stack — it talks to `am7db`
  and the services above directly.
- **No reset was used.** (Not because one is forbidden — see the reset-permission bullet above — but as a
  statement of fact about what these phases did.) Nothing in this work reset, dropped or altered a table or index; all DDL executed
  was additive `CREATE INDEX IF NOT EXISTS`.
- **Untracked and NOT part of this work** (do not commit): `META-INF/MANIFEST.MF`, `kontext-test-output/`,
  `media/*.doc[x]`. Pre-existing and unowned: `log4j2.xml:4` `log-path=c:/projects/logs` (a
  developer-local absolute path in a deployable artifact) and `context.xml`'s plaintext DB password.

---

## 7. Next steps, in order

1. ~~**Phase 2a** — two-tier role split + `scanNestedGroups` on the book path.~~ **DONE** — see §3.
   The two-role property it unblocked is asserted in `TestBookWorld` case19 for now; `TestPbSecurity`
   itself is still phase-2 test work (item 4), and the case19 fixture is the shape to lift into it.
2. ~~**Phase 2b** — the eight `olio.pb.*` model JSONs, registered, tables + indexes verified.~~
   **DONE 2026-08-14** — see §3. The former 2b/2c split (write-but-don't-register plus a DDL pre-flight
   test) was withdrawn before the work started, and nothing about the run argued for it back: the tables
   and all their indexes landed on the first JUnit run, and the two corrections that *were* needed
   (`name`/`urn` in the `query` array) were found by a round-trip test, which a DDL pre-flight would not
   have caught.
3. ~~**Phase 2c — the utilities.**~~ **DONE 2026-08-16** — see §3. All four 2b create-path traps are
   handled (derived names set explicitly everywhere; artifact names carry `revision`, run names their
   instant; explicit `request` projections throughout). Running it found five defects, two of them mine
   (a full-instance patch overwriting every field; a foreign-field condition passed as an id) and three
   pre-existing platform ones.
4. ~~**Phase 2 tests**~~ **DONE 2026-08-16** — `TestPbGraph` 15/15 (including the `planMost(true)`
   termination case for the `workflow.lastRun` ↔ `run.workflow` cycle, with `lastRun` actually populated),
   `TestPbSecurity` 10/10, and the **role-hierarchy direction test run with its result recorded in
   Appendix D**: membership flows DOWN the role tree, grants do not flow UP.
5. ~~**Decide `olio.sd.config` persistence timing.**~~ **DECIDED 2026-08-16: the serialized shape
   stands**, S2-S6 not scheduled ahead of phase 3 — recorded in Appendix D with a correction to §6c.5's
   cost claim. `PbConfigUtil.bookConfig()` is the single seam S6 would have to move.

~~**NEW, needs Stephen's decision before phase 4:**~~ **DISPOSED 2026-08-17 — see §3's Phase 3 entry,
`PictureBook2Plan.md` Appendix D and KI-67.** `AccessPoint.list` performs **no per-record
authorization** — an org-wide list with an explicit numeric `organizationId` returns another user's
group-scoped records, while the by-identity read of the same record is correctly denied (§3, defect 3).
Pre-existing and general, but PB2 makes a whole book graph listable. Either constrain on `groupId` /
filter per record in the REST layer, or fix `AccessPoint.list`.

6. ~~**Phase 3** — pipeline to graph behind `picturebook.v2`.~~ **DONE 2026-08-17, partially verified** —
   see §3. The `test.llm.ollama.server` line is **repointed to `.42`** (Stephen confirmed), the stack was
   rebuilt and probed, and the `AccessPoint.list` question is disposed. **Two gates still owe a run:** the
   flag-off `TestPictureBookCustom#TestPictureBookCustomPipeline` and the 113-test non-regression gate.
7. **Phase 4 — THIS IS NEXT.** Remaining REST endpoints (the two auth fixes are already hoisted out).
   The stack was rebuilt and verified on 2026-08-17 (§3's Phase 3 entry), so that bullet's warning is
   satisfied for now — but rebuild again after any Objects7/Service7 change, since phase 3 changed the jar.
   **Phase 4 is bound by the KI-67 disposition:** every list endpoint takes a **book objectId**, reads the
   book with `AccessPoint.find`, and delegates to the Objects7 utility. No generic `/rest/model/search`
   over `olio.pb.*`; no listing on a caller-supplied `groupId`/`organizationId`.

**Phase 3's four open gaps, in the order they should be closed (each is a real gap, not a formality):**
   1. **No portrait was ever rendered** — every run reused the persisted portraits, so the portrait render
      branch, its artifact recording, `readDataRecord` on a fresh image, the `portCfg` snapshot and the
      seed capture are all unexercised. Force a re-render (`isBookOverride=false`, scene-tagged apparel, or
      `REIMAGE_CHARS`) and assert a `PORTRAIT` artifact whose node is `DONE`, not `DONE_UNVERIFIED`.
   2. **The sub-record reroute never ran** — the gate reused the cached catatone book, so
      `createFromScenes` → `createCharPerson` did not execute. This is the riskiest part of the phase-3
      diff (8 call sites + the narrative path) and it only compiles. Needs a from-scratch character
      creation, with a case for both the world-group destination and the legacy fallback.
   3. **KI-68 — the FLUX.2 prompt asserts and then negates its own medium** (`"Photograph taken with a …"`
      then `"no photograph"`). One-word fix identified at `SWUtil.java:315`, deliberately not applied: it
      is the shared builder (chat uses it too) and wants a before/after visual comparison.
   4. **KI-67 — whether to fix `AccessPoint.list` is REOPENED.** The cost argument against it was mine and
      was wrong (the check resolves at the group/parent level, so it is one evaluation per group, cached —
      not per row). Recommendation is now to fix `list`.

**Smaller items still open:** `tagApparelSceneIndex` (`PictureBookUtil:1171`) has the same missing book
check the hoisted patch fixed elsewhere; Q5 (make `OlioContext` **throw** on authorization failure instead
of swallowing — approved, all callers); the one-line KI-60 identity assertion on the existing
`TestKi42…` test; the `test.llm.ollama.server` line in §6; and **the cardGame character portrait does not
land in the deck art dir** — `artPipeline.js` sets `imagePath` before POSTing to `/reimage`, but
`reimageWithConfig` derives the path from the source data record's own group and never reads it
(`OlioService.java:165-172`). Pre-existing, found during the S1 rename, flagged in a code comment, **not
fixed** (out of scope).

**`PathUtil` findings reported but deliberately NOT changed:** `findPath` is not synchronized (bypasses
the monitor `makePath` holds); `nodes.length > 1` logs `Invalid search` then falls through **without
updating `node`/`parentId`**, silently returning the previous segment's node; `makePath` cannot create
intermediate segments for groupId-based models (`FieldException` → caught → null); and
`PathProvider.provide` / `RecordUtil.resolveUserPath` each duplicate the `~` expansion and are **not**
normalized by this patch, so a doubled path can still be written back into a record's `path` field.
`expandHomePath` is `protected static` and reusable for that follow-up.
