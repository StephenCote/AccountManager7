# PictureBook 2.0 — Implementation State

**As of:** 2026-08-14 · **Pause point:** all in-flight work complete; nothing half-applied.
**Next up:** Phase 2a (two-tier role split + `scanNestedGroups`).
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
| Phase 2a-2d — implementation | **NOT STARTED** | — |
| Phase 3 / 4 | **NOT STARTED** | — |
| Phase 5 (Ux) / 6 (migration) | out of scope this run | — |

**One test is RED, deliberately.** See §4. Everything else passes.

---

## 2. Full test inventory (all against `am7db`)

```
TestBookWorld                 19    olio book compartment, fresh-org H2 cases, evict scoping
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

- **DB:** `am7db` at **`localhost:15430`** (not 15432 — `test.db.url` updated, 1-line diff). Console7's
  `resource.properties:11` still points at `15432/am72db`, a different database, deliberately untouched.
  **Never use the docker `am7test` DB for Objects7 JUnit — the keys won't match.**
- **`pom.xml`:** six `<exclude>` lines are commented out as `PB2-GATE` so the gate can run. Stephen will
  set these to his liking. A surefire profile (`-Ppb2-gate`) would be the cleaner mechanism than
  commented-out build config.
- **Services:** SwarmUI `192.168.1.39:7801` reachable; embedding `.42:8123`, ollama `.42:11434` and
  ComfyUI `.39:8188` were **not** reachable from the build host at last check (possibly a local firewall
  or interface binding). `test.swarm.server` and `test.llm.ollama.server` still point at `localhost` and
  will need repointing before Phase 3.
- **Never `-Dreset`.** Nothing in this work reset, dropped or altered a table or index; all DDL executed
  was additive `CREATE INDEX IF NOT EXISTS`.
- **Untracked and NOT part of this work** (do not commit): `META-INF/MANIFEST.MF`, `kontext-test-output/`,
  `media/*.doc[x]`. Pre-existing and unowned: `log4j2.xml:4` `log-path=c:/projects/logs` (a
  developer-local absolute path in a deployable artifact) and `context.xml`'s plaintext DB password.

---

## 7. Next steps, in order

1. **Phase 2a** — two-tier role split (`universeAuthorizationUserRole/AdminRole`, null-default so grid
   keeps the universal role) + `scanNestedGroups` on the book path. **Do this before the models**: it is
   an authorization-only diff whose regression baseline is the current green gate, and `TestPbSecurity`'s
   two-role case cannot be written until the universe tier exists.
2. **Phase 2b** — the eight `olio.pb.*` model JSONs, unregistered, + the DDL pre-flight test.
3. **Phase 2c** — register in `OlioModelNames.MODELS`; verify indexes actually created.
4. **Phase 2d** — `PbConfigUtil`, `PbWatchedFields`, `PbGraphUtil`, `PbArtifactUtil`, `PbBookUtil`,
   `PbSharingUtil`.
5. **Phase 2 tests** — `TestPbGraph`, `TestPbSecurity`, and the **role-hierarchy direction test**
   (approved: grant to a parent role, enrol in the child only, assert whether `AccessPoint` permits;
   record the result in Appendix D — §10 Q10 depends on it).
6. **Phase 3** — pipeline to graph behind `picturebook.v2`. Needs ollama **and** embedding reachable.
7. **Phase 4** — remaining REST endpoints (the two auth fixes are already hoisted out).

**Smaller items still open:** `tagApparelSceneIndex` (`PictureBookUtil:1171`) has the same missing book
check the hoisted patch fixed elsewhere; Q5 (make `OlioContext` **throw** on authorization failure instead
of swallowing — approved, all callers); and the one-line KI-60 identity assertion on the existing
`TestKi42…` test.

**`PathUtil` findings reported but deliberately NOT changed:** `findPath` is not synchronized (bypasses
the monitor `makePath` holds); `nodes.length > 1` logs `Invalid search` then falls through **without
updating `node`/`parentId`**, silently returning the previous segment's node; `makePath` cannot create
intermediate segments for groupId-based models (`FieldException` → caught → null); and
`PathProvider.provide` / `RecordUtil.resolveUserPath` each duplicate the `~` expansion and are **not**
normalized by this patch, so a doubled path can still be written back into a record's `path` field.
`expandHomePath` is `protected static` and reusable for that follow-up.
