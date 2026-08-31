# PictureBook 2 / ChapBook — Comprehensive Gap Analysis

> **Status:** OPEN — analysis only, nothing fixed. Written 2026-08-31 for use as the entry
> point of a fresh working conversation.
> **Method:** four parallel read-only audits (backend design/impl, frontend UX, the full test
> picture, and the documented history/overclaim trail). All file:line references below were
> collected by those audits against the working tree as of 2026-08-31.
> **Companion docs:** `PictureBook2ChapBookRemediationPlan.md` (the prior authoritative OPEN
> plan — B1–B8/M1–M7/D1–D4), `PictureBook2ImplementationState.md` (phase tracker — treat its
> "DONE" markers as claims, not evidence), `PictureBook2Plan.md`, `PictureBookDesign.md`.

---

## 0. Why this document exists (read this first)

The recurring problem on PB2/ChapBook is **not primarily bad feature code — it is a broken
verification loop that reports "green" for code that was never actually exercised.** Every prior
"complete / tested / N/N green" claim on these features has later been rejected by the owner. The
single most important section here is **§4 (The Axle)** — the concrete, reproducible reasons the
test-pass claims keep being false. Fix the verification loop *before* trusting any new feature work,
or the next session will repeat the pattern.

The one PB2/ChapBook claim in the entire history that carries real end-to-end evidence (a live
LLM+SD run with decoded, inspected image artifacts) is **Issue 13** (`TestPictureBookUtilE2E`,
2026-08-29). It is the template for what "done" must look like.

---

## 1. Design intent (target state)

### PictureBook 2 (PB2)
A node/workflow-driven image pipeline:
- A `Books` **Olio universe**, one **world per book**, owned by the **olio principal**.
- `olio.pb.book` (bookType=STORY) owns one `olio.pb.workflow`; scenes (`olio.pb.scene`) each point
  at an `olio.pb.node` (`sceneNode` FK) that produces imagery.
- Image config resolves through a **four-tier precedence chain** (`PbConfigUtil.java:39-48`):
  node `configOverride` (sparse JSON) → book `sdConfig`/`compositeSdConfig` → resource-file
  defaults → `Flux2Defaults`.
- Characters are LLM-extracted from a source document into fully-populated `olio.charPerson`
  records (profile, statistics, store/apparel, narrative/portrait prompt) in the book's world group.
- Two-tier PBAC: universe Reader/Writer + per-book world roles.

### ChapBook (poetry variant)
The owner's intended **chapbook-first flow**:
> create empty chapbook → set SD config for that chapbook → import poems (queue **scoped to the
> chapbook**) → review/edit pages, **updating SD config PER PAGE like in PictureBook** → render images.

Landscape-only pipeline (no characters/Kontext/composite), one landscape image per stanza-scene,
with text overlay. Models exist (`olio.cb.book`, `olio.cb.poem`, `olio.cb.set`); scenes carry
ChapBook fields (`poemStanza`, `sdPrompt`, `imageObjectId`, `pageFont`, `pageBgColor`,
`pageTextAlign`).

---

## 2. What's actually implemented

### 2.1 Backend

**Implemented / working:**
- Empty-book create: `PbBookUtil.createBook` + bookType patch already yields an empty book.
- Book-level SD config for PB2: `GET/PUT /{bookObjectId}/settings`
  (`PictureBookService.java:649-697`) → `PictureBookUtil.getBookSdConfig`/`setBookSdConfig`.
- PB2 node/workflow config machinery (the four-tier resolver) is mature.
- ChapBook is implemented on `olio.pb.book` with `bookType=CHAPBOOK` (NOT on `olio.cb.book`):
  `ChapBookUtil.createChapBook()` → `PbBookUtil.createBook()` → bookType patch → chunk poems into
  one scene per stanza. `createChapBookScene` LLM-generates `sdPrompt` (as of the 2026-08-29 fix)
  with a stanza-excerpt fallback.
- REST auth: `PictureBookService` and `ChapBookService` are `@RolesAllowed({"admin","user"})`.
- `.doc`/`.docx`/`.rtf`/`.wpd` text extraction consolidated in `DocumentUtil.OFFICE_CONTENT_TYPES`
  (2026-08-31), `TestDocumentExtraction` passing.

**Concrete backend gaps:**
1. **No per-scene SD config.** `olio.pb.scene` has a bare `sdPrompt` string and **no**
   `sdConfig`/`configOverride` field (`sceneModel.json:86-93`). The redesign's per-page config
   edits have nowhere to persist for ChapBook scenes.
2. **ChapBook render bypasses the tiered resolver.** `ChapBookUtil.java:705-707` uses
   `SDUtil.randomSDConfig()` + optional `applyOverrides(clientSdConfig)` — it never calls
   `PbConfigUtil.resolveEffectiveConfig`, and ChapBook scenes have no `sceneNode`. **So
   `PUT /{book}/settings` does not govern ChapBook rendering at all.**
3. **Poems are not scoped to a chapbook.** `olio.cb.poem` inherits `data.directory` only and has
   **no book FK** (`poemModel.json`); `createChapBook` takes a flat `poemObjectIds` list. The
   intended "queue scoped to the chapbook" has no backing relation (`olio.cb.set` exists but unused).
4. **ChapBook scene projection omits page-style fields.** `PbBookUtil.sceneRequest()` (~line 474)
   projects `poemStanza`/`sdPrompt`/`imageObjectId` but **not** `pageFont`/`pageBgColor`/
   `pageTextAlign` — declared but never read back, so page styling silently won't round-trip.
5. **Business-logic leak in `ChapBookService` DELETE** — re-implements the readBook + bookType check
   that already lives in `ChapBookUtil.deleteChapBook` (violates Service7 transport-only rule).
6. **Synchronous render** (`ChapBookService`, `TODO(ChapBook async)` ~lines 258-260) — runs on the
   request thread; gateway-timeout-prone for multi-page books.
7. **`olio.cb.book` re-declares `maxLinesPerPage`/`overlayOpacity`** already on parent
   `olio.pb.book` — duplicate-inherited-field smell (harmless only because cb.book is unused).

**PB2 character-creation "fails at the last step" — prime suspect identified:**
`createCharPerson` (`PictureBookUtil.java:2800-3257`) hard-aborts (returns null) at four persistence
points; the **narrative step (3177-3179, "the LAST of the seven")** is the most likely failure.
`ensureNarrative` (2702-2750) returns false — killing the whole character — when the narrative-field
patch is **PBAC-denied** (2733-2737): the patch is issued as `octx.getOlioUser()` **only when octx is
present**, else as the request user. The two extraction call sites differ — line 3683 calls without
an OlioContext hint; the PB2 path at 3906 passes `pb2OlioCtx`. **If `pb2OlioCtx` is null / failed to
init, the narrative patch runs as the request user against an olio-principal-owned world-group
narrative → silent PBAC denial → null character at the final step.** All these paths log ERROR and
return null (no thrown exception), so the surface symptom is exactly "N-1 characters succeed, the
last one vanishes, no specific error" — which matches the owner's Issue 13 report. This aligns with
the `ki-issue13-pb-subrec-olio-principal` fix but should be re-verified live.

### 2.2 Frontend (`AccountManagerUx752`)

Reference files: `src/features/chapBook.js` (ChapBook), `src/workflows/pictureBook.js` (canonical
PB2 pattern), `src/components/SdConfigPanel.js` + `sdConfig.js`, `src/views/list.js`,
`src/components/pagination.js`, `src/components/breadcrumb.js`, `src/core/formDef.js`.

Per-issue status against the owner's recurring reports:

| # | Issue | Status | Evidence |
|---|---|---|---|
| 1 | Group/parent nav in embedded picker | **PARTIAL** | Navigation works (`list.js:openForPicker 1440-1467`, `navigateUp 457-505`); real constraint is ChapBook fixes `sourceType` to `data.note`/`data.data` and the **type-picker is suppressed in picker mode** — can't switch model type from inside the picker |
| 2 | Clear queue deletes `olio.cb.poem` (not sources) | **IMPLEMENTED** | `deletePoem` `chapBook.js:132-138` → `DELETE /rest/model/olio.cb.poem/{id}`; `doDeleteSelected 689-714` via `Dialog.confirm` |
| 3 | List cache staleness after create | **IMPLEMENTED** | `list.js:1301` `noCache=true`; `pagination.js:88-90` clears cache + `cache:false`; sort-by-id `list.js:1307-1312` |
| 4 | SD config form = populated dropdown + saved defaults | **IMPLEMENTED** | `ensureRenderSdConfig 434-460` → `am7sd.loadConfig`/`buildEntity` → `prepareInstance(forms.sdConfig)`; `SdConfigPanel 56-75` renders `<select>` when `models.length>0` |
| 5 | Dead Analyze button hidden when N/A | **IMPLEMENTED but session-fragile** | Gated on `readerPoemIds.length>0` (`1232`), but ids are session-local (set at create ~626); won't appear after reload even when applicable (`1105-1108`) |
| 6 | Role check must warn **AND block** | **PARTIAL (warn-only)** | `roleWarning` banner shown (`741-742`, `1211-1214`) but create/import/render **not disabled** — diverges from PB2 which disables actions (`pictureBook.js:1247-1248`, `1254-1255`) |
| 7 | Breadcrumb model-type picker | **IMPLEMENTED for generic lists, ABSENT in ChapBook** | Real wiring `breadcrumb.js:220-226` + `list.js:681-734`, but gated `!pickerMode` (`1201`, `1333`) and ChapBook screens are bespoke components, not the generic list |
| 8 | Per-page/scene SD config editing | **MISSING** | ChapBook has only the book-level pre-render dialog (`openRenderConfigDialog 463-471`); `ChapBookReview.renderSceneCard 1523-1644` has font/bg/align/split/merge but **no per-scene SD config**. PB2 has the full per-scene override system (`pictureBook.js:104-107, 437-453, 465-477, 1109-1142`, `forms.sdConfigOverrides`) |

**Largest frontend divergences from the PB2 reference:** (a) role gating warn-only vs disable
(#6); (b) no per-scene SD config (#8). Book-level SD config is now convergent (#4).

---

## 3. Claimed-done vs actually-verified (the overclaim trail)

The authoritative prior doc is `PictureBook2ChapBookRemediationPlan.md` (2026-08-24, **OPEN**),
which explicitly **supersedes** the "done/green" claims in the state docs and memories.

| Claim | Source | Real status |
|---|---|---|
| PB2 "all phases done, only ComfyUI remains" | `project-pb2-open-gaps` | **Superseded / rejected** |
| Phase 5 canvas "complete, 13–15 Playwright green" | `project-pb5-phase-status`, state doc | **Rejected** — viewer only (B8); canvas e2e **skips by default** (M7) |
| Phase 6 migration "green" | `project-pb6-phase-status` | **Weak** — `TestPbMigration` skips (no PB1 fixture); "green" = `assumeTrue` no-op (M3) |
| Phase 6c SD config "S1–S6 done, all green" | `project-pb6c-phase-status` | Partly real, but **introduced B5** (`text = bigint` — `DBUtil.generatePatchSchema` can't `ALTER TYPE`) |
| ChapBook "8/8" then "9/9 green" | `project-chapbook-test-status`, `-remediation-complete` | **Suspect** — no ChapBook unit tests per M7; "8/8" unreproducible; 9/9 has 3 skipped |
| "PB2+ChapBook remediation all 14 issues addressed 2026-08-28" | `project-pb2-chapbook-remediation-complete` | **Unverified** — followed days later by owner commits `3f763fbf "Mark regression"`, `76ab2e47 "Mark AI Lie"` |
| "8 PB2/ChapBook issues addressed 2026-08-29" | `project-pb2-new-issues-2026-08-29` | **Compile + Vitest only** — "Playwright E2E not run (Docker stack down)" |
| Issue 13 "fully fixed, TestPictureBookUtilE2E 1/1 PASS live LLM+SD" | `ki-issue13-pb-subrec-olio-principal` | **STRONGEST — real 7-min run, live gpt-oss:120b + SwarmUI, decoded ~1.8MB portraits** |
| UAT #1/#2/#3 "implemented and compile-verified" | `uat-pb2-issues-status` | **Compile-only** — remediation reclassifies #1 as still-broken (B4) |

**Governance memory `feedback-pb2-completion-overclaimed`:** the owner rejected the "complete/green"
claims and instructed that the phase-status memories be treated **UNVERIFIED** until re-audited with
real tests + visual proof. The owner's own commit message **`76ab2e47 "Mark AI Lie"`** annotates a
session claim as a lie.

**Bugs re-fixed 3+ times (the "wound around the axle" spots):**
- Character extraction landing in user home dir instead of book/universe/world
  (`uat-pb2-issue1` → B4 → Issue 13) — the sub-record/PBAC routing was "fixed" repeatedly.
- ChapBook SD prompt = document/object name instead of LLM-from-poem-text
  (`ki-chapbook-sdprompt-design-debt` → `project-chapbook-image-pipeline` → Issue 7); analyze once
  **silently returned `success:true` without the LLM ever running** because `text` wasn't projected
  (`feedback-cb-poem-text-projection`).
- List cache staleness (`feedback-list-cache-bust-pattern` → Issue 4).
- `AccessPoint.list` per-record leak (M2/KI-67) — fixed, reverted across 5 files, reopened.

---

## 4. THE AXLE — why the test-pass claims keep being false

**This is the root-cause section.** On a default gate run, the *only* PB2/ChapBook tests that
actually execute are the shallow ones and the ungated DB-schema/PBAC tests. **Every test that truly
exercises the PB2/ChapBook image+LLM feature is skipped, not run, or pointed at an unreachable host.
"All green" is fully consistent with "the feature was never tested."**

1. **Objects7 skips tests by default — and the gate command doesn't override it.**
   `.claude/loop/detect.sh:69` runs `mvn -q -B test` with **no `-DskipTests=false`**. The Objects7
   POM skips tests, so the loop gate runs **zero** backend tests and still emits `VERIFY_OK`. Any
   agent running the gate (or `mvn -pl AccountManagerObjects7 test`) sees BUILD SUCCESS having
   executed nothing. **This is the single most reproducible false pass.**

2. **Silent skip is the default outcome for every real feature test.**
   (i) `assumeTrue(...)` on `PICTUREBOOK_E2E` / `CHAPBOOK_SD_TESTS` / `CHAPBOOK_LLM_TESTS` /
   `test.swarm.server` marks tests **skipped, not failed** — and skipped suites report green.
   (ii) `pictureBookLive.spec.js` does LLM extraction in `beforeAll`, then guards ~30 tests with
   `test.skip(!workObjectId)` / `test.skip(!extractedScenes.length)`. If `beforeAll` can't reach the
   LLM — **guaranteed through Docker (LAN unreachable)** — the whole suite skips silently. "PB2 e2e
   green" then means "PB2 never ran."

3. **A test that tolerates the bug.** `pictureBookLive.spec.js` B.3 (`:320-330`) treats
   `GET /scenes` returning 0 as a logged "KNOWN ISSUE" and **passes anyway** — a green result that
   actively conceals a broken persistence path.

4. **Wrong-target execution is invisible.** Docker cannot reach SD `192.168.1.39` / LLM
   `192.168.1.42`. Weakly-gated `TestPictureBookService/Pipeline` **default** their server to the
   Spark address, so they always attempt a connection and will connect-fail/hang if pointed at
   Docker. Meanwhile `resource.properties` is a **modified working file** (see git status), and
   `TestPictureBookUtilE2E:429` warns the checked-in `test.swarm.server` may be a WIP edit pointing
   at an unreachable host. Even a forced run can silently hit the wrong or a dead endpoint.

5. **Shallow proxies stand in for feature proof.** "Dialog opens" (Issue 8, its own comment admits
   this is "NOT sufficient proof"), `typeof === 'function'` (~25/40 Vitest assertions),
   `printableRatio>0.60` (extraction), and `toBeVisible` on an `<img>` all pass without the feature
   working. The real proofs (`naturalWidth>0`, PNG/JPEG byte-magic, `ImageIO.read`) **exist — but
   only in the env-gated tests that don't run by default.**

Net: the verification loop is **vacuous for these features**. Any claim of "tested/green" that did
not (a) run Objects7 with `-DskipTests=false`, (b) assert the gated feature tests actually *ran*
(not just were green), (c) execute on the LAN-reachable host, and (d) inspect a decoded image, is
not evidence.

---

## 5. Test inventory (condensed)

**Trustworthy core (REAL, ungated, run by default):** `TestPbSecurity`, `TestPbModelSchema`,
`TestPbGraph`, `TestPbSubRecordUtil`, `TestPictureBookSceneAuthz`, `TestPortraitReuseSceneFetch`,
`TestNarrativeUtilPortraitPrompt`, `TestBookWorld`, `TestChapBook.testChapBookWorkflow`;
Playwright `chapBook.spec.js` 6A/6D — **but note these are DB/schema/PBAC/UI-shape only; none prove
the image pipeline.** (And they only "run by default" if the POM skip is overridden — see Axle #1.)

**Gold standard (REAL, decode+visual proof) but GATED OFF by default:**
`TestPictureBookUtilE2E` (`PICTUREBOOK_E2E`), `chapBook.spec.js` 6C (`CHAPBOOK_SD_TESTS`),
`chapbook-e2e-render.spec.js` (`CHAPBOOK_RENDER_TEST`), `TestChapBook.TestChapBookRender/
testChapBookLlmLandscapePrompt`, `TestPbCanvas`.

**Real-in-intent but structurally self-skipping:** `pictureBookLive.spec.js` (every test behind
`test.skip`), `TestPbMigration` (`assumeTrue` no-op), `TestPictureBookFull` (quality assertion
skipped unless model == calibrated model, `:159` "This is NOT a pass").

**Shallow (pass without the feature):** `pictureBook.test.js` (~25/40 export/shape checks),
`TestDocumentExtraction` (printable-ratio only), `chapbook-issues.spec.js` Issue 8 ("dialog opens"),
the four SD-config Vitest files.

**Behavioral but client-shape-only (can't prove backend):** `pictureBookWiring.test.js`.

---

## 6. Backend readiness for the chapbook-first redesign

| Redesign step | Backend readiness |
|---|---|
| Create empty chapbook | **Ready** (`PbBookUtil.createBook` + bookType patch) |
| Set SD config for the chapbook | **Partial** — `GET/PUT /{book}/settings` exists, but **ChapBook render ignores it** (gap 2); must route `renderChapBook` through `PbConfigUtil.resolveEffectiveConfig` instead of `randomSDConfig` |
| Import poems as a queue **scoped to the chapbook** | **Missing** (gap 3) — needs a book FK on `olio.cb.poem` (or `olio.cb.set` participation) + a scoped import endpoint; current import is global-by-objectId |
| Per-page SD config edit (like PictureBook) | **Missing end-to-end** (gaps 1, 4, 8) — no per-scene config field, no per-scene config endpoint, page-style fields not projected, no UI. PB2's node `configOverride` tier is the pattern to mirror, but ChapBook scenes have no node |
| Render images | **Works but synchronous** (gap 6) |

---

## 7. Recommended order of work for the next conversation

**Phase A — fix the verification loop FIRST (do not skip; everything else is untrustworthy until
this is done):**
1. `.claude/loop/detect.sh:69` — pass `-DskipTests=false` for Objects7 so the gate isn't vacuous.
2. Establish the run contract: PB2/ChapBook feature tests run on the **LAN-reachable host (local
   Eclipse Tomcat / direct `IOSystem`), never Docker**; JUnit with `PICTUREBOOK_E2E=1 …
   -DskipTests=false`; Playwright with `CHAPBOOK_SD_TESTS=1 CHAPBOOK_LLM_TESTS=1 --workers=1`.
3. Turn silent skips into loud failures for the feature suites: a "PB2 verified" claim must assert
   the gated tests **actually ran** (not-skipped), and `pictureBookLive.spec.js` `beforeAll` should
   `throw` on extraction failure instead of leaving all tests to `test.skip`. Remove the
   bug-tolerating pass at `pictureBookLive.spec.js:320-330`.
4. Verify `resource.properties` `test.swarm.server` / `test.llm.ollama.server` point at reachable
   hosts (it's a live WIP edit), and pin `test.llm.ollama.model` to the calibrated model.
5. Adopt the Issue-13 evidence standard: "done" for any pipeline change = a test that decoded a real
   raster (`ImageIO.read` / `naturalWidth>0` / PNG-JPEG magic) and emitted it to disk for human
   visual inspection, using **real poem/document content**, not synthetic stand-ins.

**Phase B — PB2 character creation** (re-verify Issue 13 fix live): confirm `pb2OlioCtx` is non-null
at `PictureBookUtil.java:3906` and that `ensureNarrative` patches as the olio principal; add a test
that asserts all N characters (not N-1) persist with narrative.

**Phase C — chapbook-first redesign** (only after A+B are green): (1) add per-chapbook poem scoping
(book FK on `olio.cb.poem` or `olio.cb.set`); (2) add a per-scene sparse config override + endpoint;
(3) route `renderChapBook` through `PbConfigUtil.resolveEffectiveConfig`; (4) project the page-style
fields; (5) build the per-page SD-config UI mirroring `pictureBook.js` `sceneOverrides`.

**Phase D — smaller UX gaps:** role gating warn→block for ChapBook (#6); Analyze-button persistence
across reload (#5); decide whether the breadcrumb type-picker should be reachable in ChapBook (#7).

**Cross-cutting cleanups:** move business logic out of `ChapBookService` DELETE (gap 5); make render
async (gap 6); remove the duplicate inherited fields on `olio.cb.book` (gap 7); resolve B5
(`text = bigint` sdConfig column) — needs architect sign-off since `DBUtil` can't `ALTER TYPE`.

---

## 8. Known open threads carried from the remediation plan (not re-verified here)

B1–B8 blockers incl. B4 (book/universe/world creation), B5 (text=bigint), B6 (COMPOSITE node = 501
stub), B7 (`/workflow`+`/stale` = 404), B8 (canvas editor). `PbNodeExecutor` covers 4/17 node types
(~11 throw 501, M1). `olio.cb.book` orphaned (M5), `olio.cb.set` membership stub (M6),
`olio.pb.castGroup` orphan (D1). Phase 1b universe/world not threaded at 7 service call sites (M4).
KI-68 (FLUX.2 prompt asserts then negates its own medium), KI-60, KI-67, `tagApparelSceneIndex`
missing book-check — all flagged, not fixed. `feedback-sd-config-consistency`: SD-config
inconsistency across reimage/pictureBook raised multiple times — unresolved until values (not just
slider markup) are verified.
