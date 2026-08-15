# PictureBook 2.0 — Implementation State

**As of:** 2026-08-14 · **Pause point:** all in-flight work complete; nothing half-applied. Nothing committed.
**Next up:** Phase 2c (the utilities — `PbConfigUtil`, `PbWatchedFields`, `PbGraphUtil`, `PbArtifactUtil`,
`PbBookUtil`, `PbSharingUtil`). **Read §3's Phase 2b entry first — it lists four traps 2c's create paths
must handle, one of which silently writes a null name.**
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
| Phase 2c — utilities | **NOT STARTED** | — |
| Phase 3 / 4 | **NOT STARTED** | — |
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
3. **Phase 2c — the utilities. THIS IS NEXT.** `PbConfigUtil`, `PbWatchedFields`, `PbGraphUtil` (build /
   `validateAcyclic` / `computeInputHash` / `markStaleDownstream` / `recomputeStatus` — **compute-only**,
   ratification 2 / `nextRunnable`), `PbArtifactUtil` (persist + sanitize + supersede chain +
   `setSelected` with a post-write re-read), `PbBookUtil`, `PbSharingUtil`.
   **Four traps from 2b that the create paths must handle — two are silent, all four are in §3:**
   (a) `applyNameGroupOwnership` does **not** set `name` on these models — set the derived name
   explicitly; (b) a null `name` defeats the unique `(name, groupId, organizationId)` constraint, which
   is ratification 8's urn-collision guard, so the derived names are load-bearing (**artifact names must
   include `revision`**, run names their instant); (c) `name`/`urn` are in the `query` projection but
   nothing else is — non-query fields need an explicit `request`; (d) `computeInputHash` must name
   SHA-256 at the call site and encode explicit UTF-8 (`CryptoUtil.defaultHashAlgorithm` is a mutable
   static currently on SHA-512 and `getDigestAsString` uses the platform charset — Appendix D).
   Use the ratified field names: `sceneIndex`, `selected`, **`artifactText`**.
4. **Phase 2 tests** — `TestPbGraph` (including the `planMost(true)`-terminates case for the
   `workflow.lastRun` ↔ `run.workflow` cycle — 2b asserted the cycle **exists** and is bigint-symmetrical,
   not that a plan terminates), `TestPbSecurity`, and the **role-hierarchy direction test**
   (approved: grant to a parent role, enrol in the child only, assert whether `AccessPoint` permits;
   record the result in Appendix D — §10 Q10 depends on it).
5. **Decide `olio.sd.config` persistence timing — this competes with 3 and 4 for position.**
   Plan §6c, S1 **done**. **S6 (PB2 config fields → foreign references) is the one non-DDL-neutral step,
   and it is cheap now while those columns are new and empty and expensive once phase 3 starts writing
   artifacts.** So either do S2-S6 before phase 3, or accept the serialized shape for the foreseeable
   future and say so. Not a decision to leave implicit.
6. **Phase 3** — pipeline to graph behind `picturebook.v2`. **No longer blocked on service reachability**
   (§6: Swarm, ollama, embedding and TTS all measured up) — but settle the one-line
   `test.llm.ollama.server` question in §6 first, or it silently runs on the wrong box.
7. **Phase 4** — remaining REST endpoints (the two auth fixes are already hoisted out). **First REST or
   Playwright work must rebuild the Docker stack** — the running containers predate phase 2b and S1 (§6).

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
