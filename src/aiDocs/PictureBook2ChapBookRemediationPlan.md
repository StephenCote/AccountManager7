# PictureBook 2 / ChapBook Remediation Plan

**Status:** OPEN — remediation not started.
**Recorded:** 2026-08-24.
**Basis:** three adversarial audits that read real source and ran real tests against the live
Docker stack (`am7test-am7-1`, DB `am72db`, mapped `0.0.0.0:9443->8443`). This plan supersedes the
"done/green" claims in the PB2/ChapBook status docs and memories, which were overclaimed.

> **Why this exists.** Prior "complete/green" reports were written from weak or unrun tests
> (env-gated skips, `toBeVisible` without visual proof, backend suites that never touch the broken
> path). Stephen's assessment — both features are unusable end-to-end — is corroborated by evidence.
> Every item below carries the audit evidence that established it. Nothing here is claimed fixed.

## Ground rules for the whole remediation (non-negotiable)

- **No "done" without a real test + visual proof.** For any generative image/content path: a
  Playwright screenshot **and** the extracted image file on disk. A passing decode/`toBeVisible`
  assertion is not proof (`feedback-visual-inspection-required`).
- **Test content = the real poems**, not synthetic stand-ins (`feedback-use-real-test-content`):
  `volatile/poemsXml/txt/<collection>/<poem>.txt` — 146 real Stephen Cote poems in 11 collections
  (busstop, cates, desert, embryo, grace, leaf, lyrics, poison, singles, winter, wounded). Each file
  is `Title  by Author  (Year)` then stanzas. **Select 10 at random** for each end-to-end run.
- **Schema changes go through the included framework only.** The `text=bigint` fix (Phase A) must be
  a general `DBUtil` + `IOSystem` column-type-patch step gated behind a new off-by-default property —
  **no hand-rolled JDBC `ALTER`, no PbBook-specific names.** Architect sign-off required before coding.
- **DB safety:** `am72db` is NEVER touched by hand (no DDL/migration/SQL — the app patches its own
  schema at boot via the framework). Never pass `-Dreset`/`isReset`. `ensureSharedTestUser()`, never
  admin. SD server `192.168.1.39:7801` (Swarm); LLM `192.168.1.42:11434` (Ollama) — not
  interchangeable, `.42` crashes under sustained SD load; LLM/SD tests `--workers=1`, env-gated.
- **Objects7 skips tests by default** — always `-DskipTests=false`.
- **Playwright base URL for the current stack:** `https://127.0.0.1:9443` (port `8443` is not
  published; `9443->8443` is). Confirmed by a live 14-passed/2-failed run during the audit.

## Verified gap list (consolidated, evidence-tagged)

### BLOCKERS — feature is non-functional for a real user

| # | Gap | Evidence |
|---|-----|----------|
| B1 | **ChapBook: no poem-creation UI.** `createPoem()` (chapBook.js:60) is dead code; no Add-Poem form/button; only path is generic `POST /rest/model`. | ChapBook audit gap 4 |
| B2 | **ChapBook Analyze button 400s.** `analyzePoem()` (chapBook.js:31-38) POSTs no body; `analyzePoemTheme` returns 400 without `chatConfig` (ChapBookService.java:129-134). | ChapBook gap 1 |
| B3 | **ChapBook render has no UI trigger.** Backend `renderChapBook` works (audit rendered 2/2 scenes live) but `chapBook.js` never calls `/render`; reachable only via raw REST. | ChapBook gap 2 |
| B4 | **Character extraction creates no book/universe/world.** `PictureBookUtil.createFromScenes` (:3485) writes characters into legacy home-dir groups `~/Data/PictureBooks/<name>`; even the PB2 overload (:3700) routes characters through the same legacy groups. Characters land in the user home dir = UAT#1. | PB2 backend #4 |
| B5 | **`text=bigint` unrepaired for pre-S6 DBs.** `bookModel.json` `sdConfig`/`compositeSdConfig` are `foreign:true` (bigint FK) since S6; `DBUtil.generatePatchSchema` emits only `ADD COLUMN` (:979) / `DROP COLUMN` (:1039) — no `ALTER COLUMN TYPE`. A pre-S6 DB keeps `text`; book reads fail `operator does not exist: text = bigint`. | PB2 backend #5 |
| B6 | **COMPOSITE node = 501 stub** — the node that produces the final viewable page image. `PbNodeExecutor.java:226` throws 501; canvas "Test" cannot generate final scene images. | PB2 backend #1, frontend gap 2 |
| B7 | **Workflow `/workflow` and `/stale` 404 for a real ChapBook.** Live REST tests both failed 404 for a freshly created book — the canvas has no graph to render. | frontend gaps (spec:529, spec:632) |
| B8 | **Workflow canvas is a viewer, not an editor.** Pan/zoom only; no node drag, reparent, or edge creation (`pictureBookWorkflow.js`). Stephen: "not done in any usable sense — add comprehensive implementation and testing." | frontend gap 1 + explicit directive |

### MAJOR — works but incomplete or unverified

| # | Gap | Evidence |
|---|-----|----------|
| M1 | **PbNodeExecutor covers 4/17 node types** (PORTRAIT, LANDSCAPE, SCENE_PROMPT, LANDSCAPE_PROMPT). ~11 throw 501; class javadoc is stale. | PB2 backend #1 |
| M2 | **Cross-owner list leak.** `AccessPoint.list` does not filter per record; `TestPbSecurity` asserts the defect and warns "Phase 4 must not expose a list endpoint over olio.pb.* until resolved." | PB2 backend bonus |
| M3 | **PbMigrationUtil untested in practice.** `POST /migrate-v1` wired but `TestPbMigration` skips (no PB1 fixture seeded). | PB2 backend #2 |
| M4 | **Phase 1b universe/world not threaded at the service layer.** 7 call sites use the 2-arg hardcoded form (GameService:75; OlioService:76,202,319,376,393; GameStreamHandler:109). | PB2 backend #6 |
| M5 | **`olio.cb.book` orphaned.** `createChapBook` builds `olio.pb.book` (bookType=CHAPBOOK), never `olio.cb.book`. | ChapBook gap 6 |
| M6 | **`olio.cb.set` stub.** No membership endpoints; `fetchSets()` (chapBook.js:54) dead; not in UI. | ChapBook gap 5 |
| M7 | **No ChapBook unit tests; canvas e2e skips by default** (gated behind `WORKFLOW_BOOK_GROUP_OID`). "8/8 green" could not be reproduced. | both audits |

### MINOR / DESIGN DEBT — record and decide

| # | Gap | Evidence |
|---|-----|----------|
| D1 | `olio.pb.castGroup` orphan model — registered, table created, used by nothing. | PB2 backend #7 |
| D2 | ChapBook render synchronous + generic prompt (`"landscape, <title>, <mood> atmosphere…"`), no WebSocket progress → gateway-timeout risk on large books. | ChapBook gap 3, D6 |
| D3 | No post-create navigation after ChapBook creation. | ChapBook gap 7 |
| D4 | UAT#2 (denoise 0-1 vs 0-100) and UAT#3 (new-book sdConfig defaults) **appear RESOLVED per code** — confirm with a live test, then close. | frontend gaps 7,8 |

### CORRECTED — NOT a gap (do not "fix")

- **M3-old (OlioContext auth-before-initialized): ALREADY FIXED.** A distinct `authorizationConfigured`
  flag exists (OlioContext.java:153), set only after both `configureWorldAuthorization` calls (:939);
  the catch re-throws when `!authorizationConfigured` (:948-954). TestPbSecurity 10/10 green. The
  earlier plan's premise is stale — drop it.

## Phased remediation (ordered by dependency, then severity)

Each phase runs the orchestrator pipeline: **planner → architect (design review) → specialist
implement → test-author (real tests) → verifier → architect sign-off.**

### Phase A — `text=bigint` via the framework (B5) — FOUNDATION, do first
- **Owner:** backend-specialist; **mandatory architect sign-off before coding.**
- Add to `DBUtil`: a method to read each column's `data_type` from `information_schema.columns`; a
  general `getMismatchedColumns(ModelSchema)` (expected vs actual SQL type); a general
  `generateAlterColumnTypeSchema(ModelSchema)` emitting
  `ALTER TABLE <t> ALTER COLUMN <col> TYPE <expected> USING <col>::<expected>;`.
- Wire it into the `IOSystem.java:143-157` schema-scan loop, **gated behind a new off-by-default
  property** mirroring `isDropColumns()`. General — no PbBook names anywhere.
- **Verify:** a JUnit test that stands up a table with a deliberate `text` column where the model says
  `bigint`, runs the patch step, asserts the column becomes `bigint` and a book read succeeds. Real DB
  (`am7test`), never `am72db`, never reset.

### Phase B — ChapBook made usable end-to-end (B1, B2, B3, M5, D3)
- **B1 poem creation:** `POST /olio/chap-book/poem` in ChapBookService (creates `olio.cb.poem` in
  `~/Poems`, group-if-absent, returns identity). "Add Poem" form in chapBook.js PoemLibrary (title,
  author, multi-line text). Replace dead `createPoem()`.
- **B2 Analyze:** fix `analyzePoem()` to send the required `chatConfig`; or make the endpoint resolve a
  default chatConfig server-side. Confirm no 400.
- **B3 render trigger:** wire a "Render" control in chapBook.js → `/render`; show progress/result.
- **M5:** decide `olio.cb.book` vs `olio.pb.book(bookType=CHAPBOOK)` — ratify one, remove the orphan.
- **D3:** navigate to the book/viewer after create.
- **Verify (real poems):** Playwright in `e2e/chapBook.spec.js` — pick 10 random poems from
  `volatile/poemsXml/txt`, create each via the new Add-Poem form, build a ChapBook, Analyze (LLM,
  `--workers=1`, env-gated), Render (SD, env-gated), **screenshot + extract the rendered image files**.

### Phase C — Node executor + book creation (B4, B6, M1)
- **B4:** make the wizard/extraction path create `olio.pb.book` + universe + world via
  `PbBookUtil.createBook` / `PbOlioContextUtil.getCreateBookContext`; stop routing characters to
  `~/Data/PictureBooks/<name>`.
- **B6 + M1:** implement COMPOSITE (img2img: reference strip init + portrait IP-adapter) and the
  remaining meaningful node types; anything genuinely deferred throws **501** (not 400) with a clear
  message. Update the stale javadoc.
- **Verify:** `TestPbCanvas` gains real LANDSCAPE/SCENE_PROMPT/COMPOSITE cases (SD/LLM env-gated,
  `--workers=1`), each asserting a persisted artifact + **extracted image file**.

### Phase D — Workflow canvas: real editor + real coverage (B7, B8, M7)
- **B7:** root-cause the `/workflow` + `/stale` 404 for a native book (create-doesn't-build-nodes vs
  endpoint resolution) and fix so a freshly created book returns a real graph.
- **B8:** implement node drag, reparent, and edge/connection creation in `pictureBookWorkflow.js`
  (currently pan/zoom only). This is the "comprehensive implementation" Stephen asked for.
- **M7:** un-skip the canvas e2e (remove the `WORKFLOW_BOOK_GROUP_OID` default-skip); add ChapBook
  unit tests. Canvas e2e must render real `[data-node-id]` cards, drive Test/Stale, and assert a new
  artifact with a screenshot.
- **Verify:** `npx vite build` + `npx vitest run` + `e2e/pictureBookWorkflow.spec.js` all green,
  default-on (not skipped), against `https://127.0.0.1:9443`.

### Phase E — Multi-book isolation + safety (M2, M4, M3)
- **M4:** thread optional `universeObjectId`/`worldObjectId` through the 7 service/socket call sites;
  Ux passes the current book's world when available.
- **M2:** constrain every `olio.pb.*` list endpoint by `groupId` (or filter per record) — do not rely
  on `AccessPoint.list`. Add a two-user test proving no cross-owner leak.
- **M3:** seed a PB1 fixture and make `TestPbMigration` actually run (not skip).

### Phase F — Design-debt disposition (D1, D2, D4)
- **D1:** ratify or remove `olio.pb.castGroup`.
- **D2:** wire `PictureBookProgressNotifier.chirpUser` into the render loop; make prompts
  stanza-specific; consider async for large books.
- **D4:** live-confirm UAT#2/#3 resolved, then close.

## Definition of done (whole remediation)
A real user, as `ensureSharedTestUser()`, can: create poems from 10 real poem files → build a
ChapBook → Analyze (LLM) → Render (SD) → **see the generated images** (screenshot + extracted files);
and open the workflow canvas for that book, edit the graph, Test a node, and see a new artifact —
all proven by default-on Playwright runs against `https://127.0.0.1:9443`, with backend JUnit green
(`-DskipTests=false`), and nothing claimed done without that evidence.
