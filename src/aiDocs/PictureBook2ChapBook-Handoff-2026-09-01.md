# PB2 / ChapBook — Session Handoff (2026-09-01)

**Purpose:** self-contained continuation point for a fresh conversation. Read this first, then the
source-of-truth docs it points to. Written at the end of the session that closed the
`PictureBook2ChapBookGapAnalysis-2026-08-31.md` items.

**Design of record (unchanged):** `PictureBook2Plan.md` (Appendix D wins), the running log
`PictureBook2ImplementationState.md`, the gap analysis `PictureBook2ChapBookGapAnalysis-2026-08-31.md`,
and the earlier `PictureBook2ChapBookRemediationPlan.md`. This handoff does **not** supersede them; it
records the delta since the gap analysis and the current commit/test state.

---

## 0. Commit state — CORRECTION

Everything below is **committed**, not uncommitted.

- **HEAD = `89414d85` "Patch" (2026-09-01 09:52:30 -0500).** It contains all the feature edits, the
  four hardening items, and the two new test files. Working tree is clean for every touched file
  (`ChapBookUtil.java`, `poemModel.json`, `PictureBookService.java`, `TestChapBookDeleteAuthz.java`,
  `chapBookDeleteAuthz.spec.js`).
- **The commit was made outside this conversation** (matches the repo's terse "Patch" message style;
  the owner runs local git). This session's git operations were **read-only** (`status`, `log`,
  `show`, `ls-files`). An earlier in-session statement of mine ("Nothing committed") was true when
  said but is now stale — the correct current state is: committed in HEAD `89414d85`, tree clean.
- Remaining `git status` noise is **pre-existing and unrelated** (`resource.properties`,
  `log4j2.xml`, `context.xml`, deleted `media/speaker_0.mp3`, `test-results/*.png`, `__pycache__/`,
  `plan.json`, `logs/`). None of it is part of this work.

---

## 1. What shipped this effort

### Part 1 — Vite dev server against Docker (DONE)
The Vite dev server (`:8899`/proxy) now targets the Dockerized Service7 stack instead of an ad-hoc
local Tomcat. Complete and in use for the E2E work below.

### Part 2 — remaining gap-analysis features (DONE)
From `PictureBook2ChapBookGapAnalysis-2026-08-31.md`:

| Item | What | Where |
|---|---|---|
| **C1** | `configOverride` (sparse JSON) field on `olio.pb.scene` | scene model + `PbConfigUtil` |
| **C2** | ChapBook render resolves via `PbConfigUtil.resolveEffectiveConfig` | `ChapBookUtil` render path |
| **C3** | Poem `book` FK + book-scoped import/list | `poemModel.json`, `PbBookUtil`/import path |
| **C4** | `sceneRequest` projection (returns override + effective config) | `PictureBookService` |
| **C5** | Per-scene SD-config override: backend endpoint + UI editor | `PictureBookService.setSceneConfigOverride` + Ux752 |
| **D5/D6** | Analyze persistence + role gating | analyze endpoint + role check |
| **B1** | Issue-13 "N-not-N-1" regression test | `TestPictureBookUtilE2E.java` (gated by `PICTUREBOOK_E2E`) |
| **X-DELETE** | Typed `PictureBookException` with HTTP status on ChapBook delete | `ChapBookUtil.deleteChapBook` |

**Config precedence chain** (`PbConfigUtil.resolveEffectiveConfig(book, scene/node, composite)`):
node/scene `configOverride` (sparse) → book `sdConfig`/`compositeSdConfig` → resource defaults →
FLUX.2 defaults. **ChapBook scenes have no `sceneNode`, so the SCENE is the override carrier** — this
is why C1 puts `configOverride` on the scene.

### Part 3 — four hardening items (DONE; owner-approved "apply all")
| Item | Change | File |
|---|---|---|
| **A** | PBAC delete denial → HTTP **403** (explicit `canDelete` PERMIT check before `AccessPoint.delete`, so a denial is distinguished from a persistence failure that would otherwise read as 500) | `ChapBookUtil.deleteChapBook` (~686-706) |
| **B** | Scoped-import requires only `canRead` — **kept as-is**, documented as intended (owner chose "Read is enough (current)"). Doc-only note on `resolveScopeBook` (~564-581) | `ChapBookUtil` |
| **C** | Drop inert `likeInherits` → real `inherits: ["data.directory"]` | `poemModel.json` (lines 3-5) |
| **D** | `user == null` → 401 guard | `PictureBookService.setSceneConfigOverride` (~778) |

**Item A control flow** (`deleteChapBook`): 400 null args → 404 not found → 403 not `CHAPBOOK` →
403 `canDelete` DENY → `AccessPoint.delete`. Key hardening:
```java
// AccessPoint.delete re-checks canDelete; the double check is intentional (correctness over a saved eval).
PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canDelete(user, user, book);
if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
    throw new PictureBookException(403, "Not authorized to delete ChapBook: " + bookObjectId);
}
return IOSystem.getActiveContext().getAccessPoint().delete(user, book);
```

**PBAC reachability nuance (confirmed live), important for anyone touching item A:** by default a
non-admin user cannot even **READ** another user's group-scoped record (AUDIT DENY → `readBook`
null → **404, not 403**), so the DENY→403 path is unreachable through the REST surface with ordinary
users. The per-book **Writer** role carries **Delete** on the book's OWN world groups (only the
SHARED universe corpora are never-Delete), so Writer enrolment can't produce a delete denial either.
The only way to reach the DENY branch is a **targeted Read-only entitlement**
(`AuthorizationUtil.setEntitlement(orgAdmin, deniedUser, {bookGroup}, {"Read"}, {DATA, GROUP})`) —
which is exactly how the JUnit test provisions it.

---

## 2. Tests written this effort (all real, run against live backend)

| Test | Kind | Status |
|---|---|---|
| `TestChapBookDeleteAuthz#deleteChapBook_readableButNotDeletable_throws403` | JUnit (Objects7) | PASS — `Tests run: 1, Failures: 0`. Actor = non-admin `denied` user; admin used only to provision the fixture (create user + targeted Read-only `setEntitlement`, no Delete). Positive control asserts `readByDenied != null` (reaches `canDelete`, not 404); asserts thrown `PictureBookException.getStatus()==403` + message; non-destructive survival check. |
| `chapBookDeleteAuthz.spec.js` | Playwright REST (3 cases) | PASS — 404 `{"error":"ChapBook not found"}`, 403 `{"error":"Book ... is not a CHAPBOOK"}`, 200 `{"deleted":true}` + follow-up empty search + repeat-DELETE 404. Uses `ensureSharedTestUser()` (not admin). Case 4 (DENY) honestly documented as **REST-unreachable**, deferred to the JUnit test above. |
| `pbConfigOverride.spec.js` | Playwright (PB2 scene config-override) | PASS. SD-render secondary case gated behind `CONFIG_OVERRIDE_RENDER`. |
| `chapBookScopingOverride.spec.js` | Playwright (C3 scoped-import + C5 UI editor) | PASS. |
| `chapBookRoleGate.test.js` | Vitest (role-gate) | PASS. |
| B1 regression case | JUnit in `TestPictureBookUtilE2E.java` | PASS, gated by `PICTUREBOOK_E2E` env. |

Run the JUnit delete-authz test:
```
cd src && mvn -o -pl AccountManagerObjects7 -DskipTests=false \
  -Dtest=TestChapBookDeleteAuthz#deleteChapBook_readableButNotDeletable_throws403 test
```

**Fixture correction worth remembering:** the first JUnit attempt provisioned the denied user via the
per-book **Writer** role (copying `TestPbSecurity` case06) assuming Writer = read-but-not-delete. It
FAILED — audit log showed `AUDIT PERMIT ... to DELETE olio.pb.book`, because the per-book Writer role
DOES carry Delete on the book's own world groups. The working fixture uses a targeted Read-only
`setEntitlement` instead.

---

## 3. Review verdicts

- **Feature work:** verifier PASS, security-reviewer PASS, architect APPROVE.
- **Final hardening (A/B/C/D):** verifier **CONFIRMED** — all four correct/low-risk; both modules
  compile (Service7 BUILD SUCCESS, Objects7 install/test-compile clean); `olio.cb.poem` initializes
  cleanly in the live container log after `likeInherits` removal (the only Index-collision errors in
  the log are pre-existing `iso42001.*` ones, unrelated); both new tests real (no skip/`.only`/
  `@Ignore`, load-bearing assertions, non-admin actor).

---

## 4. Deferrals (do NOT attempt without an explicit go-ahead)

- **X-async — DEFERRED.** Make ChapBook render asynchronous. Held for a final wave from a green
  baseline because it changes the **synchronous render request/response contract that C2 depends on**.
- **B5 — owner-only.** `sdConfig` is stored as a `text` column; the redesign wants a `bigint` FK.
  That is a **non-additive** schema change needing an owner-run migration. Surface it to the owner;
  do not attempt (never touch the untouchable local `am72db`; see §6).

---

## 5. Out-of-scope observations (tracked, NOT to be drive-by fixed)

Per the standing scope-discipline rule — note, don't touch, unless asked:
- `iso42001.*` redundant `common.nameId` index-collision at startup (root cause unaddressed).
- `PbBookTypeEnumType.PICTUREBOOK` deserialization warnings.
- `"OlioContext - Root Epoch is null"` log line.
- B1's environmental first-connect LLM latency (test is correct; the slowness is the cold connection).

---

## 6. Standing environment & testing constraints (in force, verbatim intent)

- **USE DOCKER** for E2E. Stack: `src/docker-compose.test.yml`; container `am7test-am7-1`; host
  **`https://127.0.0.1:9443`** (nginx→8443); context path `/AccountManagerService7`. Backend DB is
  the **Docker `am72db` on container `am7-pg` — RESETTABLE**. This is a *different* database from the
  **untouchable LOCAL `am72db`**.
- **NEVER touch the local `am72db`** — no DDL, migrations, SQL, resets, `-Dreset`. (The owner does
  schema resets.) The resettable Docker `am72db` and `am7db`/`am7test` are separate and OK.
- **NEVER use the admin user for testing** — use `ensureSharedTestUser()` / `ensureIso42001TestUser()`
  from `e2e/helpers/api.js`.
- **LLM is live at `192.168.1.42:11434`; SD (Swarm) is live at `192.168.1.39:7801`.** Not
  interchangeable — `.42` crashes under sustained SD load. Docker cannot reach the LAN, so SD/LLM
  paths must run against the **local Eclipse Tomcat**, not Docker (see `troubleshooting.md`).
- **Do not commit or push unless explicitly asked.** (This session: "Don't commit.")
- **Do not weaken, skip, or fake tests.**

### Redeploy mechanics (Docker test stack)
```
# build
cd src && mvn -o -q -pl AccountManagerObjects7 install -DskipTests && mvn -o -pl AccountManagerService7 compile
# hot-deploy into the running container
export MSYS_NO_PATHCONV=1
docker cp .../AccountManagerObjects7/target/*.jar am7test-am7-1:.../WEB-INF/lib/
docker cp .../AccountManagerService7/target/classes/. am7test-am7-1:.../WEB-INF/classes/
docker exec am7test-am7-1 bash -c 'pgrep -f catalina | xargs -r kill'   # SIGTERM; supervisord restarts; exit 143 is expected
```
Objects7 POM **skips tests by default** — always pass `-DskipTests=false`, and confirm a
`Tests run: N` line with N > 0 (a bare `BUILD SUCCESS` proves nothing).

---

## 7. Suggested next steps for a new conversation

1. Confirm the green baseline still holds (build + the tests in §2) before starting X-async.
2. If X-async is approved, treat it as its own wave: design the async render contract first
   (planner → architect), because C2's synchronous resolve/render path is the thing it changes.
3. Surface B5 (text→bigint `sdConfig`) to the owner as a migration decision; do not attempt it.
4. Leave the §5 out-of-scope items alone unless explicitly asked to pick one up.
