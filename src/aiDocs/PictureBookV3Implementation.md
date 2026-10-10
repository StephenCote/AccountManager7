# PictureBook V3 — Implementation State

**As of:** 2026-10-10 22:35 local · **Pause point: END OF PHASE 0 — second full gate finished `VERIFY_FAILED`
(§4.2) on exactly the pre-existing backend items that are Stephen's (§6.A ×3 / §6.B / §6.F / §6.H) plus 75 e2e
failures that the §4.3 triage traces to one environmental cause (fixed, §6.C item 20) and 16 + 6 + 3 residuals that
are each environmental, a missing fixture, a stale test, or an "any record" fixture pick — every one classified
with dated evidence, no Phase 0 regression anywhere; the Ux752 quick gate after the fix is `VERIFY_OK` (P0-12
row); the Phase 1 start prompt in §7 is therefore valid, subject to the §6 items waiting on Stephen. A third
full gate (~3 h 45 min) was deliberately not run: it cannot reach `VERIFY_OK` while the §4.3 stale specs and the
§6.A/B/F/H backend items exist, and all of those are Stephen's calls, not Phase 0 work.** Also this afternoon: **§6.M** —
Stephen's "You are sending wrong sdXL model ref to server" fixed (the `modelDef.js` default named a checkpoint
not installed on `.39`; now `OfficialStableDiffusion/sd_xl_base_1.0.safetensors`, proven live by the rewritten
`apparelSequence` spec, which had been a fake green). All Phase 0 task rows P0-1…P0-11 DONE with live tests (§2.1–§2.8), every
verdict in (task-level + phase-level verifier PASS-WITH-NOTES, architect PASS, security-reviewer PASS — §4), every
cheap finding fixed and re-proven with logs; Objects7 jar + Service7 classes + Ux752 `dist` deployed in
`am7test-am7-1`. **Stephen's 2026-10-10 instructions carried out** (§6.I/§6.J/§6.K now RESOLVED, §6.C items 14–15
DONE): the Docker Postgres moved off the 9p bind mount onto a named volume (the PANIC root cause, §6.J); the
shared-user fixture repaired and `bugfixSprint2.spec.js` stopped deleting it (§6.I); the gate's e2e made
chromium-only / 2 workers, the 23 Vite-source specs split into their own `chromium-vite` project against a second
Vite on `:8898` that proxies to the Docker stack (§6.K); the live `generateScene` e2e written and run green three
times with log-backed `Scene composite [classic]` / `[flux2]` evidence and both PNGs inspected (§2.8); the stale
`kontext-request.json` deleted. **First full gate `VERIFY_FAILED`** (2026-10-10 01:50–04:23, §4.1) is superseded by
the **second full gate, 12:38:10–16:22:27 local, `VERIFY_FAILED` / `GATE_EXIT=1`** (`C:\tmp\full-gate-p012-run2.log`,
§4.2): Agent7/Console7 PASS; Objects7 `902/5/0/15` (only §6.A ×3 / §6.B / §6.F); Service7 `108/0/1` (§6.H); Ux752
build/vitest/lint PASS; e2e `439 passed / 5 flaky / 72 skipped / 13 did not run` → 75 failed, triaged in §4.3. Still
waiting on Stephen for §6.A, §6.B, §6.D, §6.F, §6.H, the §6.E/§6.G scope calls, the library `contentAnalysis`
connection repair (§6.G item 4 — both agent-side repair paths were classifier-denied), the bare-fixture nudity
question (§2.8), and the §6.M follow-ups (the `OlioService.java:266` NPE, the stale e2e specs, the
`listNavigation:155` design question).
**Design of record:** `PictureBookV3Design.md` (ratified 2026-10-09). Where this doc and the design
disagree, the design wins; every deviation is recorded in §5 here **and** as a dated in-place edit in
the design. Format follows `PictureBook2ImplementationState.md`.

**Working rules for this doc (from the brief):** updated after every task; test results are pasted
verbatim (`Tests run: n, Failures: 0 … BUILD SUCCESS`, Playwright `n passed`, the `[LLM-GATE]` /
`[e2e-llm]` line, measured `durationMs` as a trend, never a threshold); services re-probed before every
test run; phase is DONE only on `VERIFY_OK` plus verifier / architect / security-reviewer verdicts.

**Open-item defaults in force (§34):** auto-approve default **off**; default preset **DRAFT**; verify
bar **two weeks + sign-off**; estimate source **(b) seeded by (a)**.

---

## 1. Status at a glance

| Phase | State | Tests |
|---|---|---|
| **0 — Hygiene + Kontext Deprecate** | **AT PHASE END — gate red only on Stephen's items** (started 2026-10-09; all 15 task rows DONE except P0-12, the gate itself; every review verdict in incl. the phase-level verifier pass, every cheap finding fixed and re-proven with logs; first full `verify.sh` 2026-10-10 01:50–04:23 `VERIFY_FAILED` on environmental causes (§4.1) that Stephen's 2026-10-10 instructions then had me fix — §6.I/§6.J/§6.K RESOLVED, fixture repaired, named Postgres volume, chromium-only gate + `chromium-vite` project; **second full gate 12:38–16:22 `VERIFY_FAILED`** (§4.2): backend red only on the pre-existing §6.A ×3 / §6.B / §6.F / §6.H, e2e 75 failed → §4.3 triage finds one environmental cause (hardcoded `:8899` fixture base, fixed §6.C item 20) and 16 + 6 + 3 residuals, all environmental / missing fixture / stale tests / "any record" fixture picks, **none a Phase 0 regression**; Ux752 quick gate after the fix `VERIFY_OK`; §6.M SDXL model-ref defect fixed and proven live) | 136 green in the task runs (Slice A 6 + Slice B 30 + P0-5's `TestFlux2Composite` 36 / `TestPbConfigPrecedence` 2 + Slice C backend 8 + Slice C transport `TestChapBookRestContract` 8 + P0-10d Vitest 13 + Playwright `chapBookBookSettings` 8 (3 REST + 5 UI) + P0-11 `TestKontextDeprecation` 7 + Playwright `chatChainSceneReimageRoutes` 8 regression + 2 live (§2.8) — `TestFlux2Composite` counted once, regression specs of other features not counted); module gates: Objects7 RED on §6.A/B/F, Service7 `108 run, 1 error` on the §6.H fixture (§4.1); Ux752 e2e re-verified serially after the environment fixes (§4.2) |
| 1 — Graph-first rendering + review | TODO | — |
| 2 — Style, model resolution, compiler core; ChapBook goes V3; Kontext Disable | TODO | — |
| 3 — Locations + POI link | TODO | — |
| 4 — Composition + wardrobe | TODO | — |
| 5 — Multi-character strategies (spike first) | TODO | — |
| 6 — Variants | TODO | — |
| 7 — PictureBook goes V3 | TODO | — |
| 8 — Visual QA | TODO | — |
| 9 — Cover & book art | TODO | — |
| 10 — Removal (PB1, old ChapBook path, Kontext Remove) | TODO | — |

---

## 2. Phase 0 — tasks

Scope: design §30 row 0; §37.3 Deprecate row; gap doc `PictureBookImageGenerationDesign.md`
Appendix A. Exit: each fix has a passing real test for PictureBook **and** for ChapBook, gate green,
this doc current.

| Id | Task | Design § | Files | Status | Tests | Last run | Date |
|---|---|---|---|---|---|---|---|
| P0-1 | G-15 override leak — `applyOverrides(common, sdConfigOverride)` (`PictureBookUtil:9042`) then `persistBookSdConfig(common)` (`:9072`) persists per-request overrides into the book config; also meta vs `olio.pb.book.sdConfig` drift | §30, gap App. A G-15 | `PictureBookUtil.java` | DONE | `TestPbSceneGenerationGuards#testSceneOverrideDoesNotLeakIntoBookConfig` | green (10.9 s) — see §2.2 | 2026-10-09 |
| P0-2 | G-18 explicit width/height — `SDUtil.createImage` (`:1090`) sets no width/height and ignores its own `hires` and `seed` args | §30, gap App. A G-18 | `SDUtil.java`, `ChapBookUtil.java:1876`, `PbNodeExecutor.java:189/:564`, `PictureBookUtil.java:9850` | DONE | `TestSDUtilCreateImage` (6 tests, new) | 6/6 green, live Swarm .39 — see §2.1 | 2026-10-09 |
| P0-3 | G-24 template override — `PictureBookUtil.callLlm(..., overrideName)` (`:4181`) swaps the template for whatever call it is handed | §30, gap App. A G-24 | `PictureBookUtil.java` | DONE (Option A: override stays on landscape + extract-scenes, never on the scene-image prompt) | `TestPbSceneGenerationGuards#testLandscapeTemplateOverrideDoesNotPoisonScenePrompt` (live LLM) | green (46.4 s), `[LLM-GATE] route=litellm tier=local` — see §2.2 | 2026-10-09 |
| P0-4 | G-26 null guards — scene setting/action/mood not passed through `NarrativeUtil.isMeaningful`; null setting NPE (`PictureBookUtil:3845`) | §30, gap App. A G-26 | `PictureBookUtil.java` | DONE (`sceneTextField` helper at the 3 read sites + null-safe fallback at `:3863`) | `TestPbSceneGenerationGuards#testNullSceneFieldsDoNotNpeAndFallBack` (live LLM) | green (26.5 s) — see §2.2 | 2026-10-09 |
| P0-5 | G-8 landscape flag — `flux2IncludeLandscapeRef` has no schema default, so `flux2Defaults.json`'s `true` is never reached (`SceneCompositeUtil:103-126`, `PictureBookUtil.landscapeEnabled:3522`) | §30, gap App. A G-8 | `configModel.json`, `SceneCompositeUtil.java`, `PbGraphUtil.java` (`PB_PIPELINE_VERSION` → `pb2/3`) | DONE | `TestFlux2Composite#flux2IncludeLandscapeRefDefaultsTrue` (renamed from `aBooleanWithNoSchemaDefaultStillReadsFalse`), `TestPbConfigPrecedence#effectiveConfigCarriesFlux2IncludeLandscapeRefTrueByDefault` (new), `TestPbGraph#case09_computeInputHashGoldenVector` (version line → `pb2/3`) | green — `TestFlux2Composite` 36/36 (9.4 s), `TestPbConfigPrecedence` 2/2 (4.3 s) (`C:\tmp\three-run1.log`); `TestPbGraph` 18/18 (27.2 s, re-run `C:\tmp\o7-sliceB-rerun1.log`, §2.2) | 2026-10-09 |
| P0-6 | G-11 landscape failure non-fatal — a landscape render failure throws a 500 (`PictureBookUtil:9573-9579`) | §30, gap App. A G-11 | `PictureBookUtil.java` (`renderLandscapeStage` extracted; `landscapeFailed`/`landscapeError` on `olio.pictureBookResult`), `pictureBookResultModel.json` | DONE (+ text-only txt2img fallback when the classic composite has neither landscape nor portraits, and a null guard on the no-graph `./land-*.png` debug dump — §5.3) | `TestPbSceneGenerationGuards#testLandscapeFailureIsNonFatal` (live Swarm .39), `#testNoGraphClassicPathSurvivesLandscapeFailure` | green (22.6 s / 5.1 s); PNG opened and described in §2.2 | 2026-10-09 |
| P0-7 | `ByteModelUtil.setValue` for every raw `FIELD_BYTE_STORE` write in the PB path (`SDUtil` ~781–2205, `OlioService:690`) | §15, §30, gap App. A "Other" | `SDUtil.java` (12 writes, 6 lookups), `OlioService.java:691` | DONE (Service7 side compiled, not yet redeployed — carried by the Slice C redeploy) | `TestSDUtilCreateImage` (persisted `data.data` re-read uncached, PNG magic via `ByteModelUtil.getValue`) | 6/6 green — see §2.1 | 2026-10-09 |
| P0-8 | Run closure on exception — graph runs left PENDING/RUNNING when a scene throws; close run + node as FAILED in `finally` | §20, §30, gap App. A "Other" | `PictureBookUtil.java` (`finally` closure in `generateSceneImage`), `PbPipelineUtil.java` (`SceneGraph.isRunClosed()`, `failNode(user,node,error)`), `PbNodeExecutor.java` (`executeNode` stamps FAILED + rethrows) | DONE | `TestPbSceneGenerationGuards#testRunClosedFailedWhenSceneThrows`, `TestPbGraph#case16_runAndNodeClosedFailedOnException` (new) | green (10.1 s / 1.7 s) — see §2.2 | 2026-10-09 |
| P0-9 | ChapBook G-25 — style, negative prompt and hires: `ChapBookUtil:1868-1876` sends the prompt as-is and passes `hires=false` (ignored) | §30 (ChapBook column), gap App. A G-25 | `ChapBookUtil.java` (`:1929-1942`), `SDUtil.java` (`applyConfigStyle` `:1185`, host added in Slice A) | DONE | `TestChapBookRenderStyle#styleClauseAndDefaultNegativeReachCreateImage`, `#explicitNegativePromptIsPreserved`, `#liveRenderUsesStyledPromptAndDefaultNegative` (live Swarm .39) | 4/4 green (55.7 s); PNG opened and described in §2.3 | 2026-10-09 |
| P0-10a | ChapBook G-32 (persistence) — book-level `olio.sd.config` saved as the `book-sdConfig` row FK'd from `olio.pb.book.sdConfig`; get/set never run as the olio principal; adopt an unlinked row before creating | §30 (ChapBook column), gap App. A G-32, §6.C items 8–10 | `PbBookUtil.java` (`BOOK_SD_CONFIG_NAME` `:835`, `getBookSdConfig` `:854`, `setBookSdConfig` `:900`, `readSdConfigById`), `ChapBookUtil.java` (`getBookSdConfig` `:1744`, `setBookSdConfig` `:1759`, `loadChapBookForConfig` `:1764`), `SDUtil.java` (`applyOverrides(base, override, alsoSkip)` `:1014`) | DONE | `TestChapBookBookSettings` (4 tests, new) | 4/4 green (45.6 s) — see §2.3 | 2026-10-09 |
| P0-10b | ChapBook G-32 (precedence) — client config was applied **over** the page override; now a transient tier **below** the scene `configOverride`, never hashed | §30 (ChapBook column), gap App. A G-32, §6.C item 11 | `PbConfigUtil.java` (4-arg `resolveEffectiveConfig` `:324`, `mergeTiers` `:371`), `ChapBookUtil.java:1929` | DONE | `TestChapBookRenderStyle#pageOverrideSurvivesClientOverlay`, `TestChapBookBookSettings#renderDoesNotMutateStoredBookConfig` | green — see §2.3 | 2026-10-09 |
| P0-10c | ChapBook G-32 (transport) — `GET`/`PUT /rest/olio/chap-book/{bookObjectId}/settings` | §6.C items 7, 13 | `ChapBookService.java` (`getBookSdConfig`/`setBookSdConfig`, `ensureSchema` root fix — §5.4; `errorResponse` escaping + static mapper — §2.5), `PbBookUtil.java` (`BOOK_SD_CONFIG_SKIP` — §2.5) | DONE (deployed to `am7test-am7-1`; security-reviewer PASS, 3 Low items applied — §4) | `TestChapBookRestContract` (Service7, 8 tests; `TestNestedSchemaDoesNotSuppressTheRequestSchema`, `TestErrorBodyIsValidJsonForHostileMessages` new), `TestChapBookBookSettings` (smuggled-tag assertion added), Playwright `e2e/chapBookBookSettings.spec.js` (3 REST tests, live as `e2etest_shared` + `e2etest_cbother`) | 8/8 green (11.3 s); `TestChapBookBookSettings` 4/4 (46.1 s); Playwright `3 passed (24.7s)` — see §2.4, §2.5 | 2026-10-09 |
| P0-10d | ChapBook G-32 (UX) — per-book SD instance loaded from `GET /settings`, persisted via `PUT /settings` (explicit Save + render-time sync); render/Regenerate/Re-render send only the sparse delta (normally nothing), never the whole entity or `{}`; create view's panel PUT to the new book on create | §6.C items 12, 16 | Ux752 `features/chapBook.js` only (`modelDef.js`/`formDef.js` unchanged — `forms.sdConfig` already exists) | DONE (planner + architect CHANGES-NEEDED resolved via §6.C items 12/16; ux-specialist's two ⚠ calls §6.C item 17; verifier PASS-WITH-NOTES — its Medium (failed GET seeded → whole-seed PUT over a stored row) and Low (`null` key/idle sentinel collision) **fixed** 23:40–23:50, §2.6 / §6.C item 19; security PASS-WITH-NOTES, Low 1 applied, Low 2 → §6.G item 7; render-time sync half + `cb-dialog-save-book-sd` + create-time PUT NOT Playwright-verified — §2.6) | Vitest `src/test/chapBookBookSettings.test.js` (13 tests, new), Playwright `e2e/chapBookBookSettings.spec.js` (5 UI tests appended to the 3 P0-10c REST tests, live as `e2etest_shared`, no render; the 5th forces one browser GET to 503 via `page.route`) | Vitest `13 passed (13)` (suite `81 files / 1007 tests`); `vite build ✓ 5.37s`; Playwright `8 passed` within `12 passed (1.8m)` / `2 skipped` (LLM-gated) incl. regressions `chapBookSdConfig`, `chapBookConfigPanel`; Ux752 quick gate `VERIFY_OK` — see §2.6 | 2026-10-09 |
| P0-11 | Kontext **Deprecate** stage — `ChatService.java:1490` `resolveMode(sdConfig, true)` → `false`; WARN when `resolveMode` returns `kontext`; deprecation wording on `useKontext`, `kontextModel`, `kontextSteps/Cfg/Sampler/Scheduler` in `configModel.json` (descriptions only; no column drops) and the `modelDef.js` mirror; delete `kontext-test-output/` + tracked `kontext-request.json`; redirect `TestPictureBookFull.java:3128-3130` to `target/` | §37.3, §37.4 | `ChatService.java:1487-1492`, `SceneCompositeUtil.java:82-116`, `configModel.json` (6 descriptions), `modelDef.js:10312-10414` (+ `flux2IncludeLandscapeRef` mirror sync, §6.C item 18), `TestKontext.java:43`, `TestPictureBookFull.java:3128-3130`, Objects7 `.gitignore`, `TestKontextDeprecation.java` (new), `sdConfigPanelLandscapeToggle.test.js:64-76` | DONE (verifier PASS-WITH-NOTES — §4; `kontext-request.json` **deleted 2026-10-10** on Stephen's instruction — §6.C item 15; deployed to `am7test-am7-1` 04:19 UTC; **the `ChatService` flip now has a live Service7-level e2e** — the `E2E_SD_LIVE` describe in `chatChainSceneReimageRoutes.spec.js`, §2.8, closing §6.C item 14) | `TestKontextDeprecation` (7, new), `TestFlux2Composite` (36, unchanged `resolveMode` assertions), Service7 compile + `javap` (`iconst_0`), Vitest `sdConfigPanelLandscapeToggle` (9), Playwright `e2e/chatChainSceneReimageRoutes.spec.js` — 8 regression + 2 live (`E2E_SD_LIVE=1`: explicit `classic` honours 512×512 and logs `Scene composite [classic]`; schema-default runs FLUX.2 at 1024×768 and logs `Scene composite [flux2]`; plus the item-15 reimage) | `Tests run: 43, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`; Playwright regression `8 passed (4.6s)` / `1 skipped`; **live run 4 2026-10-10 12:26–12:29 `10 passed (3.2m)`** (`C:\tmp\pw-scene-live-run4.log`; runs 1 and 3 also `10 passed`), `[e2e-llm] route=litellm server=http://litellm:4000 analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr`, classic `durationMs=21123`, flux2 `durationMs=107276`, both PNGs opened — see §2.8 | 2026-10-10 |
| P0-12 | Phase gate — `verify.sh --quick` per backend slice; full `verify.sh`; verifier + architect + security-reviewer verdicts | §30, loop-engineering | — | IN PROGRESS — per-slice quick gates run for Objects7 (run 3 refresh post Slices C/D, §4.1), Service7 (Slice C transport, §4.1) and Ux752 (Slice D + verifier fix, §4.1); all task-level verdicts in plus the phase-level verifier pass (§4: PASS-WITH-NOTES; its 3 Low fixes applied and its 2 unsubstantiated-quote findings re-run with logs — §2.1, §2.6, §2.7); **full `verify.sh` run 2026-10-10 01:50–04:23 (`C:\tmp\full-gate-p012.log`): `VERIFY_FAILED`** — Agent7/Console7 PASS, Objects7 `902/5/0/15` (only §6.A ×3 / §6.B / §6.F; every Phase 0 class green), Service7 `108/0/1` (§6.H), Ux752 build/vitest/lint PASS, **e2e FAIL** (`281 passed / 4 flaky / 103 skipped / 222 did not run`, 596 failed) for environmental reasons traced in §4.1: Firefox not installed (364), the shared test user deleted mid-run by `bugfixSprint2.spec.js` (§6.I — all 12 Phase 0 ChapBook specs fail on it), two `am7-pg` PANICs + a `VACUUM FULL` deadlock under 4-worker load (§6.J), Vite-source imports under the `:9443` base URL (§6.K). **Stephen's 2026-10-10 reply resolved §6.I/J/K on my side** (named Postgres volume, fixture repair + `bugfixSprint2` fix, chromium-only 2-worker gate + `chromium-vite` project on `:8898`) — **second full gate 12:38:10–16:22:27 local `VERIFY_FAILED` (§4.2)**: backend exactly the expected set (Objects7 `902/5/0/15` on §6.A ×3 / §6.B / §6.F; Service7 `108/0/1` on §6.H), e2e `439 passed` / 75 failed; **§4.3 triage**: 61 `[chromium]` failures from the hardcoded-`:8899` fixture base (fixed, §6.C item 20; serial re-run of 16 specs `16 failed / 1 skipped / 53 passed (7.2m)` with all 16 residuals classified environmental / missing fixture / stale test), `bugfix3-live` re-run `3 failed / 6 passed (4.1m)` (1 stale selection exposing the pre-existing `OlioService.java:266` NPE, 2 never-valid selectors), `sdConfigFlow` + `pictureBookLive` re-run `6 failed / 1 skipped / 43 passed (13.2m)` — the same 6 as the gate, each classified (2 stale since 2026-03-30, 1 stale since 2026-03-31, 2 "any record" fixture picks, 1 self-contradicting assertion; the live B.4 scene render passed); **no Phase 0 regression**; **Ux752 quick gate after the fix `VERIFY_OK`**; a third full gate deliberately not run (cannot reach `VERIFY_OK` until Stephen rules on the stale specs and §6.A/B/F/H). **Status: AT PHASE END — gate red only on Stephen's items** | log-backed re-runs 2026-10-10: `TestKontextDeprecation,TestFlux2Composite` 43/0/0; `TestSDUtilCreateImage#createImageSparseConfigDoesNotThrow` (post-`assertFalse`) 1/0/0 + PNG; ChapBook Playwright ×3 `12 passed / 2 skipped` at 01:44 (`C:\tmp\pw-p012-chapbook-3specs.log`); post-gate serial re-run 04:37 `8 failed` on the then-damaged fixture (`C:\tmp\pw-p012-postgate-bookSettings.log`, §4.1); **after the fixture repair** `chatChainSceneReimageRoutes` live `10 passed (3.2m)` ×3 runs and the `chromium`/`chromium-vite` split `8 passed (51.9s)` (`C:\tmp\pw-vite-split-verify.log`, §4.2) | first full gate `VERIFY_FAILED` (§4.1): Objects7 `Tests run: 902, Failures: 5, Errors: 0, Skipped: 15`; Service7 `Tests run: 108, Failures: 0, Errors: 1, Skipped: 0`; Ux752 e2e `281 passed (45.1m)` / 596 failed. **Second full gate (§4.2): `VERIFY_FAILED`, `GATE_EXIT=1`** — Objects7 `Tests run: 902, Failures: 5, Errors: 0, Skipped: 15`; Service7 `Tests run: 108, Failures: 0, Errors: 1, Skipped: 0`; Ux752 e2e `604 tests / 2 workers` → `5 flaky / 72 skipped / 13 did not run / 439 passed (1.9h)` (75 failed, §4.3). **Post-§4.3 Ux752 quick gate (22:30, after the 37-spec `BASE_URL` fix):** `LOOP_MODULE=AccountManagerUx752 bash .claude/loop/verify.sh --quick` → `PASS (build)` / `PASS (test)` / `PASS (lint)` / **`VERIFY_OK`**, `GATE_EXIT=0` (`C:\tmp\ux752-quick-gate-p0-final.log`); `npx vitest run` → `Test Files  82 passed (82)`, `Tests  1010 passed (1010)`, `Duration  9.92s` | 2026-10-10 |

### 2.1 Slice A results — P0-2 + P0-7 (2026-10-09)

**What changed.** `SDUtil.createImage` now applies `steps`, `seed` (argument wins over config when
`>= 0`), `width`/`height` (only when the config carries a value `> 0`; otherwise `SWTxt2Img`'s 1024
default stands), and honours its `hires` argument: the refiner block runs only when `hires && refinerModel`
is non-blank; `hires` with no `refinerModel` logs
`hires requested but no refinerModel configured; skipping refiner` and sets `refinerControlPercentage=0.0`.
New public helpers `SDUtil.hiresOf(cfg)` (null/absent-safe) and `SDUtil.applyConfigStyle(prompt, cfg)`
(appends the config style clause once; host for P0-9). The three callers that passed a literal
`hires=false` (`ChapBookUtil:1876`, `PbNodeExecutor:189/:564`, `PictureBookUtil:9850`) now pass
`SDUtil.hiresOf(cfg)`; `PictureBookUtil.appendConfigStyleOnce` delegates to `applyConfigStyle`.
All 12 raw `FIELD_BYTE_STORE` writes in `SDUtil` and the one in `OlioService:691` go through
`ByteModelUtil.setValue(data, bytes)` with `contentType` set first; the 6 by-name `data.data` lookups
that precede them project the byteStore field set explicitly (`newDataByNameQuery`) because
`ByteModelUtil.setValue` throws `Missing dataBytesStore field` on a sparse record (§5.1 item 4).

**Test.** New `TestSDUtilCreateImage` (`src/test/java/.../TestSDUtilCreateImage.java`, extends
`BaseTest`, non-admin `testUser1`, Swarm from `test.swarm.server`, checkpoint from `SdTestGate`,
refiner `juggernautXL_ragnarokBy.safetensors` taken from the live ListModels). Each render test re-reads
the stored `data.data` with `setCache(false)` and an explicit projection, asserts the persisted `s2i`
attribute values, and checks the PNG magic of `ByteModelUtil.getValue`. PNGs written to
`target/sdutil-test-output/`. **Not pom-excluded** — it is part of the default suite (~90 s of GPU).

```
$ cd src && mvn -o -pl AccountManagerObjects7 -Dtest=TestSDUtilCreateImage -DskipTests=false test
LlmTestGate - [LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
SdTestGate - Live SD checkpoint for http://192.168.1.39:7801 = 'OfficialStableDiffusion/sd_xl_base_1.0.safetensors' [olio.sd.config schema default]
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 89.32 s -- in org.cote.accountmanager.objects.tests.TestSDUtilCreateImage
[INFO] BUILD SUCCESS
```
Full log: `C:\tmp\TestSDUtilCreateImage-run1.log`.

| Test | durationMs (recorded, not asserted) | Persisted `s2i` observed |
|---|---|---|
| `createImageHonoursWidthHeightAndSeed` | 6 541 | width 768, height 512, seed 12345, refinercontrolpercentage 0.0 |
| `createImageHiresWithRefinerModelSetsRefinerFields` | 34 821 | refinermodel juggernautXL_ragnarokBy.safetensors, rcp 0.3, refinersteps 8, upscale 2 → 1024×1024 PNG |
| `createImageHiresWithoutRefinerModelSkipsRefiner` | 5 166 | WARN line present, rcp 0.0, 512×512 PNG |
| `createImageSparseConfigDoesNotThrow` | 34 230 | defaults: steps 20, 1024×1024, cfg 7.0, seed -1 |
| `applyConfigStyleAppendsOnce` / `hiresOfIsNullSafe` | — | pure |

PNGs opened with the Read tool: `createImageHonoursWidthHeightAndSeed.png` — a stylised red wooden
barn in a green field under a cloudy sky, 768×512 landscape. `createImageHiresWithoutRefinerModelSkipsRefiner.png`
— a painted white bowl of oranges on a wooden table in a beige frame, 512×512.

**Observations.** `refinerModel` is sent to Swarm verbatim, so it needs the `.safetensors` extension
(memory `feedback-swarm-model-names-need-extension`); the test takes it from the live model list.
`hires=true` without a `refinerModel` is now a visible no-op with a WARN rather than a request that
names a null refiner.

**Phase verifier Low (§4), fixed 2026-10-10:** `createImageSparseConfigDoesNotThrow` early-returned when
Swarm produced no image (`if (out.isEmpty()) return;` at `:321`), which would have passed the test on exactly the
regression it exists to catch (helper defaults rejected by Swarm). Replaced with
`assertFalse("sparse config must render with the helper defaults; Swarm returned no image", out.isEmpty())`.
Re-run alone after the edit (working tree, `cd src && mvn -o -pl AccountManagerObjects7
-Dtest='TestSDUtilCreateImage#createImageSparseConfigDoesNotThrow' -DskipTests=false test`, 01:45–01:46 local,
`C:\tmp\o7-p012-sparse-postedit.log`):
```
LlmTestGate - [LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
BaseTest - sparse createImage -> 1 record(s), durationMs=19312
BaseTest - PNG written: …\AccountManagerObjects7\.\target\sdutil-test-output\createImageSparseConfigDoesNotThrow.png (1635515 bytes)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.88 s -- in org.cote.accountmanager.objects.tests.TestSDUtilCreateImage
[INFO] BUILD SUCCESS
SP_EXIT=0
```
PNG opened with Read: a single smooth grey oval pebble resting on a bright, fine-grained white sand surface in
hard sunlight, casting a sharp shadow to the lower right; a tiny second pebble at the right edge — a clean
1024×1024 SDXL render from the helper defaults (steps 20, cfg 7.0, seed −1). The gate's own run of this class
(§4.1 run 3, 6/0/0) predates this edit; this solo run is the post-edit result.

### 2.2 Slice B results — P0-1 / P0-3 / P0-4 / P0-5 / P0-6 / P0-8 (2026-10-09)

**What changed.**
- **P0-1 (G-15).** `generateSceneImage` snapshots `bookBase = common.copyRecord()` *before*
  `applyOverrides(common, sdConfigOverride)` and persists `bookBase` (not `common`) through
  `persistBookSdConfig`; the per-request override now lives only on the in-memory config used for that
  render.
- **P0-3 (G-24, Option A).** The template override (`landscapeTemplate` / extract-scenes) is passed
  to `resolveScenePrompt(...)` as `null` in both `generateSceneImage` and `prepareSceneImagePrompts`;
  it still applies to the landscape and extract-scenes LLM calls.
- **P0-4 (G-26).** New `sceneTextField(map, key)` (`NarrativeUtil.isMeaningful` gate) at the three
  setting/action/mood read sites; `landscapePrompt` falls back to `BLANK_LANDSCAPE_FALLBACK`
  ("A detailed environment.") when the setting is null/blank instead of NPE-ing at `:3845`.
- **P0-5 (G-8).** `configModel.json` `flux2IncludeLandscapeRef` now carries `"default": true`;
  `SceneCompositeUtil.includesLandscapeReference` keeps the `flux2Defaults.json` fallback only for
  records that lack the field. Because a schema-default change is not part of the inputHash,
  `PbGraphUtil.PB_PIPELINE_VERSION` is bumped `pb2/2` → `pb2/3` so cached composites re-render.
- **P0-6 (G-11).** Landscape rendering extracted to `renderLandscapeStage(...)` returning a
  `LandscapeStage{bytes,node,artifact}` holder and wrapped in try/catch: a failure sets
  `landscapeFailed=true` / `landscapeError=<message>` on `olio.pictureBookResult` (two new fields in
  `pictureBookResultModel.json`), fails the LANDSCAPE node, and the scene continues. When the classic
  composite has **neither** landscape **nor** portraits it falls back to text-only txt2img instead of
  compositing a black canvas (§5.3). The no-graph path's `FileUtil.emitFile("./land-<oid>.png", …)`
  debug dump is null-guarded — it NPE'd on the null landscape payload and turned the non-fatal
  failure back into a fatal one. **Verifier finding (2026-10-09):** the first guard test
  (`testNoGraphClassicPathSurvivesLandscapeFailure`) could not reach that guard — with no characters
  the classic path takes the text-only fallback (`compositeBytes == null`) and the guarded block is
  skipped. Replaced by `testNoGraphClassicLandscapeDumpGuardWithPortrait`: PB1-only fixture, one
  `olio.charPerson` with a persisted solid-colour portrait (`profile.portrait`, 384×512, rgb
  `0x3C8ED0`) and a `pbDescription` attribute, scene `characters=[cpOid]`, `isBookOverride=TRUE`;
  injected `SDUtil` throws on `createImage` ("portrait SD disabled"), on `createSceneImage` with a
  `landscape_` name ("landscape SD failure") and on the composite ("composite SD disabled"). The test
  expects the **composite** message (proof the guarded block ran — without the guard the NPE
  `Cannot read the array length because "b" is null` surfaces instead), `land-<oid>.png` absent,
  `comp-<oid>.png` present and decodable with the left-portrait slot (geometry mirrored from
  `SDUtil.compositeSceneCanvas`) at the portrait fill colour and the top-right pixel black, log
  lines `Reusing persisted portrait for …` and `landscape generation FAILED (test: landscape SD
  failure)`, and no `Portrait generation error`. No GPU/LLM in that test.
- **P0-1 (verifier finding).** The test asserted the persisted book config but never that steps=7
  reached the SD request. Now uses `capturingThrowingSdu(...)`: every captured `SWTxt2Img` must have
  `getSteps() == 7` and at least one must have been captured before the injected throw.
- **P0-8.** `generateSceneImage` tracks `pbGraph` / `currentNode` / `lastErrorMessage` and in
  `finally` calls `failNode` + `closeRun(false, …)` when the run is not already closed
  (`SceneGraph.isRunClosed()`, set by `closeRun` even with a null run). `PbNodeExecutor.executeNode`
  wraps its dispatch in try/catch, stamps the node FAILED (`PbPipelineUtil.failNode(user,node,error)`,
  `lastError` + `lastRunAt`) and rethrows.
- `TestOllamaModelUnload` was asserting absolute `skippedProxied` counts; when the suite's LLM route
  is LiteLLM, proxied entries from earlier tests are retained for the life of the JVM, so the test now
  drains the registry first and asserts a **delta** against that baseline (`drainRegistry()`).
- `TestPbGraph` case01 flipped to `assertNotNull` — `planMost(true)` on `olio.pb.workflow` /
  `olio.pb.run` succeeds since the nested-plan budget of 2026-10-07; case09's golden version line is
  `pb2/3`.

**Tests.** New `TestPbSceneGenerationGuards` (`src/test/java/org/cote/accountmanager/olio/picturebook/`,
extends `BaseTest`, non-admin users, real `PbBookUtil.createBook` + `PictureBookUtil.createFromScenes`
fixtures, uncached re-reads). SD failures are injected through `SDUtil` subclasses
(`throwingSdu`, `landscapeOnlyThrowingSdu`) handed to the package-private
`generateSceneImage(user, sceneOid, params, SDUtil)` seam — the live Swarm `.39` still renders the
composite in `testLandscapeFailureIsNonFatal`. `TestPbGraph#case16_runAndNodeClosedFailedOnException`
runs a LANDSCAPE node against `http://127.0.0.1:1`.

```
$ cd src && mvn -o -pl AccountManagerObjects7 -Dtest='TestOllamaModelUnload,TestPbGraph,TestPbSceneGenerationGuards' -DskipTests=false -DfailIfNoTests=false test
SdTestGate - Live SD checkpoint for http://192.168.1.39:7801 = 'OfficialStableDiffusion/sd_xl_base_1.0.safetensors' [olio.sd.config schema default]
LlmTestGate - [LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.186 s -- in org.cote.accountmanager.objects.tests.TestOllamaModelUnload
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.22 s -- in org.cote.accountmanager.objects.tests.TestPbGraph
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 121.6 s -- in org.cote.accountmanager.olio.picturebook.TestPbSceneGenerationGuards
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:34 min
```
Full log: `C:\tmp\o7-sliceB-rerun1.log`. The P0-5 unit tests ran in the earlier three-class run
(`C:\tmp\three-run1.log`, 19:38): `Tests run: 36 … TestFlux2Composite`, `Tests run: 2 …
TestPbConfigPrecedence`; that run's single `TestPbGraph` failure was case01's stale `assertNull`,
fixed above and green in the re-run.

| Test | Task | durationMs / elapsed (recorded, not asserted) |
|---|---|---|
| `testSceneOverrideDoesNotLeakIntoBookConfig` | P0-1 | 10.9 s |
| `testLandscapeTemplateOverrideDoesNotPoisonScenePrompt` (live LLM) | P0-3 | 46.4 s |
| `testNullSceneFieldsDoNotNpeAndFallBack` (live LLM) | P0-4 | 26.5 s |
| `testLandscapeFailureIsNonFatal` (live Swarm .39) | P0-6 | `generateSceneImage durationMs=13107`; 22.6 s total |
| ~~`testNoGraphClassicPathSurvivesLandscapeFailure`~~ (replaced, see above) | P0-6 | 5.1 s (did not reach the guard) |
| `testNoGraphClassicLandscapeDumpGuardWithPortrait` | P0-6 | **PASS in run 3** (below) — `no-graph canvas 1024x1024 portrait-centre rgb=3c8ed0 top-right rgb=0`; 1 min 9 s wall |
| `testRunClosedFailedWhenSceneThrows` | P0-8 | 10.1 s |
| `TestPbGraph#case16_runAndNodeClosedFailedOnException` | P0-8 | `executeNode … Connection refused (durationMs=35)`; 1.7 s |

Key lines (verbatim):
```
P0-6 PNG written: …\target\pb-v3-p0-6-landscape-failed.png (1861321 bytes)
P0-6 PNG 1024x1024 mean luminance (0-255, sampled every 4px) = 60
P0-6 run d6ee8c83-11bf-4406-aa7f-649792198b73 status=COMPLETED failedNodeCount=1 durationMs=13107
case16 executeNode against http://127.0.0.1:1 threw ProcessingException: java.net.ConnectException: Connection refused: getsockopt (durationMs=35)
```
PNG opened with the Read tool: `target/pb-v3-p0-6-landscape-failed.png` — a night-time harbour quay
with moored sailboats, lit terraced buildings along the waterfront, street lamps reflecting in dark
water and wet cobblestones in the foreground; 1024×1024, mean luminance 60. Before the text-only
fallback the same test produced a near-black composite — `mean luminance … = 1` in
`C:\tmp\pbguards-run2.log` (19:41) — which is what prompted the fallback.

**Run 3 of `TestPbSceneGenerationGuards` (2026-10-09 21:15, after the `TestPictureBookWorkflow` solo
run released `target/`; probe block §3 "Re-probe … run 3").** Log `C:\tmp\o7-pbguards-run3.log`.
Verbatim:
```
[LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
Live SD checkpoint for http://192.168.1.39:7801 = 'OfficialStableDiffusion/sd_xl_base_1.0.safetensors' [olio.sd.config schema default]
P0-6 generateSceneImage durationMs=25768
P0-6 PNG written: C:\Projects\GitHub\AccountManager7\src\AccountManagerObjects7\target\pb-v3-p0-6-landscape-failed.png (1920885 bytes)
P0-6 PNG 1024x1024 mean luminance (0-255, sampled every 4px) = 79
P0-6 run c15a2718-14be-40ef-b1f1-28304a0612cb status=COMPLETED failedNodeCount=1 durationMs=25768
P0-6 no-graph canvas 1024x1024 portrait-centre rgb=3c8ed0 top-right rgb=0
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 156.8 s -- in org.cote.accountmanager.olio.picturebook.TestPbSceneGenerationGuards
[INFO] BUILD SUCCESS
[INFO] Total time:  02:40 min
```
PNG opened with the Read tool (`target/pb-v3-p0-6-landscape-failed.png`, run 3): a monochrome night
canal/harbour scene — moored sailboats with tall masts on both banks, a brick quay wall with street
lamps on the right, terraced buildings with lit windows, lamp reflections in still dark water, mist
over a distant hill; 1024×1024, mean luminance 79. Different image from run 2's (random seed), same
subject class, not a black composite.

Noise in the run-3 log, not a test failure, noted for scope discipline (left alone): three
`RecordValidator - description value 'PERMIT system.user pbGuardUser to READ …' … WriterException:
Record failed validation in IO DATABASE` ERROR lines on `Thread-5/7/11` — the audit `MemoryWriter`
rejecting an audit record whose `description` (the full query key) exceeds the field's length. The
audited reads themselves were `PERMIT`; no test asserts on audit rows. Pre-existing audit-sizing
defect, outside Phase 0.

**Observations.**
- P0-4: for a scene whose setting/action/mood are all null, the cached `scenePrompt` is **only the
  config style clause** (`((Fashion photography) for (Dazed and Confused) …)`) — no NPE, no `null`
  leak, non-blank, which is the G-26 contract, but a style-only scene prompt is a thin render input.
  Noted, not changed (out of G-26 scope; the Phase 2 compiler owns prompt assembly).
- `PbGraphUtil.startRun` writes `PENDING` and nothing stamps `RUNNING`; P0-8 closes PENDING runs the
  same way (status → FAILED), so the task description's "left RUNNING" is "left PENDING" in practice.

### 2.3 Slice C results (backend) — P0-9 / P0-10a / P0-10b (2026-10-09)

**What changed.**
- **P0-9 (G-25).** `ChapBookUtil.renderResolvedScene` (`:1929-1942`) now resolves the effective config,
  runs `SDUtil.fillStyleDefaults`, sets `description = SDUtil.applyConfigStyle(sdPrompt, sdConfig)`
  (the parenthesised style clause appended once — `createImage` uses `description` verbatim and never
  calls `getSDConfigPrompt`, so without this the whole style tier was dead weight on the ChapBook
  path), fills `negativePrompt` from `NarrativeUtil.getDefaultNegativePrompt()` only when the
  effective config has none, and passes `SDUtil.hiresOf(sdConfig)` instead of a literal `false`. The
  stored scene prompt (`cbSdPrompt`) is **not** rewritten — the clause lives only on the render config.
  A new package-private seam `renderChapBookScene(user, sceneOid, SDUtil sdu, chatConfig,
  clientSdConfig, promptOverride)` (`:1599`) lets tests inject a capturing `SDUtil`; the public
  6/7-arg overloads build the real one.
- **P0-10b (G-32 precedence).** `PbConfigUtil.resolveEffectiveConfig(book, node, composite,
  clientOverlay)` (`:324`) is a new 4-arg overload; `mergeTiers` (`:371`) applies the overlay after the
  book tier and **before** the node/scene `configOverride`, so a per-page override survives a client
  overlay. The 3-arg form is unchanged (delegates with `null`). The overlay is request-transient and is
  not part of `resolveDeclaredConfig`/`configHash`; the javadoc records that `PbNodeExecutor` must never
  call the 4-arg form (§6.C item 11). `ChapBookUtil:1929` is the only caller.
- **P0-10a (G-32 persistence).** `PbBookUtil.getBookSdConfig(user, book)` (`:854`) reads the
  `olio.pb.book.sdConfig` FK and returns the row via `readSdConfigById` (`AccessPoint.find` with
  `planMost(false)` + `setCache(false)` — `PbBookUtil.java:983-989`; an earlier revision of this line
  said "explicit projection", corrected 2026-10-10 on the phase verifier's note) — never writes. `PbBookUtil.setBookSdConfig(user, book, sparse)` (`:900`)
  resolves the target row in order: the FK'd row → an existing unlinked `book-sdConfig`
  (`PbBookUtil.BOOK_SD_CONFIG_NAME`, `:835`) in the book group via `SdConfigUtil.findConfig` (adopted,
  INFO-logged, architect blocking item) → create one in the book group as the request user with
  `fillStyleDefaults`. The merge is `SDUtil.applyOverrides(patch, sparse, BOOK_SD_CONFIG_RENDER_ONLY)`
  — a **new 3-arg overload** (`SDUtil.java:1014`) that extends `SD_OVERRIDE_SKIP` with
  `description`/`imagePath`/`imageName` so a client config carrying another row's
  `objectId`/`groupId`/`name` cannot redirect the write and per-render fields are never persisted on
  the book row. The FK patch on the book and the config update both assert their result (null = error,
  not WARN). `ChapBookUtil.getBookSdConfig`/`setBookSdConfig(user, bookObjectId, …)` (`:1744`/`:1759`)
  wrap these behind `loadChapBookForConfig` → `requireChapBook` (403 `PictureBookException` for a
  non-ChapBook, 404 for an unknown id) — the Service7 P0-10c endpoints delegate here.

**Tests.** Two new classes in `src/test/java/org/cote/accountmanager/olio/picturebook/`, both
`extends BaseTest`, fixture mirrored from `TestChapBookRenderSignal` (org `/Development/ChapBook
Tests`, user `chapbookTestUser`, real `ChapBookUtil.createChapBook` over a two-stanza poem, 5 scenes,
scene prompts patched to a genuine prompt so no LLM is needed on the capture path).
- `TestChapBookRenderStyle` — `styleClauseAndDefaultNegativeReachCreateImage` (capturing `SDUtil`,
  returns an empty list → status FAILED, `!llmUnavailable`; asserts the captured `description` starts
  with the genuine prompt and contains `SDUtil.getSDConfigPrompt(cfg)` with no `(null)`, that
  `applyConfigStyle` is idempotent, that `negativePrompt` equals the default, and that the stored
  `cbSdPrompt` is unchanged); `explicitNegativePromptIsPreserved` (overlay `negativePrompt` reaches
  `createImage` verbatim, style clause still applied); `pageOverrideSurvivesClientOverlay` (book
  `steps=31` via `setBookSdConfig`, scene 0 `configOverride` `{"schema":"olio.sd.config","steps":17}`,
  overlay `{steps:23, cfg:4}` → scene 0 renders with steps 17 / cfg 4, scene 1 with steps 23, scene 1
  without overlay steps 31 / cfg 7, stored book config still 31);
  `liveRenderUsesStyledPromptAndDefaultNegative` (capturing `SDUtil` that also delegates to the real
  one → live Swarm `.39`; asserts RENDERED + `imageObjectId`, styled description, default negative,
  the live checkpoint from `SdTestGate.requireRenderConfig`, re-reads `data.data` uncached with
  `ByteModelUtil.getValue`, PNG magic, writes `target/chapbook-v3-p0-9-style.png`, logs `durationMs`).
- `TestChapBookBookSettings` — `setBookSdConfigRoundTrip` (fresh book has no FK and 0 rows; a sparse
  config carrying a foreign `objectId`, a wrong `groupId` and the name `not-the-book-config` is stored
  as a **new** row named `book-sdConfig` in the book group with the client identity discarded,
  `description`/`imageName` blank, exactly 1 row, FK linked; a second `{steps:44}` set updates the same
  row and leaves `cfg`/`style` untouched); `adoptsExistingUnlinkedRow` (pre-seeded unlinked row with
  `steps=12` is adopted, `cfg=9` applied, steps survive, still 1 row, FK now linked);
  `renderDoesNotMutateStoredBookConfig` (book `{steps:31, negativePrompt}` + overlay
  `{steps:23, description}` → `createImage` sees 23 + the book negative; the stored row is still 31
  with a blank description and the same `objectId`, 1 row); `nonChapBookIs403` (book patched to
  `bookType=STORY` → both get/set throw status 403 and write nothing; unknown id → 404).
  Row counts use `AccessPoint.count` on `name + groupId + organizationId`, uncached.

```
$ cd src && mvn -o -pl AccountManagerObjects7 -Dtest='TestChapBookBookSettings,TestChapBookRenderStyle' -DskipTests=false -DfailIfNoTests=false test
SdTestGate - Live SD checkpoint for http://192.168.1.39:7801 = 'OfficialStableDiffusion/sd_xl_base_1.0.safetensors' [olio.sd.config schema default]
LlmTestGate - [LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
PbBookUtil - setBookSdConfig: adopting unlinked book-sdConfig row c16cc883-14b4-4475-9a00-b4aa1508908b in group 11809 for book a026eff2-88c5-48c4-917b-60aebdfd7a55
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 45.62 s -- in org.cote.accountmanager.olio.picturebook.TestChapBookBookSettings
BaseTest - P0-9 description=a serene alpine lake at dawn, wide natural vista, soft diffuse light, snow on distant peaks. ((Selfie) taken with a (iPhone 14) at a (elevated above) angle with (ring light) lighting).
BaseTest - P0-9 negativePrompt=Washed out colors, illogical, disgusting, bad anatomy, errors, glitches, mistakes, low resolution, pixilated, blurry, out of focus, low res, mutated, distorted, melting, cropped, disproportionate, wonky, low quality, compressed, muddy colors, overexposed, mosaic, rotten, fake, low poly, lacking detail, watermark, malformed, failed, failure, extra fingers, cloned face, missing legs, extra arms, fused fingers, too many fingers, poorly drawn face
SDUtil - txt2img sdConfig: model=OfficialStableDiffusion/sd_xl_base_1.0.safetensors steps=20 cfgScale=7.0 sampler=dpmpp_2m scheduler=karras width=1024 height=1024 images=1 seed=-1 refinerModel=null … prompt=a serene alpine lake at dawn, wide natural vista, soft diffuse light, snow on distant peaks. ((Movie still) taken with a (IMAX) camera using (16 mm) film processed with (Kodachrome) by (James Cameron)).
BaseTest - P0-9 live render durationMs=17857
BaseTest - P0-9 PNG written: C:\Projects\GitHub\AccountManager7\src\AccountManagerObjects7\target\chapbook-v3-p0-9-style.png (1681147 bytes)
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 55.71 s -- in org.cote.accountmanager.olio.picturebook.TestChapBookRenderStyle
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:44 min
```
Full log: `C:\tmp\sliceC-run2.log`. Run 1 (`C:\tmp\sliceC-run1.log`) was 7/8: my test bug —
`assertEquals("…", renderConfig.get(FIELD_SD_MODEL), cfg.get(FIELD_SD_MODEL))` with two generic
`<T> T get()` arguments resolves to the deprecated `assertEquals(String, Object[], Object[])` overload
and throws `ClassCastException: String cannot be cast to [Ljava.lang.Object;` at runtime. Fixed by
assigning both to `String` locals first (and the two `Long` FK comparisons in
`TestChapBookBookSettings` the same way, defensively).

| Test | Task | durationMs / elapsed (recorded, not asserted) |
|---|---|---|
| `liveRenderUsesStyledPromptAndDefaultNegative` (live Swarm .39) | P0-9 | `P0-9 live render durationMs=17857`; 55.7 s class total |
| `TestChapBookBookSettings` (4 tests, no GPU/LLM) | P0-10a/b | 45.6 s class total (dominated by `createChapBook` fixtures) |

PNG opened with the Read tool: `target/chapbook-v3-p0-9-style.png` — a photorealistic alpine lake at
dawn, mirror-still water reflecting jagged snow-capped peaks, a dark conifer forest on the far shore,
a green meadow with wildflowers and rocks in the foreground, mist over the water, deep-blue dawn sky;
1024×1024. Matches the genuine prompt. The `((Movie still) … (James Cameron))` clause is a **random**
style picked by `fillStyleDefaults` because no tier set `style`; the capture test drew `((Selfie) …
(ring light) lighting)` for the same reason — which is why the assertions compare against
`SDUtil.getSDConfigPrompt(cfg)` of the captured config rather than a literal.

Noise in both logs, not a failure, left alone (scope discipline): `ERROR OlioContext - Failed to find
root location` / `Root Epoch is null` during `createChapBook` in this test org — the same lines
`TestChapBookRenderSignal` runs produce; the ChapBook fixture does not need an Olio world.

**Observations.**
- `olio.sd.config.cfg` is an `int` (schema default 7), so the "cfg 7" assertions are integer compares;
  `cfgScale=7.0` in the SD request line is the Swarm payload's double.
- A fresh ChapBook has **no** `book-sdConfig` row and a null `sdConfig` FK (only PB1's
  `persistBookSdConfigFk` ever wrote one), so before P0-10a the book tier of `resolveEffectiveConfig`
  was always empty for ChapBooks and the Ux752 book panel was in-memory only — confirmed by
  `setBookSdConfigRoundTrip`'s opening assertions.
- Redeployed by P0-10c (§2.4): the Objects7 jar with Slices A/B/C and the `OlioService` P0-7 class are
  now in `am7test-am7-1`.

### 2.4 Slice C results (transport) — P0-10c (2026-10-09, UTC 2026-10-10 01:34–03:05)

**What changed.**
- **`ChapBookService.java`** — two new resource methods, both `@RolesAllowed({"admin","user"})`,
  transport only: `getBookSdConfig` (`GET /rest/olio/chap-book/{bookObjectId:[0-9A-Za-z\-]+}/settings`)
  → `ChapBookUtil.getBookSdConfig(user, bookObjectId)`; 200 with the row's `toFullString()`, or `200 {}`
  when the book has no `book-sdConfig` row; `setBookSdConfig` (`PUT`, same path) → `parseParams(body)`,
  requires `sdConfig instanceof BaseRecord` (else 400 `Request body is required` / `sdConfig is
  required`), then `ChapBookUtil.setBookSdConfig(user, bookObjectId, sparse)`; 200 with the stored row.
  Both: 401 with no principal; `PictureBookException` → `e.getStatus()` (404 unknown/unreadable book,
  403 non-ChapBook); any other exception → 500. No business logic in the service (§6.C items 7, 13).
- **`ChapBookService.ensureSchema` root fix (§5.4).** The pre-existing implementation was
  `if (trimmed.contains("\"schema\"")) return json;` — a substring test over the whole body — so a request
  whose only `schema` key was **nested** (`{"sdConfig":{"schema":"olio.sd.config",…}}`) never received the
  top-level `olio.pictureBookRequest` schema, `RecordDeserializer` logged `Invalid model name`, and
  `parseParams` returned null. Rewritten to parse the body with Jackson, decide on the **root** object
  (`root.hasNonNull("schema")`), and inject `CB_REQUEST_SCHEMA` only when the root has none; a body that is
  not an object or not parseable is returned untouched (WARN). This is what made Playwright run 1 fail
  (below) and it also means the existing `/render` and `/scene/{sceneObjectId}/generate` endpoints
  (`ChapBookService.java:~410-421`, both `params != null`-guarded) have been silently dropping the Ux752
  client's `chatConfig` name and `sdConfig` whenever the dialog sent a nested `schema` — which
  `chapBook.js:812` (`entity[am7model.jsonModelKey] = 'olio.sd.config'`) always does. **`PictureBookService.
  ensureSchema` (`:172-181`) has the identical substring bug and was NOT touched** (scope) — §6.G item 1.

**Tests.**
- `TestChapBookRestContract` (Service7, H2, no servlet mocking): `parseBody` now invokes the real
  `ChapBookService.parseParams(String)` via reflection instead of re-implementing the deserialization
  in the test, so the contract tests exercise the production parse path. New
  `TestNestedSchemaDoesNotSuppressTheRequestSchema`: `{"sdConfig":{"schema":"olio.sd.config","steps":33}}`
  → non-null, root schema `olio.pictureBookRequest`, nested `steps` 33; the render shape
  `{"chatConfig":"contentAnalysis","sdConfig":{"schema":"olio.sd.config","cfg":4}}` keeps both; an explicit
  root schema is left alone. `@RolesAllowed` sweep now covers 18 resource methods (`checked >= 18`).
- `e2e/chapBookBookSettings.spec.js` (new, REST half; UI half lands with P0-10d) against the live
  Docker stack at `https://127.0.0.1:9443`, as `e2etest_shared` (+ `e2etest_cbother` with its own
  cookie jar for the cross-user case), fresh ChapBook per test via `createChapBook` over two real poems:
  (1) fresh GET → `200 {}`; sparse PUT → 200, `name === 'book-sdConfig'`, values back; GET sees the PUT;
  second sparse PUT keeps the row and the unsent `cfg`/`negativePrompt`; a PUT carrying
  `objectId:'not-this-books-row'`, `groupId:999999`, `name:'not-the-book-config'` leaves the row's
  identity/name intact and lands `steps`; (2) PUT with no `sdConfig` → 400; unknown book → 404 on GET
  and PUT; (3) another non-admin user → 404 on GET and PUT, owner's value untouched.

```
$ cd src && mvn -o -pl AccountManagerService7 -Dtest=TestChapBookRestContract -DskipTests=false test     (C:\tmp\s7-cbcontract-run3.log)
[INFO ] 2026-10-09 22:03:33.695 [main] TestChapBookRestContract - Checked @RolesAllowed on 18 ChapBookService resource methods
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.958 s -- in org.cote.accountmanager.objects.tests.TestChapBookRestContract
[INFO] BUILD SUCCESS
[INFO] Total time:  11.337 s
```
(Run 2, `C:\tmp\s7-cbcontract-run2.log`, before the `ensureSchema` fix and the new test: `Tests run: 6,
Failures: 0 … Time elapsed: 11.39 s … BUILD SUCCESS … Total time: 15.896 s`.)

```
$ cd src/AccountManagerUx752 && PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookBookSettings.spec.js --project=chromium --workers=1 --retries=0     (C:\tmp\pw-cbsettings-run2.log)
Running 3 tests using 1 worker
  ok 1 [chromium] › e2e\chapBookBookSettings.spec.js:44:5 › … › fresh book has no stored config; PUT persists a sparse config; GET reads it back (9.3s)
  ok 2 [chromium] › e2e\chapBookBookSettings.spec.js:100:5 › … › PUT without sdConfig is 400; unknown book is 404 on GET and PUT (7.1s)
  ok 3 [chromium] › e2e\chapBookBookSettings.spec.js:113:5 › … › another non-admin user cannot read or write this book's settings (7.4s)
  3 passed (25.1s)
```
No `[e2e-llm]` line: the spec makes no LLM call (ChapBook create's landscape-prompt call is server-side —
see the LiteLLM note in §6.G item 4).

**Run 1 (`C:\tmp\pw-cbsettings-run1.log`) — `3 failed`.** Every PUT returned `400 {"error":"Request body is
required"}` while the preceding GET on the same fresh book had returned `200 {}` (so the new class *was*
deployed). Server log (`docker logs am7test-am7-1`):
```
[ERROR] 2026-10-10 02:59:50.576 [https-jsse-nio-127.0.0.1-8444-exec-6] RecordDeserializer - Invalid model name
[ERROR] 2026-10-10 02:59:50.577 [https-jsse-nio-127.0.0.1-8444-exec-6] RecordDeserializer - Current node: {"sdConfig":{"schema":"olio.sd.config","steps":33,"cfg":5,"style":"art","artStyle":"watercolor","negativePrompt":"blurry"}}
	at org.cote.rest.services.ChapBookService.parseParams(ChapBookService.java:93) [classes/:?]
	at org.cote.rest.services.ChapBookService.setBookSdConfig(ChapBookService.java:1042) [classes/:?]
```
→ the substring `ensureSchema` defect above. Fixed, regression test added (7/7), class redeployed, run 2
green.

**Live evidence from run 2** (`/var/log/nginx/access.log` inside the container, 10/Oct/2026 03:04 UTC;
columns: method path status bytes):
```
GET  …/chap-book/9ce43b72-d658-4fe7-baa3-3b9a7dec194c/settings 200 2        <- fresh book: {}
PUT  …/9ce43b72-…/settings 200 1130  |  GET … 200 1130  |  PUT … 200 1130  |  PUT … 200 1130  |  GET … 200 1130
PUT  …/59556582-aac0-4a67-91f5-70b9d0f8151f/settings 400 32                  <- no sdConfig
GET  …/no-such-book-1791601482829/settings 404 54  |  PUT …/no-such-book-1791601482844/settings 404 54
PUT  …/9022c5ad-79ad-4c2a-a89d-1ecdc44368e6/settings 200 1253                <- owner seeds steps=12
GET  …/9022c5ad-…/settings 404 64  |  PUT … 404 64                           <- e2etest_cbother
GET  …/9022c5ad-…/settings 200 1253                                          <- owner, still steps=12
```
PBAC audit for the same window — every write as the request user, none as the olio principal:
```
AUDIT INVALID system.user e2etest_shared to READ … olio.sd.config … (name = "book-sdConfig" && groupId = … && organizationId = 2) … No results   <- fresh book, GET → {}
AUDIT PERMIT  system.user e2etest_shared to ADD olio.sd.config book-sdConfig (via: system.user e2etest_shared)
AUDIT PERMIT  system.user e2etest_shared to MODIFY olio.sd.config book-sdConfig (via: system.user e2etest_shared)     (×2: second sparse PUT, identity-carrying PUT)
```

| Test | Task | elapsed (recorded, not asserted) |
|---|---|---|
| `TestChapBookRestContract` (7, H2) | P0-10c | 7.958 s class total |
| `chapBookBookSettings.spec.js` (3, live stack) | P0-10c | 9.3 s / 7.1 s / 7.4 s; 25.1 s run (dominated by `createChapBook`, which runs the server-side landscape-prompt LLM call per book) |

**Redeploy record (Docker test stack `am7test-am7-1`; all times UTC 2026-10-10, `ls -la` inside the container).**
1. `mvn -o -q -pl AccountManagerObjects7 install -DskipTests` → `AccountManagerObjects7-7.0.0-SNAPSHOT.jar`
   **3002036 bytes**; `docker cp` → `WEB-INF/lib/` (**Oct 10 02:53**). Carries Slices A, B and C.
2. `mvn -o -pl AccountManagerService7 compile`; `javap -p` on `target/classes/org/cote/rest/services/
   ChapBookService.class` and `OlioService.class` (no `Unresolved compilation` stubs; `ByteModelUtil.setValue`
   present in `OlioService`; `ObjectNode.put` present in the fixed `ChapBookService`) before each
   `MSYS_NO_PATHCONV=1 docker cp … WEB-INF/classes/org/cote/rest/services/`:
   `OlioService.class` **35858 bytes (01:34)**; `ChapBookService.class` 37320 bytes (endpoints only, copied
   before Playwright run 1 at 02:59 — overwritten, so its timestamp is gone) then **37559 bytes (03:02,
   `ensureSchema` fix)**.
3. `docker restart am7test-am7-1` after each copy (two restarts); `curl -sk -o /dev/null -w '%{http_code}'
   https://127.0.0.1:9443/rest/setup/state` → **200** ~30 s later each time; the only startup WARN is Jersey's
   `LoginService.login` empty-path notice (pre-existing).
4. `/opt/ux752/dist` untouched (no frontend change yet — P0-10d will `vite build` + `docker cp`).

Observations.
- With the fixed `ensureSchema`, `/render` and `/scene/{id}/generate` now actually receive the Ux752
  dialog's `chatConfig`/`sdConfig` for the first time; until P0-10d sends the sparse delta (§6.C item 12),
  the dialog's full in-memory config therefore reaches the render overlay — which is exactly the G-32
  "client config becomes the whole config" shape, now sitting **below** the page override per P0-10b.
  P0-10d is the fix; recorded here so the behaviour change is not mistaken for a regression.
- `ensureSchema` parses the body twice (Jackson tree, then `RecordDeserializer`). Bodies on these routes
  are a few KB; left as is.

### 2.5 Slice C security review + hardening — P0-10c (2026-10-09, 22:18–22:26 local)

Security-reviewer verdict on P0-10c (`ChapBookService` `/settings` endpoints + `PbBookUtil.setBookSdConfig`):
**PASS, no blocking findings, three Low hardening items.** Items checked with no finding: `@RolesAllowed`
on both routes and every read/write through `AccessPoint` as the request user (no olio principal);
cross-user access is a 404 and an FK to an unreadable row reads as `{}`; `getFilteredModule` /
`getUnfilteredModule` are byte-identical in this build, and `referenceImageId` is resolved via
`AccessPoint.find` as the rendering user. The three Low items and what was done:

| # | Finding | Resolution |
|---|---|---|
| 3 | `olio.sd.config` inherits `tags` (`data.tagsExt`, participation), `controls` (`policy.controlsExt`) and `attributes` (`common.attributeList`) from `data.directory`'s ancestry; none is in `SDUtil.SD_OVERRIDE_SKIP` or the P0-10a skip list, so a `PUT …/settings` body `sdConfig:{tags:[…]}` / `controls:[…]` attaches them to the user's own `book-sdConfig` row. Same exposure as generic `PATCH /rest/model` on a row you own — not an escalation. | **Fixed and proven.** `PbBookUtil.BOOK_SD_CONFIG_RENDER_ONLY` → `BOOK_SD_CONFIG_SKIP` = `{description, imagePath, imageName, tags, controls, attributes}` (both `applyOverrides` call sites). `TestChapBookBookSettings.setBookSdConfigRoundTrip` now creates a real `data.tag` as the test user, puts it on the sparse body's `tags`, and asserts `MemberUtil.getMembers(stored, "tags", data.tag)` is empty. The shared `SD_OVERRIDE_SKIP` was **not** widened — render overlays are in-memory and the skip semantics there belong to P0-10b. |
| 4 | `ChapBookService.errorResponse` interpolated the message unescaped; the two new 500 branches pass `e.getMessage()`, so a message with `"` produced malformed JSON. | **Fixed.** `errorResponse` now goes through `escapeJson`, which serializes with Jackson (`SCHEMA_MAPPER.writeValueAsString`). The first cut mirrored `PictureBookService.escapeJson` (replace chain) and the new contract test **failed** on it: a tab in stack-trace text is CTRL-CHAR 9, which that chain does not escape (`JsonParseException: Illegal unquoted character ((CTRL-CHAR, code 9))`, `C:\tmp\s7-cbcontract-run4.log`). `PictureBookService.escapeJson` has the same gap — not touched, §6.G item 3. Plain messages keep the exact wire form (`{"error":"Request body is required"}`), asserted, so the Playwright spec's matches are unchanged. |
| 5 | `ensureSchema` double-parses every body (≈3× body heap; authenticated routes, nginx `client_max_body_size 100m`, Jackson 2.18 depth-bounded — not a security issue) and built `new ObjectMapper()` per request. | **Mapper made `private static final SCHEMA_MAPPER`** (also used by `escapeJson`). The double parse stays (bodies are a few KB). The pre-existing per-request mappers at `:451`, `:508`, `:566`, `:616`, `:838` are not mine and were left — §6.G item 5. |

Order of proof for item 3 (the leak had to be shown before it was closed):

- Run 1 (`C:\tmp\o7-cbsettings-leak-run1.log`): first assertion used `getParticipations(stored, data.tag)`,
  which treats `stored` as the **participant** — the wrong direction for `sd.config.tags` (the config is the
  participation owner). It passed against the unfixed code: a negative result from a setup that could not
  show the condition, not evidence. Corrected to `getMembers(stored, "tags", data.tag)`.
- Run 2, unfixed code (`C:\tmp\o7-cbsettings-leak-run2.log`):
  ```
  [LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
  AUDIT PERMIT system.user chapbookTestUser to ADD data.tag cb-settings-smuggled-b1c46729-… (via: system.user chapbookTestUser)
  java.lang.AssertionError: A tag carried on the sparse settings body must not be attached to the stored config, got 1 member(s)
  Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 16.07 s
  BUILD FAILURE
  ```
- Run 3, fixed code, whole class (`C:\tmp\o7-cbsettings-leak-run3.log`):
  ```
  [LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
  Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 46.12 s -- in org.cote.accountmanager.olio.picturebook.TestChapBookBookSettings
  BUILD SUCCESS   Total time:  50.298 s
  ```

`TestChapBookRestContract` after items 4/5 (`C:\tmp\s7-cbcontract-run5.log`; run 4 is the tab failure above):
```
TestChapBookRestContract - Checked @RolesAllowed on 18 ChapBookService resource methods
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.30 s -- in org.cote.accountmanager.objects.tests.TestChapBookRestContract
BUILD SUCCESS   Total time:  15.241 s
```

Redeploy (third for Slice C): `mvn -o -q -pl AccountManagerObjects7 install -DskipTests` → jar **3002077 bytes
(22:22 local)**, `javap` on the jar's `PbBookUtil.class` shows `BOOK_SD_CONFIG_SKIP`; `ChapBookService.class`
**37929 bytes (22:23)**, `javap` shows `SCHEMA_MAPPER`, `escapeJson`, both `/settings` methods; both
`docker cp`'d (container times 03:22/03:23 UTC), `docker restart am7test-am7-1`, Tomcat `Server startup in
[8289] milliseconds` at 03:24:43 UTC, `/rest/setup/state` → 200. Playwright run 3
(`C:\tmp\pw-cbsettings-run3.log`, `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`, REST-only, no `[e2e-llm]`
line because nothing in the spec routes an LLM call):
```
[1/3] … fresh book has no stored config; PUT persists a sparse config; GET reads it back
[2/3] … PUT without sdConfig is 400; unknown book is 404 on GET and PUT
[3/3] … another non-admin user cannot read or write this book's settings
  3 passed (24.7s)
```
Services before these runs (22:17 local): `postgres3` Up 31 h `:15432`; `am7test-am7-1` Up; `am7test-litellm-1`
healthy `:4000`; `am7test-ollama-1` healthy `:11435`; host `C:` 452G used / 493G free (48%);
`/rest/setup/state` → 200. No SD or `.42` probe — nothing in this slice reaches either.

### 2.6 Slice D results (Ux752) — P0-10d (2026-10-09, 22:49–23:12 local; Playwright 23:05–23:11)

**What changed** (`src/AccountManagerUx752/src/features/chapBook.js` only, +245/−40; the design is §6.C
items 12 and 16 after the planner + architect reviews). The ChapBook screens keep **one** `olio.sd.config`
instance per book: `ensureRenderSdConfig(bookObjectId)` memoizes on the book key, dedupes an in-flight
build on the same key (`_renderSdConfigPromiseKey`, publish-only-if-still-current), and on a different key
discards the old instance and rebuilds — `GET /settings` → stored row (minus `SD_CONFIG_IDENTITY` ∪
`BOOK_SD_CONFIG_SKIP`, `renderSdConfigStored=true`) or, for `{}`, the seed chain `sdcfg-default` →
`am7sd.buildEntity()` (`/olio/randomImageConfig`) → `am7model.newPrimitive`. `computeBookSdDelta(inst,
stored)` is `inst.changes` for a stored row and the whole seeded entity otherwise (item 12(a)), drops
null/undefined and the skip lists, returns `null` when empty. `saveBookSdConfig` PUTs the delta then
`resetChanges()`; `syncBookSdConfigForRender` is what Render / Regenerate / Re-render call: null delta →
`{sdConfig:null}` (the render body carries no `sdConfig`), successful save → likewise, failed save → warn
toast + the delta travels on the render body. `doCreateChapBook` PUTs the create-view panel to the new
book on create success (item 16). Two explicit Save buttons: review toolbar `cb-save-book-sd`, render
dialog `cb-dialog-save-book-sd`, both disabled when the delta is null. Exports added for the tests:
`computeBookSdDelta, getBookSettings, putBookSettings, saveBookSdConfig, syncBookSdConfigForRender,
ensureRenderSdConfig`.

Two ⚠ judgment calls by the ux-specialist, both accepted (recorded as §6.C item 17):
1. The create-view "Image settings" toggle (`renderConfigDesignSettings`) renders in all three mounts
   (PoemLibrary / Reader / Review), so it keys `ensureRenderSdConfig(bookObjectId || null)` on the mount's
   book rather than the literal `null` my brief said — literal `null` would have re-seeded a review book's
   loaded row the moment the toggle was opened.
2. `getBookSettings` treats a 200 body without `schema`/`objectId` as "no row" (the server's `{}`), and any
   non-2xx as an error that is caught in `ensureRenderSdConfig` (warn + seed), so a transient failure
   degrades to the seed path instead of a blank panel.

Architect non-blocking note (backend, out of scope — noted, not fixed, §6.G item 6): `PbConfigUtil.mergeTiers`
`:374` applies the render overlay with only `SD_OVERRIDE_SKIP`, so `description/imagePath/imageName` are not
stripped on the render-overlay path (the stored-row path strips them via `BOOK_SD_CONFIG_SKIP`).

**Vitest** (`src/test/chapBookBookSettings.test.js`, 11 tests: real exported functions, `global.fetch`
stubbed, `am7sd.loadConfig`/`page.toast` spied — delta unstored/stored/identity-skip/null-drop/empty→null;
`getBookSettings` `{}`→null / row / 404 rejects; PUT body shape; sync PUT-fail returns the delta; a null
change in stored mode is dropped; a second sync after a successful PUT sends nothing):
```
 Test Files  1 passed (1)
      Tests  11 passed (11)
```
Whole suite: ` Test Files  81 passed (81)` / `      Tests  1005 passed (1005)`. `npx vite build` →
`✓ built in 5.81s`, `dist/assets/chapBook-DhC5JhzR.js  81.12 kB` (pre-existing `(!) Some chunks are larger
than 500 kB` warning, unchanged). `MSYS_NO_PATHCONV=1 docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/` —
container lists `chapBook-DhC5JhzR.js` (04:07 UTC); the served `index-C9phgr96.js` imports it and the chunk
contains `cb-dialog-save-book-sd`, `cb-save-book-sd`, `Book image settings saved`.

**Playwright** (`e2e/chapBookBookSettings.spec.js`, 3 pre-existing REST tests + 4 new UI tests appended by
the test-author; `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test … --project=chromium
--workers=1`, shared non-admin user, `loginAsSharedUser` WS stub on every page incl. `context.newPage()`,
no render clicked, first attempt, no retries). Field map correction found while writing them: the panel's
second text field is "Action" → `imageAction` (`SdConfigPanel.js:262`), not `actionStyle`.
1. *Save in the toolbar panel persists the book row (first save of an unstored book)* — fresh book asserted
   `{}` → fill Composition with `e2e-comp-<ts>` → Save enabled → click → toast `.toast-text` "Book image
   settings saved" visible **and** `expect.poll` on `GET /settings` → `bodyStyle === value`, `schema ===
   'olio.sd.config'`, `name === 'book-sdConfig'`, `objectId` truthy → Save disabled.
2. *a new page shows the STORED value and Save is disabled* — REST PUT seed; page 1 shows it;
   `context.newPage()` + login shows it; Save disabled (stored-row path, not the seed path).
3. *no cross-book leakage* — A seeded via PUT; page 1 shows A's value; **new page** opens B: Composition ≠ A's
   value, Save enabled (seed path); `GET /settings` for B still `{}` (read path wrote nothing); A unchanged.
4. *once a row exists, Save sends only the changed field* — seed `bodyStyle` + `imageAction` via PUT; panel
   shows both, Save disabled; change Composition; `page.waitForRequest` captures the PUT: `sdConfig` keys are
   exactly `['bodyStyle','schema']` (no `imageAction`/`objectId`/`name`); GET shows the new `bodyStyle`, the
   seeded `imageAction` intact, same `objectId`.
```
7 passed (1.1m)
```
Regressions, same env: `e2e/chapBookSdConfig.spec.js` → `1 passed (11.0s)`; `e2e/chapBookConfigPanel.spec.js`
→ `2 skipped` / `3 passed (26.7s)` — the 2 skipped are the `[LLM]` tests gated on `CHAPBOOK_LLM_TESTS`
(they drive `doRegeneratePagePrompt`, not in this diff); **skipped, not passed**.

**Not verified by a test** (honest gap): the render-time sync half — `syncBookSdConfigForRender` through
`doRenderFromDialog` / `doRegenerateScene` / `doRerenderScene`, the render dialog's `cb-dialog-save-book-sd`,
and `doCreateChapBook`'s PUT to the new book — because exercising them triggers an SD render or the UI create
flow. The delta/sync logic itself is covered by the Vitest (fetch-stubbed), and the REST contract they hit is
the one the Playwright tests above exercise. A live-render Playwright test is the natural follow-up when a
Phase-0 SD run is on the table (`feedback-llm-sd-always-live-end-to-end-required` applies — this is recorded
as a gap, not a pass).

**Security-review Low applied (23:27 local, after the P0-11 redeploy).** `doRegenerateScene` (`:3423`) and
`doRerenderScene` (`:3467`) read the module-level `reviewBookObjectId` **after** their first `await`
(`fetchDefaultChatConfigName`), so navigating from book A to book B mid-call synced and PUT B's config
while A's stale scene rendered with it (B's own data — no cross-user leak, wrong book). Both now capture
`reviewBookObjectId` into a local at entry and pass the local to `syncBookSdConfigForRender`. Re-run:
`npx vite build` → `✓ built in 5.52s` (`chapBook-Druso8m4.js 81.14 kB`, `index-BwGXRF4L.js`); `npx vitest
run` → ` Test Files  81 passed (81)` / `      Tests  1005 passed (1005)`; `docker cp ./dist/.` → container
`index.html` references `index-BwGXRF4L.js`, which imports `chapBook-Druso8m4.js` (04:27 UTC). The P0-10d
verifier was launched against this bundle (its Playwright run is the live check of the change; the two
functions themselves are in the not-Playwright-verified half above).

**P0-10d verifier verdict (23:35 local): PASS-WITH-NOTES — one Medium, one Low, both fixed below (23:40–23:50).**
Verifier's own runs against the 04:27 UTC bundle (served `chapBook-Druso8m4.js` md5 = fresh build):
Playwright `7 passed (1.1m)`; regressions `2 skipped` / `4 passed (41.3s)`; Vitest 11/11 and `81 files /
1005 tests`; Ux752 quick gate `VERIFY_OK`. Findings:
- **Medium** — `ensureRenderSdConfig` caught a **failed** `GET /settings` (non-2xx, network) and fell through
  to the `sdcfg-default` seed exactly as for the server's `{}` "no row". With `renderSdConfigStored=false`
  the whole seed became the pending delta, so the next Save or Render would **PUT the whole seed over a
  real stored row**. This is the risk I had already flagged as §6.C item 17(b); the verifier's rating makes
  it a fix now, not a deferral.
- **Low** — the publish/clear guard was `_renderSdConfigPromiseKey === key`, and `null` is both the
  create-view key and the "idle" value, so a create-view build (key `null`) that settled **after** a later
  book build had cleared the key passed the guard and replaced the book's instance with the seed.
- Coverage gaps restated (unchanged, honest): in-SPA A→B re-key, `cb-dialog-save-book-sd`, `doCreateChapBook`'s
  PUT — see "Not verified by a test" above.

**Fix (⚠ MY JUDGMENT on the shape, recorded as §6.C item 19; `chapBook.js` `ensureRenderSdConfig` + new
`renderSdConfigPlaceholder` + the three panel sites).**
1. *Failed GET never seeds.* The GET failure is kept as `loadError`; the seed chain is skipped; nothing is
   published. The failure is recorded as `renderSdConfigLoadError = { key, message }`, a warn toast
   `Could not load book image settings: <message>` fires, and the promise is cleared so a later open
   re-attempts. Consequences: `computeBookSdDelta(null)` is `null`, so both Save buttons stay disabled and
   `syncBookSdConfigForRender` returns `{sdConfig:null}` — a render proceeds on the **server's stored row**
   (safe; no client overlay). The three panel sites (`renderSdConfigPlaceholder`) now show the error +
   **Retry** (`data-testid="cb-book-sd-load-error"` / `cb-book-sd-retry`) instead of a spinner; Retry calls
   `ensureRenderSdConfig(key)` again. The create view (key `null`) never GETs, so it still seeds.
2. *Build token.* `_renderSdConfigBuild` is a monotonically increasing counter; each build captures
   `build = ++_renderSdConfigBuild` and may publish / clear the in-flight promise **only if** `build ===
   _renderSdConfigBuild`. The key-based early-return dedupe is unchanged. `renderSdConfigLoadError` is reset
   at every build start.

**Tests added.**
- Vitest `src/test/chapBookBookSettings.test.js` (+2, now 13): *GET 500 → resolves null, no seed, warn toast;
  sync sends no PUT; Retry after recovery loads the stored row* (`am7sd.loadConfig` spy asserted **not**
  called through load + sync; PUT stub throws if reached; GET #3 returns the row; memo hit thereafter) and
  *a create-view (null key) build that settles after a later book build does not replace the book instance*
  (seed held on a manual promise, book GET resolves first; the superseded build resolves `null`; a re-ask for
  the book is a memo hit returning the **same** instance; sync sends nothing). Both would fail on the
  pre-fix code (the first on `loadConfig` being called and on sync returning the seed delta after the PUT
  throws; the second on the re-ask refetching and returning a different instance) — stated from reading the
  old branch logic, not from a stash-and-rerun, since the working tree carries many uncommitted edits.
  ```
   Test Files  1 passed (1)
        Tests  13 passed (13)
  ```
  Whole suite: ` Test Files  81 passed (81)` / `      Tests  1007 passed (1007)`. `npx vite build` →
  `✓ built in 5.37s` (`chapBook-CaHduaAY.js`, `index-BEZJ8GMT.js`); `docker cp ./dist/.` → container
  `chapBook-CaHduaAY.js` md5 `6203f1222ed386bef70abfea698e9247` = local build.
- Playwright `e2e/chapBookBookSettings.spec.js` (+1, now 8): *a failed settings GET shows an error + Retry
  (no seed, no Save, no PUT); Retry loads the real stored row*. ⚠ (item 19) it uses `page.route` to fail
  **only the browser's first GET** of that book's `/settings` with a synthesized 503 — the stack is live and
  untouched, the seed PUT was real, Retry's GET and the final REST verification go to the real endpoint; it is
  the only way to reach the client failure path without breaking the stack for the other tests. Asserts: error
  element visible and contains `503`; no Composition input, no Save button, no spinner; exactly one GET
  failed; after Retry the Composition shows the REST-seeded value, Save disabled; `GET /settings` returns the
  same `objectId` and value; the page sent **no PUT**.
  ```
  ok 8 [chromium] › e2e\chapBookBookSettings.spec.js:335:5 › ChapBook book settings — UI (P0-10d) › a failed settings GET shows an error + Retry (no seed, no Save, no PUT); Retry loads the real stored row (8.7s)
  ```
  Full re-run of the spec + the two ChapBook regression specs on the fresh bundle
  (`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 … --project=chromium --workers=1`, 23:47–23:49 local):
  ```
  Running 14 tests using 1 worker
    2 skipped
    12 passed (1.8m)
  ```
  (`chapBookBookSettings` 8/8, `chapBookConfigPanel` 3 passed / 2 `[LLM]` skipped as before, `chapBookSdConfig`
  1/1.) Ux752 quick gate (`LOOP_MODULE=AccountManagerUx752 bash .claude/loop/verify.sh --quick`,
  `C:\tmp\ux752-verify-quick-sliceD-fix.log`): build PASS / test PASS / lint PASS → `VERIFY_OK`.

  **The 23:47 Playwright quotes above were not captured to a log** (the phase verifier's finding, §4) — only
  `e2e/results/.last-run.json` corroborated them. **Re-run with a log 2026-10-10 01:44–01:46 local** for P0-12,
  same command, same bundle (container `dist` md5 = host at 23:56, no frontend edits since),
  `C:\tmp\pw-p012-chapbook-3specs.log` — identical outcome, verbatim:
  ```
  Running 14 tests using 1 worker
    ok  1 [chromium] › e2e\chapBookBookSettings.spec.js:44:5 › … fresh book has no stored config; PUT persists a sparse config; GET reads it back (7.9s)
    ok  2 [chromium] › e2e\chapBookBookSettings.spec.js:100:5 › … PUT without sdConfig is 400; unknown book is 404 on GET and PUT (7.3s)
    ok  3 [chromium] › e2e\chapBookBookSettings.spec.js:113:5 › … another non-admin user cannot read or write this book's settings (8.0s)
    ok  4 [chromium] › e2e\chapBookBookSettings.spec.js:184:5 › … Save in the toolbar panel persists the book row (first save of an unstored book) (9.8s)
    ok  5 [chromium] › e2e\chapBookBookSettings.spec.js:218:5 › … a new page shows the STORED value and Save is disabled (stored-row load, not the seed path) (8.5s)
    ok  6 [chromium] › e2e\chapBookBookSettings.spec.js:243:5 › … no cross-book leakage: book B does not show book A's value and opening B writes no row (13.5s)
    ok  7 [chromium] › e2e\chapBookBookSettings.spec.js:276:5 › … once a row exists, Save sends only the changed field (sparse PUT) and unsent fields survive (7.8s)
    ok  8 [chromium] › e2e\chapBookBookSettings.spec.js:335:5 › … a failed settings GET shows an error + Retry (no seed, no Save, no PUT); Retry loads the real stored row (8.0s)
    ok  9 [chromium] › e2e\chapBookConfigPanel.spec.js:93:5 › … CREATE view: panel + queue at top, inline collapse/expand (7.8s)
    ok 10 [chromium] › e2e\chapBookConfigPanel.spec.js:119:5 › … REVIEW view: panel + queue at top of the scene list (7.9s)
    ok 11 [chromium] › e2e\chapBookConfigPanel.spec.js:136:5 › … READ view: panel + queue at top of the reader (7.7s)
    -  12 [chromium] › e2e\chapBookConfigPanel.spec.js:155:5 › … [LLM] per-page Regen prompt re-derives + persists a genuine landscape prompt
    -  13 [chromium] › e2e\chapBookConfigPanel.spec.js:218:5 › … [LLM] per-page Analyze re-derives + persists a mood
    ok 14 [chromium] › e2e\chapBookSdConfig.spec.js:35:5 › … override sets a sparse delta, persists it, and Clear removes it (8.5s)
    2 skipped
    12 passed (1.8m)
  PW_EXIT=0
  ```
  (Test titles elided at `…` for width only; the log has them in full.) The two skips are the spec's own
  `[LLM]` env-flag gate (`chapBookConfigPanel.spec.js`), pre-existing and not Phase 0 — they are not a Phase 0
  test being skipped.

§6.C item 17(b) is therefore superseded: a non-2xx GET no longer degrades to the seed path.

### 2.7 Slice D results (backend) — P0-11 Kontext Deprecate (2026-10-09, 23:00–23:25 local)

**What changed.** `SceneCompositeUtil.resolveMode(sdConfig, legacyKontextDefault)` (`:82-107`) now wraps
the unchanged rules (private `resolveModeValue`) and logs **one** WARN — `compositeMode=kontext is deprecated
(V3 design §37.3): FLUX.2 is the supported composite path; Kontext is disabled in Phase 2 and removed in
Phase 10` — whenever the result is `kontext`; return values unchanged. `ChatService.java:1487-1492`
`resolveMode(sdConfig, true)` → `false` (grep: it was the only `true` caller; `PbNodeExecutor:468`,
`PictureBookUtil:3761/3775` already pass `false`), so chat's absent-field fallback is classic like every other
caller; an explicit `compositeMode=kontext` / `useKontext=true` still resolves to Kontext. `configModel.json`
`useKontext` (`:315`), `kontextModel` (`:393`), `kontextSteps` (`:401`), `kontextCfg` (`:409`),
`kontextSampler` (`:415`), `kontextScheduler` (`:421`) descriptions prefixed `DEPRECATED (V3 §37.3; disabled
in Phase 2, removed in Phase 10): ` — descriptions only, `compositeMode` untouched, `python -I json.load` OK;
same six prefixes in the Ux752 mirror `core/modelDef.js:10312-10414`. Scratch writers → `./target/
kontext-test-output` (`TestKontext.java:43`, `TestPictureBookFull.java:3128,:3130`; the only non-doc
literals under `src/`), `src/AccountManagerObjects7/.gitignore` += `/kontext-test-output/`. The tracked
`kontext-test-output/kontext-request.json` is left for Stephen's `git rm` (§6.C item 15).

New `TestKontextDeprecation` (Objects7, JUnit 4, 7 tests, no DB — registers models like `TestFlux2Composite`):
(a) cleared `compositeMode` + default `useKontext` with `false` → classic (the chat case) and null config;
(b) `useKontext=true` → kontext; (c) `compositeMode="kontext"` / `" KONTEXT "` → kontext; (d) WARN count
exactly 1/2/3 across the three kontext paths and 0 for classic/flux2/typo/null, captured by an
`AbstractAppender` attached to the `SceneCompositeUtil` `LoggerConfig` (not `core.Logger.addAppender`, which
creates a non-additive config that mutes the logger's console output for the rest of the JVM — hit, then
switched); (e) the six schema descriptions carry the prefix and `compositeMode` does not.
```
[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.461 s -- in org.cote.accountmanager.objects.tests.TestFlux2Composite
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.cote.accountmanager.objects.tests.TestKontextDeprecation
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
**The block above was quoted from the console, not a log** (the phase verifier's Medium #1, §4). **Re-run with a
log 2026-10-10 01:45 local** for P0-12 — working tree, `cd src && mvn -o -pl AccountManagerObjects7
-Dtest=TestKontextDeprecation,TestFlux2Composite -DskipTests=false test`, `C:\tmp\o7-p012-kontext-flux2.log`:
```
[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.48 s -- in org.cote.accountmanager.objects.tests.TestFlux2Composite
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in org.cote.accountmanager.objects.tests.TestKontextDeprecation
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  23.381 s
KF_EXIT=0
```
Same 43/0/0; 10 `compositeMode=kontext is deprecated` WARN lines are in the log (`grep -c`) — every kontext
resolution across both classes reached the console, confirming the appender attachment left root additivity intact. (This class pair has no
LLM/SD call — no `[LLM-GATE]` line is emitted because `TestKontextDeprecation`/`TestFlux2Composite` do not
extend `BaseTest`.)
`TestFlux2Composite`'s pre-existing `resolveMode` assertions (`:395-490`, incl. `resolveMode(null, true)` →
KONTEXT) pass unchanged — the "no behaviour change for a config that explicitly asks for Kontext" requirement.
`mvn -o -q -pl AccountManagerObjects7 install -DskipTests` → exit 0. Service7: Eclipse auto-build had already
rewritten `ChatService.class`, so the class was deleted and `mvn -o -pl AccountManagerService7 compile`
re-emitted it (`Compiling 69 source files … BUILD SUCCESS`); `javap -c` shows `iconst_0` immediately before
`invokestatic SceneCompositeUtil.resolveMode`, and `javap -p` has no `Unresolved compilation` stub.

**Redeploy** (fourth for Phase 0; after the P0-10d Playwright run finished): `javap` on the extracted jar
shows `resolveMode` + `resolveModeValue`, the jar's `configModel.json` has 6 `DEPRECATED (V3` lines; jar
3002286 B and `ChatService.class` 60017 B + `ChatService$ChainRequestException.class` `docker cp`'d to
`/opt/tomcat/webapps/AccountManagerService7/WEB-INF/{lib,classes/org/cote/rest/services}/` (04:09/04:10 UTC);
`npx vite build` → `✓ built in 5.62s` (`chapBook-C0cXoik8.js`, `index-2wwCfzG2.js`), `docker cp ./dist/.`;
`docker restart am7test-am7-1` → Tomcat `Server startup in [8288] milliseconds` at 04:19:10 UTC;
`/AccountManagerService7/rest/setup/state` → `{"initialized": true}` (`application/json`), `/rest/schema`
200 JSON, unauthenticated `/chap-book/x/settings` → 403. (Probe note for the next session: on `:9443` the
REST base is `/AccountManagerService7/rest/…`; a bare `/rest/...` probe is answered 200 `text/html` by the
SPA fallback and proves nothing.)

Playwright regression for the `ChatService` path, same env:
`e2e/chatChainSceneReimageRoutes.spec.js` → `8 passed (4.6s)`, `1 skipped` (the charPerson reimage test
behind `E2E_SD_LIVE`, unrelated to this task). The `kontext`/`KONTEXT` posts in that spec 404 at
`ChatService.java:1440` (random session) **before** `resolveMode` at `:1490`, so the WARN is not reached by
any live transport test; as §6.C item 14 says, the flip is verified by compile + bytecode + the Objects7
`resolveMode` tests, not by a live chat render. **Superseded 2026-10-10 by §2.8** — Stephen asked for the
live `generateScene` e2e ("Yes I want the generateScene 2e2 done live") and it now exists and is green.

### 2.8 P0-11 live `generateScene` e2e + item-15 reimage (2026-10-10, runs 1/3/4 — Stephen's instruction)

**What was written.** `e2e/chatChainSceneReimageRoutes.spec.js` gained a second `E2E_SD_LIVE`-gated describe,
`generateScene live composite modes (item 14 live)` (`:431-560`), that drives the **whole** Service7 path as the
shared non-admin user: `ensureChatConfig` (own `system.connection` + `olio.llm.chatConfig` on the resolved LLM
route), two stable `olio.charPerson`s in `~/Characters` (`Mira SceneE2E` female / `Theo SceneE2E` male, reused
across runs, PATCHed onto the chatConfig as `systemCharacter`/`userCharacter`), a `promptConfig`, a session via
`POST /chat/new`, **two** real `POST /chat/text` turns, then two `POST /chat/{objectId}/generateScene` renders
against the stack's Swarm `.39`, each with a container-log slice taken by snapshotting `docker logs` before and
after with the **same absolute `--since`** (`generateScene()` `:397-429`). Asserts, per render: HTTP 200, a
`data.data` image record named `Mira and Theo …`, the PNG decoded, and the pipeline choice **from the server log**:

- **(a) explicit `compositeMode:'classic'`** → log contains `Scene composite [classic]`, not `[kontext]`/`[flux2]`,
  `generateScene Stage 2: Skipped (skipLandscape=true)`; PNG is the posted **512×512**; a `Scene description:` line
  exists and is not the no-history fallback `two people facing each other`.
- **(b) `compositeMode` and `useKontext` absent** → log contains `Scene composite [flux2]`, not `[kontext]`/`[classic]`;
  PNG is **1024×768** (the `flux2Defaults.json` size, overriding the posted 512×512); same description assertions.

Case (b) pins the contract the earlier draft had wrong: the REST deserializer materializes the `olio.sd.config`
schema default `compositeMode="flux2"` (`configModel.json:312-322`), so an absent mode is **FLUX.2, not classic**;
the P0-11 `legacyKontextDefault=false` flip (`ChatService.java:1492`) is only reachable for hand-built configs with
a null `compositeMode` — a REST body is never one (design §1709-1716). A future edit restoring `true` there would
still not flip (b) to Kontext for REST callers, but (a)+(b) together now prove the deployed `ChatService` routes
each mode to the pipeline the log names, which is the observable the phase-level verifier asked for (§6.C item 14).

**Run 4 — 2026-10-10 12:26:21–12:29:34 local (`C:\tmp\pw-scene-live-run4.log`), command from `src/AccountManagerUx752`:
`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 E2E_SD_LIVE=1 npx playwright test e2e/chatChainSceneReimageRoutes.spec.js --project=chromium --workers=1`
→ `10 passed (3.2m)`, `PW_EXIT=0`.** Verbatim lines:

```
[item14-live] [e2e-llm] route=litellm server=http://litellm:4000 analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr
[item15] reimage Mira SceneE2E took 17716ms, body chars=616695
[item15] new portrait objectId=1f48a760-15eb-4ac5-8940-c48bdf8f3400 name=Mira SceneE2E - Photo Op - 1 - 2135909243 - 979114665 group=/Olio/Universes/My Grid Universe/Worlds/My Grid World/Gallery/Characters/Mira SceneE2E
[item15] reimage-portrait.png rendered 512x512 (460140 bytes)
[item14-live] session e2e-scene-classic-7ba34361 434aa106-cacb-484a-a7fc-4f9474ad3482
[item14-live] chat turn HTTP 200 durationMs=27534
[item14-live] chat turn HTTP 200 durationMs=12694
[item14-live] classic generateScene HTTP 200 durationMs=21123 body chars=581638
[item14-live] classic scene objectId=26a92fef-dc06-4025-b2fb-8143431198fa name=Mira and Theo - scene - 1 - 329619336 - 1814881168 group=/home/e2etest_shared/Gallery/Scenes/Mira and Theo
[item14-live] scene-classic.png rendered 512x512 (219425 bytes)
[item14-live] classic [INFO ] 2026-10-10 17:27:42.848 [https-jsse-nio-127.0.0.1-8444-exec-4] SceneCompositeUtil - Scene composite [classic]: composite=213463 bytes creativity=0.65
[item14-live] classic ... Chat - Scene description: Theo and Mira stand at the lighthouse entrance, facing each other. Theo reaches for the door handle, while Mira gestures toward the sea. Golden light filters through stained glass, casting warm hues on wet sand. ...
[item14-live] schema-default generateScene HTTP 200 durationMs=107276 body chars=7353518
[item14-live] schema-default scene objectId=60107ed4-d00c-4c01-86b1-3a0773c2dd35 name=Mira and Theo - scene - 1 - 190556960 - 1647975427
[item14-live] scene-schema-default.png rendered 1024x768 (1173912 bytes)
[item14-live] schema-default [INFO ] 2026-10-10 17:27:53.671 [https-jsse-nio-127.0.0.1-8444-exec-3] SceneCompositeUtil - Scene composite [flux2]: model=flux2Klein_9b refs=2 [person1=yes person2=yes setting=no] cfg=2.0 steps=4 1024x768
[item14-live] schema-default ... Scene description: Theo and Mira stand at the lighthouse entrance, hands clasped behind their backs. Soft golden light filters through stained glass windows...
```
Trend (never a threshold): run 1 (earlier the same morning) `10 passed (3.6m)`, classic 24018 ms, flux2 117123 ms
(`refs=1 [person1=yes person2=NO]` — the portrait had not been rendered yet), item15 32322 ms; run 3 (12:13–12:15)
`10 passed (3.0m)`, classic 19713 ms, flux2 105027 ms, item15 8805 ms; run 4 as above. Run 2 was discarded: its
log window was taken with a host-clock `--since now-5s` and the previous render's `[classic]` line leaked into the
flux2 window because the Docker VM clock is skewed from the host — fixed by the absolute-`--since` snapshot pair.

**PNGs opened (Read) and described.** `scene-schema-default.png` (1024×768, FLUX.2): a stone, pillared hall with
tall stained-glass windows, the sea visible through arched openings, patches of coloured light on the floor, a
blonde woman and a bald bearded man standing side by side — the lighthouse/stained-glass description was honoured.
`scene-classic.png` (512×512, classic img2img at creativity 0.65 over the composite canvas): a mostly black canvas
with two small portrait insets in the lower half; **both insets read as blonde women**, so it is unclear whether the
right-hand inset is Theo's portrait — recorded as an observation, not investigated (classic is the legacy path
Phase 10 removes). `reimage-portrait.png` (512×512, item 15) was **not** opened: Playwright clears
`e2e/results` on the next run and the 12:33 split-verification run (§4.2) removed run 4's output directory before I
read it — the item-15 evidence is the decoded-PNG magic + IHDR assertion and the logged size, not a visual check.

**Both figures in every scene render are nude.** Not a Phase 0 defect and not fixed: `NarrativeUtil.getSDMinPrompt`
(`:1057-1105`) emits `(null) (null hair)` for a charPerson with no narrative, and `describeOutfit` (`:341-347`)
emits `"naked/nude, wearing no clothes"` when the character has no apparel — the fixtures are bare `olio.charPerson`
rows with only name/gender/age/race. Options for Stephen (§6.L): give Mira/Theo apparel in the fixture, or reuse a
populated PictureBook cast character.

**Pre-existing finding recorded, not fixed (scope):** `Chat.generateSceneDescription` (`Chat.java:2322-2327`) →
`ChatUtil.getFormattedChatHistoryFrom(…, pruneSkip + 2)` (`ChatUtil.java:2618-2619`) assumes the assist-template
layout (system, user-template, assistant-template). With the chatConfig schema default `assist=false` the session
after one turn is system(0)+user(1)+assistant(2), so the first exchange is below the start index and the description
silently falls back to `"two people facing each other"` with no LLM call. Measured on run 1's first draft (one turn);
the test uses two turns and asserts the fallback text is absent. §6.G item 13.

**Item 15 note:** the reimage test picks the first charPerson in the shared user's `~/Characters` that it can
render, so which character it reimages is run-dependent (`Mira SceneE2E` in runs 3/4); the assertion is on the
new portrait record and the 512×512 PNG, not on the name.

---

## 3. Services checked

### Session 2026-10-09 (Phase 0 start) — gate GREEN with two recorded facts

Required services all answer. Facts that differ from the brief's wording, recorded rather than
blocked on:

1. **`am7-devpg-emulator` no longer exists.** `docker ps -a` shows no such container. The Objects7
   test DB `am7db` (`test.db.url=jdbc:postgresql://localhost:15432/am7db`) is now served by container
   **`postgres3`** (`pgvector/pgvector:0.8.6-pg18-trixie`, created 2026-10-08T20:37Z — after the
   2026-10-07 WSL crash that wiped container records). It hosts `am72db`, `am7db`, `am7isotestdb`.
   `am7db` is populated: 135 public tables, 170 `a7_system_modelschema_0_1` rows. **`am72db` shares this
   host:port and is never touched.**
2. **Service7's status endpoint is `/rest/setup/state`**, not `/rest/setup/status` (the latter 404s).
3. Vite `:8899` is **down**. Not needed for Phase 0 (no UI behaviour; Playwright runs against `:9443`).
4. The deployed image `am7:latest` was built 2026-10-08T20:37Z and **predates commit `9085a440 Patch`
   (2026-10-09 09:49)**, which touched `OlioContext`, `PictureBookUtil`, `PolicyUtil`, `AuthorizationUtil`,
   `AsyncJobRegistry`, `ChapBookService`, `ChatService`, `PictureBookService`. The Phase 0 redeploy carries it.

```
$ docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
am7test-am7-1              Up 24 hours             0.0.0.0:9443->8443/tcp
postgres3                  Up 24 hours             0.0.0.0:15432->5432/tcp
am7test-litellm-1          Up 24 hours (healthy)   127.0.0.1:4000->4000/tcp
am7test-langfuse-web-1     Up 24 hours (healthy)   127.0.0.1:3001->3000/tcp
am7-pg                     Up 24 hours (healthy)   0.0.0.0:15433->5432/tcp
am7test-ollama-1           Up 24 hours (healthy)   127.0.0.1:11435->11434/tcp
am7test-ollama-init-1      Exited (0) 24 hours ago
(+ pgadmin, langfuse worker/minio/clickhouse/db/redis)

$ docker exec postgres3 psql -U am7user -d postgres -Atc "select datname from pg_database order by 1"
am72db / am7db / am7isotestdb / postgres / template0 / template1
$ docker exec postgres3 psql -U am7user -d am7db -Atc "select count(*) from information_schema.tables where table_schema='public'; select count(*) from a7_system_modelschema_0_1;"
135
170

$ curl -sk https://127.0.0.1:9443/AccountManagerService7/rest/setup/status   -> 404 (Tomcat HTML)
$ curl -sk https://127.0.0.1:9443/AccountManagerService7/rest/setup/state    -> { "initialized" : true }
$ docker inspect am7test-am7-1  -> running health=n/a created=2026-10-08T20:53:50Z image=am7:latest
$ docker image inspect am7:latest --format '{{.Created}}'  -> 2026-10-08T20:37:07Z
   WEB-INF/lib: AccountManagerObjects7-7.0.0-SNAPSHOT.jar (Oct 8 20:34), ISO42001 (20:36), Agent7 (20:35)
   /opt/ux752/dist/index.html  Oct 8 16:00
$ curl http://127.0.0.1:8899/  -> 000 (DOWN)

$ curl http://192.168.1.42:11434/api/tags -> models:
['bge-m3:latest', 'creat:latest', 'goekdenizguelmez/JOSIEFIED-Qwen3:30b', 'goekdenizguelmez/JOSIEFIED-Qwen3:8b',
 'gpt-oss:120b', 'nomic-embed-text:latest', 'qwen3-27b:latest', 'qwen3-coder-next:latest', 'qwen3-coder:30b',
 'qwen3-vl:8b-instruct', 'qwen3:32b', 'qwen3:8b', 'valkyriesys/eudaimonia-dryad3-vision:latest', 'way-local:latest']
   qwen3:8b PRESENT; valkyriesys/eudaimonia-dryad3-vision PRESENT
$ curl http://127.0.0.1:11435/api/tags -> ['goekdenizguelmez/JOSIEFIED-Qwen3:8b', 'nomic-embed-text:latest', 'qwen3:8b']
$ curl http://127.0.0.1:4000/health/liveliness -> 200
$ curl http://127.0.0.1:3001/api/public/health  -> 200

$ POST http://192.168.1.39:7801/API/GetNewSession -> session ok
$ POST /API/ListModels (Stable-Diffusion, depth 3) ->
['OfficialStableDiffusion/sd_xl_base_1.0.safetensors', 'ZImage/SwarmUI_Z-Image-Turbo-FP8Mix.safetensors',
 'chilloutmix_Ni.safetensors', 'chroma_v10HD.safetensors', 'chromagenIL_somni.safetensors',
 'cyberrealisticPony_v160.safetensors', 'flux2Klein_9b.safetensors', 'flux2_dev.safetensors',
 'juggernautXL_ragnarokBy.safetensors', 'krea2TurboOfficialComfy_krea2RawInt8Convrot.safetensors',
 'lustifySDXLNSFW_endgame.safetensors', 'ponyRealism_V22.safetensors', 'realmixXL_v10.safetensors']
   juggernautXL_ragnarokBy PRESENT; flux2Klein_9b PRESENT; sd_xl_base_1.0 PRESENT
$ POST /API/ListBackends ->
0 comfyui_selfstart running features: [comfy_just_load_model, comfy_latent_blend_masked, comfy_loadimage_b64,
 comfy_saveaudio_ws, comfy_saveimage_ws, comfyui, controlnet, cubiqipadapter, cubiqipadapterunified,
 endstepsearly, folderbackslash, freeu, ipadapter, refiners, sam2, seamless, variation_seed, video, yolov8]
   ipadapter PRESENT; sam2 PRESENT; yolov8 PRESENT

$ docker exec am7test-am7-1 curl -s -o /dev/null -w '%{http_code}' http://192.168.1.42:11434/api/tags -> 200
$ docker exec am7test-am7-1 curl -s -o /dev/null -w '%{http_code}' http://192.168.1.39:7801/          -> 302
$ docker exec am7test-am7-1 curl -s -o /dev/null -w '%{http_code}' http://ollama:11434/api/tags       -> 200

$ df -h /c -> 944G total, 441G used, 504G free (47%)
   docker_data.vhdx 56.4 GB
```

Admin password for the `am7test` stack: `password` (all three orgs), confirmed in
`dockerDevSetup.md` §4. Tests use `ensureSharedTestUser()` — never admin.

### Re-probe 2026-10-09 before the Slice A test run (`TestSDUtilCreateImage`) — GREEN

```
$ docker ps --format '{{.Names}}\t{{.Status}}'  -> am7test-am7-1 Up; postgres3 Up; am7test-litellm-1 Up (healthy);
   am7-pg Up (healthy); am7test-ollama-1 Up (healthy)
$ docker exec postgres3 psql -U am7user -d am7db -Atc "select count(*) from a7_system_modelschema_0_1" -> 170
$ curl -sk https://127.0.0.1:9443/AccountManagerService7/rest/setup/state -> {"initialized":true}
$ POST http://192.168.1.39:7801/API/ListModels -> flux2Klein_9b, juggernautXL_ragnarokBy, sd_xl_base_1.0 (+10) PRESENT
$ curl http://192.168.1.42:11434/api/tags -> qwen3:8b PRESENT; valkyriesys/eudaimonia-dryad3-vision:latest PRESENT
$ df -h /c -> 944G total, 442G used, 502G free
```
Gate lines from the run itself: `[LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000`;
`Live SD checkpoint for http://192.168.1.39:7801 = 'OfficialStableDiffusion/sd_xl_base_1.0.safetensors' [olio.sd.config schema default]`.

### Re-probe 2026-10-09 before the Slice B re-run (`TestOllamaModelUnload`, `TestPbGraph`, `TestPbSceneGenerationGuards`) — GREEN

```
$ docker ps --format '{{.Names}}\t{{.Status}}'  -> am7test-am7-1 Up 28 hours; postgres3 Up 28 hours;
   am7test-litellm-1 Up 28 hours (healthy); am7-pg Up 28 hours (healthy); am7test-ollama-1 Up 28 hours (healthy)
$ docker exec postgres3 psql -U am7user -d am7db -Atc "select count(*) from a7_system_modelschema_0_1" -> 170
$ curl -sk https://127.0.0.1:9443/AccountManagerService7/rest/setup/state -> { "initialized" : true }
$ curl http://192.168.1.42:11434/api/tags -> qwen3:8b PRESENT; valkyriesys/eudaimonia-dryad3-vision PRESENT
$ curl http://127.0.0.1:4000/health/liveliness -> 200
$ POST http://192.168.1.39:7801/API/ListModels -> 13 models; sd_xl_base_1.0 PRESENT; flux2Klein_9b PRESENT
$ POST /API/ListBackends -> 0 comfyui_selfstart running; ipadapter True; sam2 True; yolov8 True
$ docker exec am7test-am7-1 curl … http://192.168.1.42:11434/api/tags -> 200;  … http://192.168.1.39:7801/ -> 302
$ df -h /c -> 944G total, 451G used, 494G free (48%)
```
Gate lines from the run itself (`C:\tmp\o7-sliceB-rerun1.log`): `[LLM-GATE] route=litellm tier=local
analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000`; `Live SD checkpoint for
http://192.168.1.39:7801 = 'OfficialStableDiffusion/sd_xl_base_1.0.safetensors' [olio.sd.config schema default]`.

### Re-probe 2026-10-09 20:25 before the `TestPictureBookWorkflow` solo run (§4.1 run-2 OPEN items) — GREEN

```
$ docker ps -> am7test-am7-1 Up 29 hours; postgres3 Up 29 hours; am7test-litellm-1 Up 29 hours (healthy);
   am7test-ollama-1 Up 29 hours (healthy); langfuse web/worker/db/redis/minio/clickhouse Up 29 hours
$ docker exec postgres3 psql -U am7user -d am7db -At -c "select count(*) from a7_system_modelschema_0_1" -> 170
$ curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9443/rest/setup/state -> 200
$ curl http://192.168.1.42:11434/api/tags -> ['valkyriesys/eudaimonia-dryad3-vision:latest', 'qwen3:8b']
$ curl http://127.0.0.1:4000/health/liveliness -> 200
$ curl http://127.0.0.1:11435/api/tags -> ['qwen3:8b', 'goekdenizguelmez/JOSIEFIED-Qwen3:8b', 'nomic-embed-text:latest']
$ POST http://192.168.1.39:7801/API/ListModels -> models: 13
$ POST /API/ListBackends -> {'0': ('running', ['yolov8', 'sam2', 'ipadapter'])}
$ docker exec am7test-am7-1 curl … http://192.168.1.42:11434/api/tags -> 200;  … http://192.168.1.39:7801/ -> 302
$ df -h /c -> C: 944G 451G 494G 48% /c
```

### Re-probe 2026-10-09 (after the solo run) before `TestPbSceneGenerationGuards` run 3 — GREEN

```
$ docker ps -> postgres3 Up 30 hours (0.0.0.0:15432->5432); am7test-am7-1 Up 29 hours; am7test-litellm-1 Up (healthy);
   am7test-ollama-1 Up (healthy); langfuse web/worker/db/redis/minio/clickhouse Up (healthy)
$ docker exec postgres3 psql -U postgres -d am7db -tAc "select count(*) from a7_system_modelschema_0_1" -> 170
$ curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9443/rest/setup/state -> 200
$ curl http://192.168.1.42:11434/api/tags -> ['bge-m3:latest', 'creat:latest', 'goekdenizguelmez/JOSIEFIED-Qwen3:30b',
   'goekdenizguelmez/JOSIEFIED-Qwen3:8b', 'gpt-oss:120b', 'nomic-embed-text:latest', 'qwen3-27b:latest',
   'qwen3-coder-next:latest', 'qwen3-coder:30b', 'qwen3-vl:8b-instruct', 'qwen3:32b', 'qwen3:8b',
   'valkyriesys/eudaimonia-dryad3-vision:latest', 'way-local:latest']   qwen3:8b PRESENT; dryad3-vision PRESENT
$ curl http://127.0.0.1:11435/api/tags -> ['goekdenizguelmez/JOSIEFIED-Qwen3:8b', 'nomic-embed-text:latest', 'qwen3:8b']
$ curl http://127.0.0.1:4000/health/liveliness -> 200
$ POST http://192.168.1.39:7801/API/GetNewSession -> session ok; POST /API/ListModels -> models: 13
$ POST /API/ListBackends -> {'0': ('running', ['yolov8', 'sam2', 'ipadapter'])}
$ docker exec am7test-am7-1 curl … http://192.168.1.42:11434/api/tags -> 200;  … http://192.168.1.39:7801/ -> 302
$ df -h /c -> C: 944G 452G 493G 48% /c
$ curl http://127.0.0.1:8899/ -> 000 (Vite DOWN — not needed until the Slice C/D Playwright runs)
$ tasklist | grep java -> only PID 23524 = VS Code redhat.java JDT language server (no test/surefire JVM running)
```

### Re-probe 2026-10-10T02:40:38Z (2026-10-09 21:40 local) before the Slice C backend run (`TestChapBookBookSettings`, `TestChapBookRenderStyle`) — GREEN

```
$ df -h /c -> C: 944G 452G 493G 48% /c
$ docker ps -> postgres3 Up 30 hours (0.0.0.0:15432->5432/tcp); am7test-am7-1 Up 30 hours; am7test-litellm-1 Up (healthy);
   am7test-ollama-1 Up (healthy); langfuse web/worker/db/redis/minio/clickhouse Up (healthy)
$ curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9443/rest/setup/state -> 200
$ curl http://192.168.1.42:11434/api/tags -> 14 models incl. 'qwen3:8b', 'valkyriesys/eudaimonia-dryad3-vision:latest',
   'goekdenizguelmez/JOSIEFIED-Qwen3:8b'   qwen3:8b PRESENT; dryad3-vision PRESENT
$ curl http://127.0.0.1:11435/api/tags -> ['goekdenizguelmez/JOSIEFIED-Qwen3:8b', 'nomic-embed-text:latest', 'qwen3:8b']
$ curl http://127.0.0.1:4000/health/liveliness -> 200
$ POST http://192.168.1.39:7801/API/GetNewSession -> session ok (version 0.9.8.3.GIT-d9ecb52d); POST /API/ListModels -> 13 models
   incl. OfficialStableDiffusion/sd_xl_base_1.0.safetensors
$ POST /API/ListBackends -> backend 0 running ipadapter=True sam2=True yolov8=True
$ docker exec am7test-am7-1 curl … http://192.168.1.42:11434/api/tags -> 200;  … http://192.168.1.39:7801/ -> 302
$ curl http://127.0.0.1:8899/ -> 000 (Vite DOWN — not needed for the Objects7 run; required before P0-10d Playwright)
```

### P0-10c transport runs (2026-10-09 20:34–22:26 local) — stack re-checked, not re-probed in full

The P0-10c Service7 contract runs (H2, no services) and the three Playwright REST runs used the stack
probed above; before each Playwright run `docker ps` + `/rest/setup/state` were re-checked (always
`200` after the restart that deployed the new class — §2.4 redeploy record, §2.5). Vite was still down
and is **not** needed for the REST-only half of `e2e/chapBookBookSettings.spec.js` (no page is opened);
it must be up and `dist` current before the P0-10d UI tests. The security-hardening runs (§2.5) re-checked
`postgres3`, `am7test-am7-1`, LiteLLM, the local Ollama container and host disk at 22:17 local; no SD or
`.42` probe because nothing in that slice reaches either. The Service7 module gate (22:32, §4.1) needs only
the module-local H2 file and `postgres3`'s `am7isotestdb` (`TestISO42001Service` 10/10 green proves the
latter answered); host `C:` 452G free of 944G (48 %) at 22:31.

### Re-probe 2026-10-09 22:49:24 local before P0-10d (Ux752 UI) — GREEN after starting Vite

Full §2 probe, dev box and in-container. Vite `:8899` was **down** (`curl → 000`); started in the
background from `src/AccountManagerUx752` (`npx vite --port 8899`, log `C:\tmp\vite-8899.log`, only a
browserslist-staleness warning) and re-probed → `200`. Playwright for P0-10d runs against `:9443`
(`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`), so `dist` must be re-copied into the container after
every `vite build` (recorded per run in §2.6).

```
$ docker ps --format '{{.Names}} {{.Status}}'
am7test-am7-1 Up 24 minutes            (restarted at 22:25 for the P0-10c security-hardening redeploy)
postgres3 Up 31 hours
am7test-litellm-1 Up 31 hours (healthy)
am7test-ollama-1 Up 31 hours (healthy)
am7test-langfuse-web-1 / -worker-1 / -minio-1 / -clickhouse-1 / -db-1 / -redis-1  Up 31 hours
$ curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9443/rest/setup/state        -> 200
$ curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:8899/                        -> 000, then 200 after start
$ curl -s http://192.168.1.42:11434/api/tags | jq -r '.models[].name' | sort
bge-m3:latest creat:latest goekdenizguelmez/JOSIEFIED-Qwen3:30b goekdenizguelmez/JOSIEFIED-Qwen3:8b
gpt-oss:120b nomic-embed-text:latest qwen3-27b:latest qwen3-coder-next:latest qwen3-coder:30b
qwen3-vl:8b-instruct qwen3:32b qwen3:8b valkyriesys/eudaimonia-dryad3-vision:latest way-local:latest
   (both required models present: qwen3:8b, valkyriesys/eudaimonia-dryad3-vision)
$ curl -s -o /dev/null -w '%{http_code}' http://192.168.1.39:7801/                       -> 302
$ docker exec am7test-am7-1 sh -c 'curl ... 192.168.1.42:11434/api/tags; curl ... 192.168.1.39:7801/; curl ... postgres3:5432'
ollama42=200 swarm39=302 pg15432=000   (Postgres is not HTTP — expected; the JDBC path is proven by the §4.1 runs)
$ df -h /c   -> 944G 452G 492G 48%
```

Re-checked at 23:12 (while the planner/architect reviews ran): Vite `200`, `/rest/setup/state` `200`,
`am7test-am7-1 Up 29 minutes`, `postgres3`/LiteLLM/local Ollama up.

**Correction (found 23:20):** the `https://127.0.0.1:9443/rest/setup/state → 200` lines above and in
the 23:12 re-check were answered by nginx's `location /` SPA fallback (`200 text/html`, the Ux752
`index.html`), not by Tomcat — on `:9443` the REST base is `/AccountManagerService7/rest/…`. The stack
*was* up (the P0-10c/P0-10d Playwright runs in the same window prove it), but those two lines did not
prove it. The probe below uses the right path; future sessions should too.

### Re-probe 2026-10-09 23:20 local after the P0-11 redeploy (`docker restart am7test-am7-1` at 04:19:10 UTC) — GREEN

```
$ docker ps --format '{{.Names}} {{.Status}}'
am7test-am7-1 Up 1 minute
postgres3 Up 32 hours
am7test-litellm-1 Up 32 hours (healthy)
am7test-ollama-1 Up 32 hours (healthy)
$ docker exec am7test-am7-1 sh -c "grep -E 'Server startup|SEVERE' /opt/tomcat/logs/catalina.2026-10-10.log | tail"
... Server startup in [8288] milliseconds          (04:19:10 UTC; the only SEVERE lines are the shutdown ThreadLocal-leak warnings from the stop)
$ curl -sk -w '\n%{http_code} %{content_type}' https://127.0.0.1:9443/AccountManagerService7/rest/setup/state
{"initialized": true}
200 application/json
$ curl -sk -o /dev/null -w '%{http_code} %{content_type}' https://127.0.0.1:9443/AccountManagerService7/rest/schema   -> 200 application/json
$ curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9443/AccountManagerService7/rest/olio/chap-book/x/settings -> 403   (unauthenticated — the route is reachable and gated)
$ curl -sk -o /dev/null -w '%{http_code} %{content_type}' https://127.0.0.1:9443/rest/setup/state -> 200 text/html   (SPA fallback — proves nothing, see correction)
$ docker exec am7test-am7-1 sh -c 'curl -sk -o /dev/null -w "%{http_code} %{content_type}" https://127.0.0.1:8443/AccountManagerService7/rest/setup/state' -> 200 application/json
$ curl -sk -o /dev/null -w '%{http_code}' http://127.0.0.1:8899/ -> 200   (Vite, still up from 22:49)
$ docker exec am7test-am7-1 sh -c 'grep -o "assets/index-[A-Za-z0-9_-]*\.js" /opt/ux752/dist/index.html' -> assets/index-2wwCfzG2.js  (P0-11 bundle; replaced by index-BwGXRF4L.js / chapBook-Druso8m4.js at 04:27 UTC after the P0-10d Low fix, §2.6)
```
LLM `.42` and Swarm `.39` not re-probed in this block — nothing in P0-10d/P0-11's test runs reaches
either (P0-11's `TestFlux2Composite`/`TestKontextDeprecation` are DB-less and SD-less; the Playwright
specs render nothing). The 22:49 probe above is the last full one; the full gate (P0-12) re-probes all
six before it runs.

### Re-probe 2026-10-09 23:56:20 local for P0-12 (Objects7 gate refresh + full gate) — GREEN on all six required probes; two optional hosts down

```
--- 2026-10-09 23:56:20 ---
[1] postgres3 :15432
tcp open
[2] am7test setup state
{
  "initialized" : true
} http=200
[3] vite :8899 / host :8443
vite8899 http=000
host8443 http=000
[4] LLM .42 tags
['valkyriesys/eudaimonia-dryad3-vision:latest', 'qwen3:8b']
[5] Swarm .39
session=a0c092ed…
backends= 1 features has ipadapter/sam2/yolov8: [True, True, True]
[6] in-container probes
llm42=200 swarm39=302
$ docker ps --format '{{.Names}} {{.Status}}' | grep -E 'postgres3|am7test'
am7test-am7-1 Up 36 minutes
postgres3 Up 32 hours
am7test-litellm-1 Up 32 hours (healthy)
am7test-ollama-1 Up 32 hours (healthy)
(+ the six langfuse containers, all Up/healthy)
$ df -h /c | tail -1
C:              944G  453G  492G  48% /c
$ md5sum dist/assets/index-BEZJ8GMT.js (host) = 0699c160eacac61fa8fe9e4d2f139e43 ; same file in am7test-am7-1:/opt/ux752/dist = 0699c160eacac61fa8fe9e4d2f139e43
```
Reading: the Objects7 gate copy (`C:/tmp/o7verify/AccountManagerObjects7`) `diff -rq`'s clean against
the working tree (`src/` and `pom.xml`), and the container serves the current Ux752 build (index.html
and the entry chunk are byte-identical; the container's `assets/` also still holds the older hashed
chunks from previous `docker cp`s, which is harmless).

**Vite `:8899` is down (it was up at 23:20; the shell that started it has exited) and the host
Eclipse Tomcat `:8443` is down.** Neither is required by §2 of the brief for the Docker-stack path:
`playwright.config.js:14-15,36-40` omits the `webServer` block entirely when `PLAYWRIGHT_BASE_URL` is
set, and the brief's §5 prescribes exactly `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443` for Playwright.
So the P0-12 full gate's e2e phase is run with that variable exported (the loop's `E2E_CMD` is a bare
`npx playwright test`, which inherits it) against the Docker stack, not through a Vite proxy to an
ad hoc Tomcat — ✓ FOLLOWING INSTRUCTIONS (brief §2 "not an ad hoc Tomcat", §5 base URL). What that
run can and cannot prove is recorded under §4.1 with its result.

### Re-probe 2026-10-10 01:42:09 local before the P0-12 log-backed re-runs (§2.1, §2.6, §2.7) and the full gate — GREEN on all six required probes

```
--- 2026-10-10 01:42:09 ---
[1] postgres3 :15432 tcp open
[2] am7test setup state http=200
[4] LLM .42 ['valkyriesys/eudaimonia-dryad3-vision:latest', 'qwen3:8b']
[5] Swarm .39 backends= 1 ipadapter/sam2/yolov8: [True, True, True]
[6] in-container llm42=200 swarm39=302
am7test-am7-1 Up 2 hours
postgres3 Up 34 hours
am7test-litellm-1 Up 34 hours (healthy)
am7test-ollama-1 Up 34 hours (healthy)
$ df -h /c | tail -1   (01:50, before the full gate)
C:              944G  454G  490G  49% /c
```
Vite `:8899` / host `:8443` not re-probed — still not required (previous block). Container `dist` unchanged
since the 23:56 md5 match (no frontend edit since). No Eclipse/`javaw` process is running (`tasklist`), so
the full gate's Objects7 `mvn test` can run in the working tree without the stub-class interference that
motivated the `C:/tmp/o7verify` copy. Modules `detect.sh` will select from uncommitted changes (`git diff
--name-only HEAD` + untracked): `AccountManagerAgent7` (untracked `logs/`, `plan.json` — Stephen's, present
at session start), `AccountManagerConsole7` (`tmp.bat`, `vectorEnv.bat` — Stephen's, present at session
start), `AccountManagerObjects7`, `AccountManagerService7`, `AccountManagerUx752`. ✓ FOLLOWING
INSTRUCTIONS: the brief's §5 says full `bash .claude/loop/verify.sh` with no module scope, so all five run
and Agent7/Console7 results are reported as what they are (not Phase 0 modules).

### Re-probe 2026-10-10 04:34:46 local after the full gate (`VERIFY_FAILED`, §4.1) and before the post-gate serial Playwright re-run — GREEN on all six required probes; **the stack's shared-user fixture is damaged (§6.I)**

```
--- 2026-10-10 04:34:46 ---
[1] postgres3 :15432 tcp open
[2] am7test setup state http=200        am7-pg Up 37 hours (healthy)   am7test-am7-1 Up 5 hours
[3] Vite https://127.0.0.1:8899/ http=200   (plain http://127.0.0.1:8899/ -> 000: the dev server is HTTPS-only; not a failed probe)
    container dist: index.html identical to the local build, missing=0   (needs `MSYS_NO_PATHCONV=1`; without it Git Bash rewrites
    /opt/ux752/dist/... to C:/Program Files/Git/opt/... and every asset reports MISSING)
[4] LLM .42 14 models incl. 'qwen3:8b', 'valkyriesys/eudaimonia-dryad3-vision:latest'
[5] Swarm .39 session OK; ListBackends ipadapter/sam2/yolov8: [True, True, True]
[6] in-container llm42=200 swarm39=302
$ df -h /c | tail -1
C:              944G  465G  480G  50% /c
```
Every service is up, but `am7-pg` had crashed and recovered **twice** during the gate's e2e phase (second
recovery complete 09:30:47Z = 04:30:47 local, four minutes before this probe — §6.J) and the shared test
user had been deleted and recreated by another spec mid-gate (§6.I). The services are green; the **test
data** is not — see §4.1 "Post-gate serial re-run" for the consequence.

### Probes 2026-10-10 12:25:47 and 12:37:38 local — after the §6.I/§6.J/§6.K fixes, before the live `generateScene` run 4 and before the second full gate — GREEN

Probe script `C:\tmp\am7-services-probe.sh` (bash + `python -I`; appends to `C:\tmp\am7-services-probe.log`),
now also checking the second Vite on `:8898` and its proxy to the stack, the in-stack Ollama container, LiteLLM
from inside the app container, and the container-vs-local `dist` hash. Verbatim, the 12:37:38 run (the 12:25:47 run
is identical except Swarm `.39` answered `302` and `am7-pg` showed `Up About an hour`):

```
--- 2026-10-10 12:37:38 ---
[1] postgres3 :15432 tcp open
[2] am7test setup state http=200   am7test-am7-1 Up About an hour;am7-pg Up 2 hours (healthy)
[3] Vite 8899 http=200  Vite 8898 http=200  8898-proxy setup/state=200
[4] LLM .42: 14 models; qwen3:8b True ; dryad3-vision True
[4b] Ollama container :11435: ['goekdenizguelmez/JOSIEFIED-Qwen3:8b', 'nomic-embed-text:latest', 'qwen3:8b']
[5] Swarm .39: http=200
[6] in-container llm42=200 swarm39=302 litellm=200
    container dist index.html identical to local build
C:              944G  480G  465G  51% /c
```
`am7-pg Up 2 hours` is the **new** container on the named volume `am7test_am7-test-pg` (§6.J); `(healthy)` has held
since. Swarm `.39` alternating `200`/`302` on `/` is its session redirect, not a failure (the `[6]` in-container probe
shows the same `302`).

---

## 4. Verification per phase

| Phase | `verify.sh` | verifier | architect | security-reviewer |
|---|---|---|---|---|
| 0 | **Second full gate running since 2026-10-10 12:38:10 local** (`C:\tmp\full-gate-p012-run2.log`, §4.2 — result pasted there when it lands). **First full gate 2026-10-10 01:50–04:23 `VERIFY_FAILED`** (`C:\tmp\full-gate-p012.log`, §4.1): Agent7 PASS/PASS; Console7 PASS/PASS; Objects7 `Tests run: 902, Failures: 5, Errors: 0, Skipped: 15` — all five are §6.A (3) / §6.B / §6.F, every Phase 0 class green; Service7 `Tests run: 108, Failures: 0, Errors: 1, Skipped: 0` — the §6.H keystore fixture, every other class green incl. `TestChapBookRestContract` 8/8; Ux752 build/vitest/lint PASS, **e2e FAIL** `281 passed (45.1m) / 4 flaky / 103 skipped / 222 did not run`, 596 failed — classified from traces: 364 Firefox-not-installed, 41 Vite-source dynamic imports under `:9443`, 39 ChapBook/poem seeding (shared user deleted mid-gate by `bugfixSprint2.spec.js`, §6.I — includes all 12 Phase 0 ChapBook specs), the rest overlapping two `am7-pg` PANICs (§6.J) and left unclassified. Earlier module runs: Objects7 run 3 01:40 `902/6/0/15`; Service7 22:34 `108/0/1`; Ux752 quick `VERIFY_OK` 23:49. **Phase exit blocked**: §6.A/B/F/H (pre-existing, Stephen's) + §6.I (fixture repair) before any ChapBook Playwright can go green again | **Slice A/B 2026-10-09: CHANGES-NEEDED** (code sound, claims honest). Findings and status: (1) module gate RED — `Tests run: 879, Failures: 10, Errors: 1` → `VERIFY_FAILED`; 3 failures are §6.A (PBAC series read), 1 is §6.B (expired fixture), 2 were `TestPictureBookWorkflow` — re-run alone (§4.1): `TestStepBudgetComparison` passes, `TestFreshCharacterSubRecordsAndPortraitRender` fails deterministically → §6.F (test/product inconsistency since 2026-09-20, waiting on Stephen), the rest fixed — **open until the gate is green**; (2) `testNoGraphClassicPathSurvivesLandscapeFailure` could not reach the `land-*.png` null guard — **fixed**, replaced by `testNoGraphClassicLandscapeDumpGuardWithPortrait` (§2.2, green in run 3); (3) `generateSceneImage` public overload performed the SD-server null check after delegating — **fixed** (check first, §5.3 item 3); (4) LOW, two undocumented behaviour changes — **documented** (§5.3 items 10–11); (5) P0-1 never asserted steps=7 reached the SD request — **fixed** (`capturingThrowingSdu`, §2.2). Coverage gaps the verifier noted and that remain untested: `SDUtil` update branches of P0-7, the FLUX.2 `landscapeFailed` path (only the classic path is exercised), the `generateSceneImage` null-field read site, and the `TestSDUtilCreateImage:321-325` early return. **P0-11 2026-10-09: PASS-WITH-NOTES** — re-ran `TestKontextDeprecation,TestFlux2Composite` (`Tests run: 43, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`, 6 WARN lines observed on the console so the appender attachment did not break root additivity), Service7 `compile` exit 0 + `javap` (`iconst_0` before `SceneCompositeUtil.resolveMode:(…;Z)`, zero `Unresolved compilation`, single call site), Vitest `sdConfigPanelLandscapeToggle` `9 passed (9)`; confirmed `resolveModeValue` is byte-identical to HEAD's `resolveMode` body, every production caller passes `false`, the six `configModel.json` hunks are description-only, the `modelDef.js` mirror matches `configModel.json:366`, no non-doc `kontext-test-output` literal remains outside `target/`, `TestKontextDeprecation` is a real test. Notes, none blocking: (1) `kontext-request.json` still tracked and modified (§6.C item 15 — Stephen's `git rm`); (2) the `configModel.json` diff as a whole is not description-only because P0-5's `flux2IncludeLandscapeRef` hunk is co-resident — within the six P0-11 fields it is; (3) `ChatService.class` predated the verifier's own compile (Maven up-to-date), the bytecode inspected is the one deployed; (4) no Service7-level test of the flip exists (§6.C item 14). **P0-10d 2026-10-09 23:35: PASS-WITH-NOTES** — ran against the 04:27 UTC bundle (served `chapBook-Druso8m4.js` md5 = fresh build): Playwright `7 passed (1.1m)`, regressions `2 skipped` / `4 passed (41.3s)`, Vitest 11/11 and `81 files / 1005 tests`, quick gate `VERIFY_OK`. One **Medium**: a failed `GET /settings` was treated as "no row" → seed → whole-seed PUT over a real stored row on the next Save/Render; one **Low**: `null` is both the create-view key and the idle sentinel, so a create-view build settling after a book build overwrote the book instance. **Both fixed** (§2.6 "P0-10d verifier verdict", §6.C item 19): failed GET never seeds (error + Retry, Save disabled, render on the server row), build token replaces the key sentinel; +2 Vitest (13/13, suite 1007) and +1 Playwright (`page.route` 503 on the first GET; `8 passed`, 12/14 with regressions, 2 LLM-gated skips), quick gate `VERIFY_OK`. Coverage gaps the verifier restated and that remain: in-SPA A→B re-key, `cb-dialog-save-book-sd`, `doCreateChapBook`'s PUT. **PHASE-LEVEL pass 2026-10-10 00:15 (whole diff, read-only + Vitest/`vite build`; Maven/Playwright deliberately not run to stay clear of the gate): PASS-WITH-NOTES for the code/test work; phase EXIT still blocked by the RED module gates (§6.A/B/F, §6.H) and the P0-12 runs.** Confirmed: every P0-1…P0-11 change exists in the diff and matches its §1/§2 row; the named tests are real (live DB, real `SDUtil` subclasses or Swarm, real assertions), no admin user (admin only seeds `chapbookTestUser`/`pbGuardUser`), layering intact (`/settings` pure transport, `@RolesAllowed({"admin","user"})` on GET+PUT, every write via `AccessPoint` as the request user, no ISO in Objects7); `grep` shows no raw `FIELD_BYTE_STORE` write left in `picturebook/` or `sd/`; only two `kontext-test-output` literals remain under `src/` and both are `./target/...`; all 26 cited `C:\tmp\*.log` files exist and every quote checked was verbatim **except two** (next). **Medium (2):** (1) §2.7's combined `TestKontextDeprecation,TestFlux2Composite` run (`Tests run: 43`) is in no `C:\tmp` log — the tests are sound, the quote is unsubstantiated → **re-run with a log under P0-12** (§4.1); (2) no test covers the `ChatService.java:1492` `resolveMode(sdConfig, false)` flip — a future edit restoring `true` re-activates Kontext for chat image requests with no red test (§6.C item 14, now carrying this verdict). **Low (5):** `TestSDUtilCreateImage.createImageSparseConfigDoesNotThrow` early-returned on an empty Swarm result → **fixed** (`assertFalse(out.isEmpty())`, re-run under P0-12); stale javadoc test name in `SceneCompositeUtil.includesLandscapeReference` → **fixed**; §2.3 "explicit projection" wording for `readSdConfigById` → **corrected** (`planMost(false)` + `setCache(false)`); `OlioService.java:218,644` `imp.get("hires")` unboxing NPE risk on a sparse config, pre-existing and outside P0-2 → §6.E item 11; coverage gaps already recorded (FLUX.2 `landscapeFailed` live, `SDUtil` update-hit branches, in-SPA A→B re-key, `cb-dialog-save-book-sd`, `doCreateChapBook` PUT) confirmed real. **Unsubstantiated quotes:** the §2.6 Playwright UI lines (`ok 8 …`, `Running 14 tests … 12 passed (1.8m)`) are in no log — only `e2e/results/.last-run.json` (`status: passed`, 23:49) corroborates → **re-run with a log under P0-12**; the REST-only `3 passed` runs (`pw-cbsettings-run2/3.log`) are verbatim. **Weak/fake tests:** none fake; `TestChapBookRestContract` is a reflective annotation sweep + the real private `parseParams` and says so; `chapBookBookSettings.test.js` stubs `fetch` at unit level, acceptable because the Playwright spec drives the same paths live; the `TestOllamaModelUnload` delta-baseline change is a fix for LiteLLM-routed false failures, not a weakening. Stephen's call (not blockers): `git rm --cached kontext-request.json` (§6.C item 15), §6.D, §6.E 5–11, §6.G 8–12, the deprecated-Kontext removal timeline. | plan review 2026-10-09: 7 APPROVED / 4 CHANGES-NEEDED, resolved (§6.C); **code review Slices A/B 2026-10-09: PASS** — P0-1…P0-8 all APPROVED, no layering/prohibition/pattern/test-weakening findings; 1 CHANGES-NEEDED on this doc (§5.3 item 2 wrongly called `olio.pictureBookResult` column-backed — corrected), 2 suggestions applied (P0-1 shallow-copy note at `PictureBookUtil.java:9500`; `OlioService.java:691` now sets `contentType` before `ByteModelUtil.setValue`); 4 flags for Stephen recorded in §6.E; **plan review Slices C/D 2026-10-09: PASS with CHANGES-NEEDED on P0-10a and P0-11** — P0-9/P0-10b/P0-10c/P0-10d APPROVED; P0-10a blocking: the create branch must adopt an existing `book-sdConfig` row in the Book group (`SdConfigUtil.findConfig`) before creating, with a test for the pre-existing-unlinked-row case (PB1's `persistBookSdConfigFk` FK patch is best-effort so a dangling row is reachable); P0-11: the `ChatService.java:1490` flip has no Service7 test and must be reported as "compile + Objects7 `resolveMode` test only", and `TestKontextDeprecation`'s description-prefix assertions are a doc check, not behavioural coverage. Suggestions: GET settings via `AccessPoint.find` rather than the search-layer `ensureFullSdConfig` (adopted); PUT test to send a client config carrying a foreign `objectId`/`groupId` and assert the stored row is still the book's (adopted); record that the 4-arg `resolveEffectiveConfig` overlay must never be called from `PbNodeExecutor` (javadoc + §6.C); render-persists-settings is a UX-semantics decision to attribute (§6.C). All resolutions in §6.C. **Code review Slices C/D 2026-10-09 23:40: PASS** for P0-9 / P0-10a / P0-10b / P0-10c / P0-10d / P0-11, no blocking findings. Confirmed: `ChapBookService` `/settings` is pure transport (principal, 401/400 guards, delegate, `toFullString`, `@RolesAllowed`); `PbBookUtil.getBookSdConfig` never writes and the adopt/create branches run as the request user (the e2e "opening B writes no row" test pins it); `mergeTiers :374` overlay strip deferral (§6.G item 6) acceptable — overlay is transient, never hashed, `description` overwritten by `applyConfigStyle`, so only `imagePath/imageName/tags/controls/attributes` could reach one render (note: the one-liner needs `BOOK_SD_CONFIG_SKIP` non-private or moved to `PbConfigUtil`); nothing pre-empts Phase 2 — `SUPPORTED_MODES`/`isSupportedMode` untouched, `legacyKontextDefault` survives for Remove, overlay tier sits below node `configOverride` and outside hashing, `PbNodeExecutor` callers all 3-arg; no fake tests, no admin user. Non-blocking suggestions → §6.E items 5–9. On `kontext-request.json`: leaving it was right for an agent, but §37.3 Deprecate is not complete until Stephen runs `git rm --cached` (or deletes) — §6.C item 15 now says exactly that. Rules gap: `architecture.md` says nothing about JUnit `@Test(timeout)` on render/LLM tests (guidance lives only in memory) — §6.E item 10. | **P0-10c 2026-10-09: PASS, no blocking findings, three Low hardening items — all three applied and proven in the task runs (§2.5):** (3) inherited `tags`/`controls`/`attributes` reachable through the sparse `sdConfig` → `PbBookUtil.BOOK_SD_CONFIG_SKIP`, leak demonstrated red then green in `TestChapBookBookSettings`; (4) unescaped `ChapBookService.errorResponse` → Jackson-escaped, `TestErrorBodyIsValidJsonForHostileMessages` (caught the tab gap the mirrored replace chain missed); (5) per-request `ObjectMapper` in `ensureSchema` → `static final`; double parse kept. No finding on `@RolesAllowed`, PBAC-as-user, cross-user 404, FK-to-unreadable → `{}`, `referenceImageId` resolution, or the two deserializer modules. Not in scope, noted in §6.G: `PictureBookService.ensureSchema` / `escapeJson` twins, pre-existing per-request mappers. **Slices A/B (P0-1…P0-8) 2026-10-09 23:41: PASS-WITH-NOTES, no blocking findings** (read-only review). Confirmed: every new write goes through `AccessPoint` as the request user (`PbPipelineUtil.failNode`, `PictureBookUtil.persistBookSdConfig:2007`, all 12 `SDUtil` image-record writes), no new olio-principal or org-admin writes, `openSceneGraph` find-only, `authorizeSceneAccess(user, sceneObjectId, WRITE)` still first in both `generateSceneImage` overloads; `OlioService.generateArt:611-616` / `reimage:173-178` keep `@RolesAllowed({"user"})` + principal, helper `:658-693` sets `contentType` → `ByteModelUtil.setValue` → `AccessPoint.create`; P0-3 **narrows** the client-controlled `promptTemplate` surface (scene-image prompt now `null`; landscape `:3859/:9611/:10695` and extract-scenes `:8665` still accept any name — resolution is PBAC-scoped DB template then classpath `getResourceAsStream`, no traversal); `resources/olio/llm/` untouched, `TRAINING BIAS OVERCORRECTION` only in `prompts/compliance.json`, unchanged; persisted error strings (`olio.pb.node.lastError`, scene `error`) are raw `e.getMessage()` readable only by the record's PBAC audience, `landscapeError` response-only; no secrets (only literal: `TestFlux2Composite.java:685` `.42` fallback, Stephen's 2026-08-07 commit, outside the diff); no admin authentication in tests, no `-Dreset`/`TestAuthorization`. Hardening, severity order, **none applied** (scope discipline; all → §6.G items 8–12 for Stephen): (1) `PictureBookUtil.java:10357-10362` production `./comp-<id>.png` / `./land-<id>.png` dump to JVM cwd whenever `openSceneGraph` fails — pre-existing, P0-6 only added the null guard; (2) raw exception messages in `lastError`/`error` can carry internal host:port, `nodeModel.json:101 lastError` has no `maxLength`; (3) `SDUtil.newDataByNameQuery` lookups (6 sites) still raw `getSearch().findRecord` while now projecting `keyId`/`vaultId`/`byteStore` (pre-existing bypass, the subsequent update is PBAC'd); (4) landscape/extract-scenes `promptTemplate` override has no allowlist; (5) the `.42` literal. **P0-10d 2026-10-09: PASS-WITH-NOTES** — checked against the P0-10c contract: (a) sparse body — `computeBookSdDelta` (`chapBook.js:919-934`) strips `SD_CONFIG_IDENTITY` + `BOOK_SD_CONFIG_SKIP` + `schema` in both branches and the GET row is stripped the same way before it becomes the instance, matching `PbBookUtil.BOOK_SD_CONFIG_SKIP` + `SDUtil.SD_OVERRIDE_SKIP`; (b) toast text is a Mithril text node (`pageClient.js:162`), no `m.trust`/innerHTML, server text inert; (c) `bookObjectId` from the hash only reaches `encodeURIComponent` in the REST path, the memo key and a localStorage key, and the server route regex rejects anything else with 404; (d) no path can PUT A's instance to B — memo check, in-flight key check, publish-only-if-current, `saveBookSdConfig(key)` PUTs to its own key, create path gated on `renderSdConfigBookId === null`; (e) read path issues only the GET (Playwright test 3 asserts no row); pre-existing and unchanged: `seedDefaultSdEntity` → `am7sd.loadConfig` → `page.makePath('auth.group','DATA','~/Data/.preferences')` creates the user's own preference group as the user. Two Low hardening items: (1) `doRegenerateScene`/`doRerenderScene` read `reviewBookObjectId` **after** an `await`, so navigating A→B mid-call synced/PUT B's own config while A's stale scene rendered with it — **applied** (both capture the id before their awaits, §2.6 addendum); (2) `page.logout` (`pageClient.js:855`) routes to `/sig` without a reload, so `renderSdConfigInst` survives a same-tab user switch and the next user opening the same objectId reuses the memo instead of a fresh PBAC'd GET — pre-existing pattern for **all** `chapBook.js` module state, not P0-10d code → §6.G item 7. Ran the Vitest itself (11/11); did not run the Playwright. **P0-11 2026-10-09: PASS** — WARN is a string constant carrying no record data; the pre-existing `:106` WARN that echoes user-supplied `mode` is untouched; the `ChatService` flip is pipeline selection only (no principal / `@RolesAllowed` / `AccessPoint` change) and Kontext was already client-selectable so no new reachable path; six fields description-only; `TestKontextDeprecation` is real (ran it: 7/0/0, 6 WARN lines); `kontext-request.json` content is a benign SD request, no secrets. |

### 4.1 Phase 0 module-gate runs (2026-10-09)

Command: `LOOP_ROOT=C:/tmp/o7verify LOOP_MODULE=AccountManagerObjects7 MAVEN_ARGS=-o bash .claude/loop/verify.sh --quick`
— an rsync'd copy of the module under `C:/tmp/o7verify` so the gate's full `mvn test` does not fight
the working tree's `target/`. Both runs hit the live `am7db` on `postgres3` and the live LLM/SD tiers.

**Run 1 (17:29, `C:\tmp\o7verify-gate-sliceA-run1-dbpanic.log`) — `VERIFY_FAILED`, `Tests run: 879,
Failures: 545, Errors: 1, Skipped: 3`.** Not a code result: PostgreSQL crashed mid-suite and every
test after it failed on a dead connection. `docker logs postgres3` (UTC):
```
22:25:24.652 [913] LOG:  checkpoint starting: time
   (previous checkpoint: wrote 59870 buffers (11.4%) … write=269.346 s … total=270.062 s)
22:28:19.487 [6118] PANIC:  could not write to file "pg_wal/xlogtemp.6118": Interrupted system call
22:28:19.487 [6118] STATEMENT:  COMMIT
22:28:29.308 [1] LOG:  client backend (PID 6118) was terminated by signal 6: Aborted
22:28:29.308 [1] LOG:  terminating any other active server processes
22:28:43.577 [6131] LOG:  database system was not properly shut down; automatic recovery in progress
22:30:32.865 [1] LOG:  database system is ready to accept connections
```
The container stayed up and recovered on its own; `am7db` re-verified intact afterwards (170
`a7_system_modelschema_0_1` rows). The 270 s checkpoint immediately before the PANIC says the host
disk (`docker_data.vhdx` on `C:`) was under heavy write pressure at the time — `TestOlioLoadDataFreshOrg`
was running (it seeds a whole org and grows `A7_data_cenWord_0_1`). **Waiting on Stephen (§6.D):**
whether that test belongs in the default gate, and whether the vhdx / `C:` pressure needs looking at.

**Run 2 (19:48, `C:\tmp\o7verify-gate-sliceA-run2.log`) — `VERIFY_FAILED`, `Tests run: 879,
Failures: 10, Errors: 1, Skipped: 15`.** Verbatim failure list and classification:
```
TestMannequinBaseImage.classpathCopiesAreByteIdenticalToUx752:71   -> isolated-copy artefact (../AccountManagerUx752/public/media/ is not under C:/tmp/o7verify); real tree: Tests run: 10, Failures: 0 (0.632 s)
TestOllamaModelUnload.nativeUnloadIsCountedOnlyOn200AndPostsKeepAliveZero:117 expected:<0> but was:<2>   -> absolute skippedProxied counts; FIXED (delta), §2.2
TestOllamaModelUnload.nonOllamaUpstreamRecordsNothing:193 expected:<0> but was:<1>                        -> same; FIXED
TestOllamaModelUnload.proxiedEntriesAreSkippedNotPostedToTheProxy:160 expected:<1> but was:<2>            -> same; FIXED
TestPbGraph.case01_fullPlanOverTheWorkflowRunCycleTerminates:222 … expected null, but was:<{olio.pb.workflow …}>  -> stale characterization; FIXED (assertNotNull), §2.2
TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender:710 At least one PORTRAIT node must have been asserted   -> FAILS ALONE too (solo run below) → §6.F, deterministic, not Slice A/B
TestPictureBookWorkflow.TestStepBudgetComparison:752 » PictureBook java.net.SocketTimeoutException: Read timed out        -> PASSES ALONE (solo run below); the gate failure was the concurrent Slice B GPU work
TestUpstreamResolution.testLiveNullColumnRowsResolveFromDialect:247 No system.connection row in am7db has upstream IS NULL   -> §6.B
TestPbDeleteSeries.testStrangerDeniedThenOwnerDeletesWholeSeries:119 chapter 1 row                        -> §6.A
TestPbListSeriesBooks.testEntitledNonOwnerSeesAllChaptersStrangerSeesNone:138 expected:<2> but was:<0>    -> §6.A
TestPbSeriesCast.testRecopyAndMergeDenyReadOnlyUserAndDoNotMutate:532 subject MUST be able to READ …     -> §6.A
```
`TestPbSceneGenerationGuards` was not yet in the copy for run 2 (its no-graph NPE was found and fixed
in the working tree, §2.2). Next gate re-run: after Slices C/D, from a refreshed copy.

**Run 3 — Objects7 gate refresh for P0-12 (started 2026-10-09 23:52 local from a copy re-synced at 23:56
and `diff -rq`-clean against the working tree; finished 2026-10-10 01:40; `C:\tmp\o7-verify-quick-p012.log`)
— `VERIFY_FAILED`, `Tests run: 902, Failures: 6, Errors: 0, Skipped: 15`.** Verbatim:
```
==> [AccountManagerObjects7] build: mvn -q -B -DskipTests test-compile
    PASS (build)
==> [AccountManagerObjects7] test: mvn -q -B -DskipTests=false test
    FAIL (test) in AccountManagerObjects7
[ERROR]   TestMannequinBaseImage.classpathCopiesAreByteIdenticalToUx752:71->ux752Copy:39 Ux752's copy of maleModelx512.png must exist at ../AccountManagerUx752/public/media/ — if it moved, Objects7's copy under olio/media/ has to move with it
[ERROR]   TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender:710 At least one PORTRAIT node must have been asserted, or this case proved nothing about the render branch
[ERROR]   TestUpstreamResolution.testLiveNullColumnRowsResolveFromDialect:247 No system.connection row in am7db has upstream IS NULL. The added column is nullable with no DDL default, so this test's subject - the primary path every pre-existing row takes - is not present in this database. Do not weaken this test; it means the fixture assumption changed.
[ERROR]   TestPbDeleteSeries.testStrangerDeniedThenOwnerDeletesWholeSeries:119 chapter 1 row
[ERROR]   TestPbListSeriesBooks.testEntitledNonOwnerSeesAllChaptersStrangerSeesNone:138 entitled non-owner must see BOTH chapters expected:<2> but was:<0>
[ERROR]   TestPbSeriesCast.testRecopyAndMergeDenyReadOnlyUserAndDoNotMutate:532 subject MUST be able to READ the chapter book (else recopy 404s before the canUpdate gate)
[ERROR] Tests run: 902, Failures: 6, Errors: 0, Skipped: 15
VERIFY_FAILED
```
Every one of the six is already recorded and none is Phase 0 code: `TestMannequinBaseImage` → the
isolated-copy artefact (run 2, same cause; real tree 10/0); `TestPictureBookWorkflow …PortraitRender` → §6.F;
`TestUpstreamResolution` → §6.B; the three PB-series tests → §6.A. Run 2's other five failures and its one
error are gone (`TestOllamaModelUnload` ×3, `TestPbGraph` case01, `TestStepBudgetComparison`): 879/10/1 → 902/6/0.
Phase 0 classes in this run (surefire reports, `C:\tmp\o7verify\AccountManagerObjects7\target\surefire-reports\`):

| Class | run / fail / err | time |
|---|---|---|
| `TestSDUtilCreateImage` (P0-2/P0-7; **before** the `assertFalse` edit of §2.1 — post-edit re-run below) | 6 / 0 / 0 | 51.08 s |
| `TestPbGraph` (P0-1/P0-3/P0-4) | 18 / 0 / 0 | 26.55 s |
| `TestPbSceneGenerationGuards` (P0-6/P0-8) | 6 / 0 / 0 | 127.1 s |
| `TestChapBookBookSettings` (P0-9/P0-10a/P0-10b) | 4 / 0 / 0 | 37.63 s |
| `TestChapBookRenderStyle` (P0-10b) | 4 / 0 / 0 | 56.34 s |
| `TestKontextDeprecation` (P0-11) | 7 / 0 / 0 | 0.006 s |
| `TestFlux2Composite` (P0-5/P0-11) | 36 / 0 / 0 | 8.014 s |
| `TestPbConfigPrecedence` (P0-5/P0-10b) | 2 / 0 / 0 | 1.690 s |
| `TestOllamaModelUnload` (Slice B delta fix) | 6 / 0 / 0 | 0.036 s |
| `TestPictureBookWorkflow` | 5 / 1 / 0 (§6.F) | 2198 s |

`TestPictureBookFull`, `TestKontext` and `TestPictureBookUtilE2E` have **no surefire report** because the
Objects7 POM excludes them from surefire (`pom.xml:140`, `:154`, `:168`) — they never run in any gate, so the
P0-11 `kontext-test-output/` redirect in `TestPictureBookFull` is verified by compile + grep only (§2.7), not by
a gate run. Not a Phase 0 change; recorded so nobody reads "no report" as "not compiled".

**Solo run of `TestPictureBookWorkflow` (2026-10-09, `C:\tmp\o7-pbworkflow-alone-run1.log`, no concurrent
GPU work, working tree, `cd src && mvn -o -pl AccountManagerObjects7 -Dtest=TestPictureBookWorkflow
-DskipTests=false test`):**
```
[LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2228 s <<< FAILURE! -- in org.cote.accountmanager.objects.tests.TestPictureBookWorkflow
[ERROR] TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender -- Time elapsed: 104.9 s <<< FAILURE!
java.lang.AssertionError: At least one PORTRAIT node must have been asserted, or this case proved nothing about the render branch
	at org.cote.accountmanager.objects.tests.TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender(TestPictureBookWorkflow.java:710)
[INFO] BUILD FAILURE
[INFO] Total time:  37:12 min
```
- `TestStepBudgetComparison` **passed alone** — `KI-68 steps=4: generated in 161133ms`, `steps=20: 533088ms`,
  `steps=40: 1003372ms` (recorded, never asserted). Its run-2 `SocketTimeoutException` was the concurrent
  Slice B GPU work, as suspected.
- `TestFreshCharacterSubRecordsAndPortraitRender` **fails alone, deterministically** — `Stage 1 complete: 0
  portraits generated`, both scene characters unresolved (`WARN Could not resolve scene character
  'Jideon'/'Touvier' to a charPerson record — this character's portrait will be skipped`, log :1728/:1731/:1840/:1843).
  Root cause is a test/product inconsistency dating from 2026-09-20, independent of Slice A/B: **§6.F**.
- Every LLM call in this test 404'd (`Chat config: model=gpt-oss:120b … serverUrl=http://127.0.0.1:11435` →
  `LLM call failed (HTTP 404): model 'gpt-oss:120b' not found`) and fell back to the non-LLM paths; also §6.F.

PNGs the solo run exported (`AccountManagerObjects7/export/`), each opened with Read:
- `pb2_fresh_composite_4f31c742-….png` — photographic rainy narrow alley; two men crouched facing each
  other picking small coloured chips/cards off the wet ground; left man grey-brown hair in an olive coat,
  right man dark hair, mouth open, holding a small amber bottle. No fresh-character portrait references
  were used (consistent with 0 portraits) — the faces/outfits are free-generated.
- `ki68_step4_…`, `ki68_step20_…`, `ki68_step40_…` — one composition at 4/20/40 steps: left man in a maroon
  leather jacket with a bloody cheek wound, light grey jeans, pink socks and pink shoes; right young Asian
  man in a shiny lilac trench coat, khaki trousers, black shoes; chips on the wet alley floor. All three
  coherent; step 40 marginally crisper (chips resolve into round tokens).
- `pb2_level1_composite_3b2dd469-….png` — the same two men (maroon jacket + cheek wound, pink shoes;
  lilac trench coat, brown trousers/shoes) crouched in the rainy alley, the right man picking up a
  card/chip, chips scattered between them.
- `pb2_level2_withLandscapeRef_597c0280-….png` — same pair and framing; grey rendered-stone alley walls
  with a street lamp and a bicycle wheel at right; chips on the ground, one white card at the right edge.
- `pb2_level2_withoutLandscapeRef_e023e218-….png` — same pair, different setting: wider alley with a brick
  building and a green tree at the far end, puddle-reflections on the ground. The with-/without-landscape
  pair differ in background exactly where the landscape reference should act, which is what the Level-2
  case is meant to show.

**Service7 quick gate for the Slice C transport slice (2026-10-09 22:32–22:34 local,
`C:\tmp\s7-gate-sliceC-run1.log`) — `VERIFY_FAILED`, `Tests run: 108, Failures: 0, Errors: 1, Skipped: 0`.**
Command (working tree, no copy needed — Service7's tests run on the module-local H2 file plus the ISO
module's `am7isotestdb`, not on a `target/` the Docker deploy depends on):
`LOOP_MODULE=AccountManagerService7 MAVEN_ARGS=-o bash .claude/loop/verify.sh --quick`.
```
==> [AccountManagerService7] build: mvn -q -B -DskipTests test-compile
    PASS (build)
==> [AccountManagerService7] test: mvn -q -B -DskipTests=false test
    FAIL (test) in AccountManagerService7
[ERROR] Errors:
[ERROR]   TestMediaUtilStreaming.TestWriteBinaryDataStreamsLargeStreamBackedFileWithoutFullBuffer:103->createStreamBackedFile:70 » NullPointer Cannot invoke "org.cote.accountmanager.record.BaseRecord.getFields()" because "keySet" is null
[ERROR] Tests run: 108, Failures: 0, Errors: 1, Skipped: 0
VERIFY_FAILED
```
Per class (surefire reports): `TestAccessRequestService` 7, `TestChapBookRestContract` **8**, `TestChatChainStatus` 5,
`TestCredentialService` 4, `TestEmbeddingTypeUrlMismatchWarning` 7, `TestEscapeJson` 9, `TestFeatureConfigService` 10,
`TestISO42001Service` 10, `TestMediaUtilStreaming` 3 (**1 error**), `TestModelServicePatch` 9,
`TestPictureBookRestContract` 14, `TestPrincipalProfileCache` 3, `TestResources` 3, `TestSchemaService` 7,
`TestUserPrincipalIdentity` 3, `TestGameStreamCancel` 6 — every class except `TestMediaUtilStreaming` is green,
including both P0-10c contract classes.

The one error is **not in Slice C and not caused by this session's changes** — it is a broken local test
fixture, and it is deterministic (re-run alone 22:40, `C:\tmp\s7-mediastream-isolated.log`: `Tests run: 3,
Failures: 0, Errors: 1`, same trace). Root cause, from the test's own log:
```
[ERROR] KeyStoreUtil - Key store does not exist at ./am7/.jks/4/keystore.jks
[ERROR] KeyStoreUtil - Key store does not exist at ./am7/.jks/4/truststore.jks
[ERROR] OrganizationContext - Failed to initialize key stores
[ERROR] BaseTest - org.cote.accountmanager.exceptions.SystemException: Organization already exists
```
followed by `VaultService.getPublicKey(:671)` → `new CryptoBean(null)` → the NPE at `CryptoBean.java:88`
inside `StreamUtil.streamToData` (the first vaulted write). The module-local, gitignored H2 fixture
`src/AccountManagerService7/am7/h2.mv.db` holds five organizations — `1 System, 2 Development, 3 Public,
4 /Development/MediaUtilStreamTest, 5 PrincipalCacheTest` (queried with the H2 shell) — but
`src/AccountManagerService7/am7/.jks/` holds keystore directories **`1/ 2/ 3/ 5/` only**; `4/` does not exist
anywhere on this machine (`c:/projects/data/am7` does not exist; `../AccountManagerISO42001/am7/.jks/4/`
belongs to the ISO Postgres org `4 ISO42001`, written 2026-10-08 16:27). Keystore files are written exactly
once, at organization creation (`Factory.configureOrganizationStore`, random password stored as a
`auth.credential` on the ops user's `KeyStoreBean`), and `OrganizationContext.initializeStores` only ever
opens them — nothing recreates them. The other two tests in the class pass because the inline byte[] path
never reaches the vault. Why `4/` is missing is not recoverable from the evidence here; the plausible
history is a single-JVM run before `reuseForks=false` (added 2026-10-08) in which `TestISO42001Service`
left `IOFactory.DEFAULT_FILE_BASE` pointed at `../AccountManagerISO42001/am7` and org 4's keystores
landed there, to be overwritten when the ISO fixture was rebuilt on 2026-10-08 — but that is a guess and
is marked as one. **Decision on repair is Stephen's — §6.H.**

**Ux752 quick gate (Slice D, latest run 23:49, `LOOP_MODULE=AccountManagerUx752 bash .claude/loop/verify.sh --quick`
from the git root, working tree, after the P0-10d verifier Medium+Low fix — §2.6, §6.C item 19;
log `C:\tmp\ux752-verify-quick-sliceD-fix.log`) — `VERIFY_OK`:**
```
==> [AccountManagerUx752] build: npm run --if-present build
    PASS (build)
==> [AccountManagerUx752] test: npx vitest run
    PASS (test)
==> [AccountManagerUx752] lint: npm run --if-present lint
    PASS (lint)
VERIFY_OK
```
(`--quick` skips the module's `npx playwright test` e2e phase; that phase is the full-gate run under P0-12.
The P0-10d/P0-11 Playwright specs were run individually against the Docker stack — §2.6, §2.7.)

**Full gate for P0-12 (2026-10-10 01:50:17–04:23:47 local, 2 h 33 m, working tree, git root,
`bash .claude/loop/verify.sh` with no module scope — `C:\tmp\full-gate-p012.log`) — `VERIFY_FAILED`.**
Modules selected by `detect.sh` from uncommitted changes: Agent7, Console7, Objects7, Service7, Ux752.
Verbatim per-module lines (the log's own `==>` / result lines; Objects7 and Service7 `[ERROR]` summaries
quoted in full):
```
START 2026-10-10 01:50:17
==> [AccountManagerAgent7] build: mvn -q -B -DskipTests test-compile
    PASS (build)
==> [AccountManagerAgent7] test: mvn -q -B -DskipTests=false test
    PASS (test)
==> [AccountManagerConsole7] build: mvn -q -B -DskipTests test-compile
    PASS (build)
==> [AccountManagerConsole7] test: mvn -q -B -DskipTests=false test
    PASS (test)
==> [AccountManagerObjects7] build: mvn -q -B -DskipTests test-compile
    PASS (build)
==> [AccountManagerObjects7] test: mvn -q -B -DskipTests=false test
    FAIL (test) in AccountManagerObjects7
[ERROR]   TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender:710 At least one PORTRAIT node must have been asserted, or this case proved nothing about the render branch
[ERROR]   TestUpstreamResolution.testLiveNullColumnRowsResolveFromDialect:247 No system.connection row in am7db has upstream IS NULL. ...
[ERROR]   TestPbDeleteSeries.testStrangerDeniedThenOwnerDeletesWholeSeries:119 chapter 1 row
[ERROR]   TestPbListSeriesBooks.testEntitledNonOwnerSeesAllChaptersStrangerSeesNone:138 entitled non-owner must see BOTH chapters expected:<2> but was:<0>
[ERROR]   TestPbSeriesCast.testRecopyAndMergeDenyReadOnlyUserAndDoNotMutate:532 subject MUST be able to READ the chapter book (else recopy 404s before the canUpdate gate)
[ERROR] Tests run: 902, Failures: 5, Errors: 0, Skipped: 15
==> [AccountManagerService7] build: mvn -q -B -DskipTests test-compile
    PASS (build)
==> [AccountManagerService7] test: mvn -q -B -DskipTests=false test
    FAIL (test) in AccountManagerService7
[ERROR]   TestMediaUtilStreaming.TestWriteBinaryDataStreamsLargeStreamBackedFileWithoutFullBuffer:103->createStreamBackedFile:70 » NullPointer Cannot invoke "org.cote.accountmanager.record.BaseRecord.getFields()" because "keySet" is null
[ERROR] Tests run: 108, Failures: 0, Errors: 1, Skipped: 0
==> [AccountManagerUx752] build: npm run --if-present build
    PASS (build)
==> [AccountManagerUx752] test: npx vitest run
    PASS (test)
==> [AccountManagerUx752] lint: npm run --if-present lint
    PASS (lint)
==> [AccountManagerUx752] e2e: npx playwright test
    FAIL (e2e) in AccountManagerUx752
  4 flaky
  103 skipped
  222 did not run
  281 passed (45.1m)
VERIFY_FAILED
EXIT=1 END 2026-10-10 04:23:47
```
- **Objects7 `902 / 5 / 0 / 15`** — one better than run 3 (the run-3 "copy artefact" failure is gone because
  this run was in the working tree, not the `C:/tmp/o7verify` copy). The five are exactly the known set:
  §6.F (PortraitRender), §6.B (UpstreamResolution), §6.A ×3 (PbDeleteSeries / PbListSeriesBooks /
  PbSeriesCast). **Every Phase 0 class is green** (same classes as the run-3 table).
- **Service7 `108 / 0 / 1`** — the §6.H keystore fixture, unchanged; `TestChapBookRestContract` 8/8 and
  `TestPictureBookRestContract` 14/14 green.
- **Ux752 build / vitest / lint PASS; e2e FAIL.** The gate's e2e command is the bare `npx playwright test`
  (4 workers, projects chromium **and** firefox, retries 1). `e2e/results/.last-run.json`: `failedTests`
  **596** (mtime 04:23:46 local). The artifacts of this run are preserved untouched in
  `src/AccountManagerUx752/e2e/results/` (1383 dirs); the post-gate re-run below used `--output=e2e/results-postgate`
  so it would not wipe them.

**Classification of the 596 e2e failures from their trace.zip files** (`test.trace` error entries, last
recorded attempt per test; script output saved to `C:\tmp\pw-p012-fullgate-classify.txt`):
```
failed attempts with trace: 1181
by project/retry: {('firefox', False): 419, ('firefox', True): 419, ('chromium', False): 175, ('chromium', True): 168}
tests failed on last recorded attempt per project: Counter({'firefox': 424, 'chromium': 176})
```
- **firefox (424):** `364  Error: browserType.launch: Executable doesn't exist at C:\Users\scote\AppData\Local\ms-playwright\firefox-1509\firefox\...`
  — the Firefox browser is **not installed** on this box, so the firefox project cannot pass at all and contributes
  nothing about the code. The remaining 60 are tests that failed without needing the browser (API-only specs); the
  top classes are the same as chromium's (7 JSON-parse, 4 login failed, 2 `POST /series failed: 403 <!doctype html>...`,
  2 `could not ensure ~/Chat: null`, …) — I did not verify each of the 60 individually.
- **chromium (176):**
  - `41` `page.evaluate: TypeError: Failed to fetch dynamically imported module: https://127.0.0.1:9443/src/core/am7client.js` (29),
    `/src/core/config.js` (5), `/src/core/model.js` (2), `/src/chat/LLMConnect...` (2), `/src/core/pageClient...` (2),
    `/src/cardGame/servic...` (1) — specs that `import()` Vite **source** paths, which only the Vite dev server on
    `:8899` serves; against the nginx-served `dist` on `:9443` (`PLAYWRIGHT_BASE_URL` for the Docker stack) they
    cannot load. Environmental: these specs are written for the Vite base URL.
  - `29` `SyntaxError: Unexpected end of JSON input` + `4` `failed to seed poem` + `2` `poem create failed for c3-/c5-seed-... HTTP 200`
    + `1` `~/Data/Poems group created` + `1` `poem 1 objectId not set` + `1` `poem not seeded` + `1` `poem1ObjectId not set`
    — the ChapBook/poem-seeding family; root cause **§6.I** (shared-user fixture destroyed mid-run; `ModelService.createModel`
    returns HTTP 200 with an empty body when `AccessPoint.create` returns null, so `r.json()` throws). **All 12 Phase 0
    ChapBook specs in the gate are in this class**: `chapBookBookSettings` ×8, `chapBookConfigPanel` ×3, `chapBookSdConfig` ×1.
  - `4` `login failed` + `1` `login failed for e2etest_shared (status 500)` + `1` `login failed (status 500)` + the
    `Test timeout` / `toBeVisible` / `toBeGreaterThan` / `toBeTruthy` / `not.toBeNull` / `toBe` classes (12+11+8+6+6+5+3)
    and the hook timeouts (6) — overlap the two Postgres outages (§6.J: 08:48:21Z–08:57:01Z and 09:20:46Z–09:30:47Z, i.e.
    03:48–03:57 and 04:20–04:30 local, the second one running past the gate's end at 04:23:47) — **not individually
    attributed**; I did not open each of these traces, so they are listed as unclassified rather than claimed.
  - singletons (`ENOENT ... media\faceTes...`, `base() should include the app server host:port`, `all REST calls should
    target the app server on 8443`, `MCP initialize should succeed`, `ISO Tester create should succeed`, `create without
    an ISO role must be denied, got 500`, `book row STILL present in UX list AFTER delete`, …) — unclassified.
- **`chatChainSceneReimageRoutes.spec.js` (P0-11 regression, 8 tests)** left **no** results directory — Playwright
  writes one only for failed/retried attempts, so this is either "passed first attempt" or part of the 222 "did not
  run"; the artifacts cannot distinguish them. Its log-backed evidence remains the 23:11 / 01:44 runs (§2.7, §2.6).

**Root causes behind the e2e phase — all environmental / suite-level, none in Phase 0 code.** Evidence is the
in-container Tomcat file log (`/opt/tomcat/logs/accountManagerService.log`; `docker logs am7test-am7-1` had rotated —
`local` driver — and started at 09:21:18Z) and `docker logs am7-pg`. Timestamps are UTC; local = UTC−5.
1. **The shared test user was deleted and recreated mid-gate by `e2e/bugfixSprint2.spec.js`** (`:12-18`:
   `test.beforeAll(... ensureSharedTestUser(request))` then `test.afterAll(... cleanupTestUser(request, testInfo.user?.objectId, ...))`
   — the only spec in `e2e/` that tears down the user it `ensureSharedTestUser`'d; `cleanupTestUser` (`api.js:255-297`)
   deletes the home group tree and the `system.user`):
   ```
   [INFO ] 2026-10-10 08:40:17.627 [...-exec-7] AuditUtil - AUDIT PERMIT system.user admin to DELETE system.user am6:system.user.normal:development:e2etestshared (via: system.user admin)
   [INFO ] 2026-10-10 08:40:17.943 [...-exec-10] AuditUtil - AUDIT PERMIT system.user admin to ADD system.user e2etest_shared (via: system.user admin)
   [ERROR] 2026-10-10 08:41:25.798 [...-exec-11] ModelService - Failed to create record
   [WARN ] 2026-10-10 08:41:46.597 [...-exec-5] AuditUtil - AUDIT DENY system.user e2etest_shared to ADD olio.cb.poem E2E-CB-Poem-1 (via: system.user e2etest_shared)
   [ERROR] 2026-10-10 08:41:46.597 [...-exec-5] ModelService - Failed to create record
   ```
   The old user (id 19) owned `/home/e2etest_shared/Poems` (group 10697); the recreated user got a fresh id but
   `ensurePoemsGroup` → `GET /path/make/auth.group/data/~/Poems` resolved/created groups the new user does not own,
   so every `POST /rest/model` of `olio.cb.poem` was `AUDIT DENY … ADD` → `Failed to create record` → **HTTP 200 with
   a 0-byte body** (`ModelService.java:259-310`: `ops == null` is returned as `Response.status(200).entity(null)`) →
   `ensurePoem` (`e2e/helpers/chapbook.js:147`) `r.ok()` true, `r.json()` → `SyntaxError: Unexpected end of JSON input`.
   The first Phase 0 spec failure is 68 s after the delete. A second burst — nine `ADD system.user e2etest_shared`
   at 09:08:45–59Z — is concurrent `ensureSharedTestUser` calls racing after the first Postgres recovery.
2. **`am7-pg` PANIC'd twice during the e2e phase**, each on a WAL write, each followed by a full crash recovery:
   ```
   2026-10-10 08:48:21.660 UTC [182248] PANIC:  could not write to file "pg_wal/xlogtemp.182248": Interrupted system call
   2026-10-10 08:48:27.863 UTC [1] LOG:  terminating any other active server processes
   2026-10-10 08:54:52.792 UTC [208440] LOG:  redo done at 2/59FBAB38 system usage: CPU: user: 4.96 s, system: 25.51 s, elapsed: 361.60 s
   2026-10-10 08:57:01.449 UTC [1] LOG:  database system is ready to accept connections
   2026-10-10 09:20:45.157 UTC [209589] ERROR:  deadlock detected
   2026-10-10 09:20:45.157 UTC [209589] DETAIL:  Process 209589 waits for AccessExclusiveLock on relation 1214 of database 0; blocked by process 211373.
   	Process 211373 waits for AccessShareLock on relation 1247 of database 16384; blocked by process 211590.
   	Process 211590 waits for RowExclusiveLock on relation 1214 of database 0; blocked by process 209589.
   	Process 209589: vacuum(full, analyze, verbose)
   2026-10-10 09:20:46.510 UTC [210058] PANIC:  could not write to file "pg_wal/xlogtemp.210058": Interrupted system call
   2026-10-10 09:20:46.510 UTC [210058] STATEMENT:  COMMIT
   2026-10-10 09:20:52.702 UTC [1] LOG:  client backend (PID 210058) was terminated by signal 6: Aborted
   2026-10-10 09:29:03.172 UTC [212373] LOG:  redo done at 3/6BFEE798 system usage: CPU: user: 7.46 s, system: 37.01 s, elapsed: 463.34 s
   2026-10-10 09:30:47.732 UTC [1] LOG:  database system is ready to accept connections
   ```
   Outage 1: 08:48:21–08:57:01Z (~9 min). Outage 2: 09:20:46–09:30:47Z (~10 min), which is **after the gate ended
   (09:23:47Z)** — explaining the `222 did not run` and `am7-pg` showing `unhealthy` at 09:30:02Z. The deadlock
   immediately before PANIC #2 is three concurrent `vacuum(full, analyze, verbose)` — that is `GET /rest/model/cleanup`,
   which `cleanupTestUserFull()` (`api.js:299-311`) calls from several specs; under 4 workers they run in parallel.
   `could not open file "pg_wal/…": No such file or directory` lines recur throughout the container's life (05:59Z 10-09,
   03:07Z, 04:38Z, 04:53Z, 06:53Z, 09:08Z, 09:13Z 10-10) — infra question, §6.J. Tomcat `ServletException: Login failed`
   bursts at 08:38:53, 08:49:21–23, 08:50:37, 08:51:32Z coincide with outage 1.
3. Suite/environment configuration: Firefox not installed (364 failures), specs importing Vite source paths under
   the `:9443` base URL (41), and 4-worker parallelism against one Docker stack (the `cleanup` deadlock, the user-recreate
   race). §6.K.

**Post-gate serial re-run of the Phase 0 ChapBook spec (2026-10-10 04:37:19–04:37:56 local,
`C:\tmp\pw-p012-postgate-bookSettings.log`) — `8 failed` (plus 8 retries), `PW_EXIT=1`.** Command from
`src/AccountManagerUx752`: `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/chapBookBookSettings.spec.js
--workers=1 --project=chromium --output=e2e/results-postgate --reporter=list`. Every one of the 16 attempts fails at
`e2e/helpers/chapbook.js:147` with `SyntaxError: Unexpected end of JSON input`; Tomcat 09:37:24–28Z shows
`PathService - Request to find object from: auth.group data ~/Poems…` followed by `ModelService - Failed to create record` ×4.
**This is the same spec that passed `12 passed / 2 skipped` (with `chapBookConfigPanel` and `chapBookSdConfig`) at 01:44
(`C:\tmp\pw-p012-chapbook-3specs.log`, §2.6) — nothing in the code changed between the two runs; the test-stack fixture
did.** Current DB state (`am7-pg`, read-only psql, org 2): `e2etest_shared` is now **id 134** (was 19 before the gate,
50 after the 08:40Z recreate); `/home/e2etest_shared` is group **11667 owned by admin (6)**; `~/Poems` is group
**11668 owned by the deleted user 50**; groups 10687/10697 are gone; no `E2E-CB-Poem-*` rows remain. So the live shared user
owns neither its home group nor its Poems group and is denied `ADD olio.cb.poem` — **every ChapBook Playwright spec fails
until the fixture is repaired.** Repair options were Stephen's call (§6.I) — he gave it 2026-10-10 ("You have full
control over docker stack") and §4.2 records what was done.

### 4.2 Phase 0 second full gate (2026-10-10, after Stephen's §6.I/§6.J/§6.K instructions)

**Stephen's instruction (2026-10-10, verbatim):** "6.I - You have full control over docker stack, so you may reset
it, and/or create another fresh test user. 6.J - Check docker settings (am7-docker-up.bat/sh for how the mount is
set - lets changre/fix it if needed. 6.K - Then use Chrome. These are your own tests and test conditions from prior
runs, so improvise. Yes I want the generateScene 2e2 done live, and that kontext json is from one of your older
runs - delete it. Once complete, if there are no blocking issues, show me comprehensive prompt to start next phase".

**What was done before this gate** (details in §6.I/§6.J/§6.K "RESOLVED" and §2.8):
1. **§6.J** — `am7-pg`'s data directory moved from the `./docker-data/pg` bind mount (Docker Desktop 9p, the source of
   `PANIC: could not write to file "pg_wal/…": Interrupted system call`) to the named volume `am7test_am7-test-pg`
   (`src/docker-compose.test.yml`; `am7-docker-up.bat:146` / `.sh:206`; docs `dockerDevSetup.md` §7 + storage map,
   `DockerComposeDesign.md`). Stack brought back up; `am7-pg (healthy)` throughout every run since.
2. **§6.I** — shared user repaired: `e2etest_shared` is now **id 228**, objectId `97f13143-f4ee-47aa-aeee-a419dc25ebd3`,
   home group 12055 (admin-owned home groups are normal on this stack). `e2e/bugfixSprint2.spec.js` no longer tears
   down the shared user (the `afterAll` is gone; only its per-test `wf` user is cleaned); `e2e/helpers/api.js` gained a `PERSISTENT_USERS` guard so
   `cleanupTestUser` refuses the shared/ISO users, scopes name lookups by `groupId`, and `cleanupTestUserFull` no
   longer calls `GET /rest/model/cleanup` (`VACUUM FULL`, the §6.J deadlock).
3. **§6.K** — the gate's e2e is chromium-only at 2 workers; the 23 specs that `import('/src/...')` run in their own
   Playwright project `chromium-vite` (`playwright.config.js`, `VITE_SOURCE_SPECS`) against a second Vite dev server
   on `:8898` started with `AM7_BACKEND=https://127.0.0.1:9443` (`vite.config.js:12,17`), whose origins were added to
   `CORS_ALLOWED_ORIGINS` (`docker-compose.test.yml`). `chromium`/`firefox` `testIgnore` those specs; with
   `PLAYWRIGHT_BASE_URL` unset both projects use `:8899` as before. `--list` under the gate's env: `chromium` 473 tests,
   `chromium-vite` 131 tests / 23 files, firefox none. `e2e/sdConfigLandscapePersist.spec.js` had a hardcoded
   `https://localhost:8899` REST base for its read-back/cleanup — under `chromium-vite` that would have sent the Docker
   session cookie (cookies are host-scoped, port-agnostic) to the **local** Tomcat/`am72db`; made relative to the
   project `baseURL`. Documented in `dockerDevSetup.md` §9 ("Path C").
4. **§6.C item 14** — the live `generateScene` e2e written and run (§2.8, `10 passed` ×3).
5. **§6.C item 15** — `src/AccountManagerObjects7/kontext-test-output/kontext-request.json` deleted (shows as `D` in
   `git status`; Stephen commits).
6. Working-tree hygiene found on the way: the committed `src/docker/bcprov-jdk18on-1.80.jar` was CRLF-mangled (a
   text-mode checkout of a binary) — `src/.gitattributes` now marks `*.jar`/`*.war` binary and the file is restored
   from the Maven cache; `src/.dockerignore` tightened. The **committed** blob stays corrupt until Stephen commits.

**Split verification 2026-10-10 12:33:19–12:34:13 local (`C:\tmp\pw-vite-split-verify.log`)**, from
`src/AccountManagerUx752`: `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/charGallery.spec.js
e2e/sdConfigLandscapePersist.spec.js e2e/breadcrumbNav.spec.js --workers=1` → **`8 passed (51.9s)`**, `PW_EXIT=0`:
`breadcrumbNav` ×2 ran as `[chromium]` (page on `:9443`), `charGallery` ×5 + `sdConfigLandscapePersist` ×1 as
`[chromium-vite]` (page on `:8898` → Docker). The `sdConfigLandscapePersist` (KI-65) read-back printed
`"initial":{"skipLandscape":false,"flux2IncludeLandscapeRef":true,"compositeMode":"flux2"}` →
`"afterToggle":{"skipLandscape":true,"flux2IncludeLandscapeRef":false,"steps":13}` → `"persisted":true` →
`"reloaded":{"skipLandscape":true,"flux2IncludeLandscapeRef":false,"steps":13,"model":"juggernautXL_ragnarokBy.safetensors"}`,
`"reloadedChecked":true` — i.e. the P0-5 `flux2IncludeLandscapeRef` default and the P0-11 `compositeMode` default
are what a fresh Ux752 SD config shows. Observation: `charGallery` test 1 logs `Images tab not visible — charPerson
form may not have loaded` and still passes (a soft, pre-existing assertion; not changed).

**Second full gate — launched 2026-10-10 12:38:10 local** from the git root:
`export PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443; unset LOOP_MODULE E2E_SD_LIVE CHAPBOOK_LLM_TESTS; bash
.claude/loop/verify.sh` → `C:\tmp\full-gate-p012-run2.log` (prints `GATE_EXIT=…` and an end timestamp). Module
order is the first gate's (Agent7, Console7, Objects7, Service7, Ux752). **Expected** from the module runs:
Agent7/Console7 PASS; Objects7 red **only** on §6.A ×3 / §6.B / §6.F; Service7 red **only** on §6.H; Ux752
build/vitest/lint PASS and e2e covering both chromium projects with no Firefox, no fixture teardown and no
`VACUUM FULL`. Progress at 12:41: Agent7 PASS/PASS, Console7 PASS/PASS, Objects7 build PASS, Objects7 test running.

**RESULT (run finished 2026-10-10 16:22:27 local, 3 h 44 min; `C:\tmp\full-gate-p012-run2.log`): `VERIFY_FAILED`,
`GATE_EXIT=1`.** Per module, verbatim from the log:

```
==> [AccountManagerAgent7] build … PASS (build) / test … PASS (test)
==> [AccountManagerConsole7] build … PASS (build) / test … PASS (test)
==> [AccountManagerObjects7] build … PASS (build) / test … FAIL (test)
[ERROR] Tests run: 902, Failures: 5, Errors: 0, Skipped: 15
[ERROR]   TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender:710 At least one PORTRAIT node must have been asserted, …
[ERROR]   TestUpstreamResolution.testLiveNullColumnRowsResolveFromDialect:247 No system.connection row in am7db has upstream IS NULL. …
[ERROR]   TestPbDeleteSeries.testStrangerDeniedThenOwnerDeletesWholeSeries:119 chapter 1 row
[ERROR]   TestPbListSeriesBooks…:138 / TestPbSeriesCast…:532   (the §6.A trio)
==> [AccountManagerService7] build … PASS (build) / test … FAIL (test)
[ERROR] Tests run: 108, Failures: 0, Errors: 1, Skipped: 0      (TestMediaUtilStreaming NPE — §6.H)
==> [AccountManagerUx752] build PASS / test (vitest) PASS / lint PASS / e2e FAIL
Running 604 tests using 2 workers
  5 flaky   (chat:24 chat page renders; featureConfig:101; views:28 dashboard; voiceTest:5; [chromium-vite] cardGame:328 4.2b)
  72 skipped
  13 did not run
  439 passed (1.9h)
VERIFY_FAILED
GATE_EXIT=1
--- end 2026-10-10 16:22:27 ---
```

The log keeps only the last 60 lines of the e2e phase, so the `N failed` line is cut off; derived from the totals:
**604 − 439 − 5 − 72 − 13 = 75 failed**. The surviving tail shows the complete `[chromium-vite]` part of the failed list
(the `chromium-vite` project runs last): **14** — `bugfix3-live` ×4 (`:93` reimage, `:275`, `:331`, `:509`),
`chatDeleteMsgDebug:12`, `pictureBookLive` C.2 / C.3 / H.1 / L.1, `sdConfigFlow:134` / `:243`, `verifyFixes:272`,
`worldGroupPicker.e2e:41` — so **61** are `[chromium]`, of which the tail names the last 37 (`connectionForm` …
`worldDeleteBreadcrumb`); the first ~24 (alphabetically `apiBaseUrl` … `chatConfig*`) were seen scrolling past at
~16:16 but are not in the log.
Backend: exactly the expected set and nothing else — Objects7 red only on §6.A ×3 / §6.B / §6.F (every Phase 0 class
green, same `902/5/0/15` as the first gate), Service7 only on §6.H. Versus the first gate's e2e
(`281 passed / 596 failed / 222 did not run` with Firefox and the destroyed fixture): `439 passed`, no Firefox rows,
no `did not run` wave, `am7-pg (healthy)` throughout, no PANIC in `docker logs am7-pg` — §6.I/§6.J/§6.K held.

**The 75 e2e failures are not yet triaged against a serial run** — the `e2e/results` artifacts of this gate were
wiped by the next Playwright invocation (§6.M's `apparelSequence` run; Playwright cleans `outputDir` on start — my
mistake, the artifacts should have been copied first). Triage in progress: a serial `--workers=1 --retries=0` re-run of
ten of the failing `[chromium]` specs (`C:\tmp\pw-triage-chromium-run1.log`); result and classification (environmental
vs. regression) recorded under **§4.3** when it lands. One `[chromium-vite]` failure is already explained and fixed:
`apparelSequence` was not in the failed list — it was a **fake green** (§6.M).

### 4.3 Post-gate triage of the 75 e2e failures (2026-10-10 16:30–22:10 local)

**Outcome in one line: all 61 `[chromium]` failures trace to one environmental cause (fixed) plus 16 residuals
that are each environmental, a missing fixture, or a stale/never-valid test — none touches a file Phase 0
changed; the 14 `[chromium-vite]` failures are re-run below. There is no Phase 0 regression in the gate-2 e2e.**

**Triage run 1** (`C:\tmp\pw-triage-chromium-run1.log`): 10 specs (`apiBaseUrl testHarness listNavigation
iso42001 webauthn connectionForm groupNav charPerson workflowRuntime picker`), `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443
npx playwright test … --workers=1 --retries=0 --project=chromium` → **`22 failed / 6 skipped / 29 passed`**.
Every failure's trace showed REST calls going to `https://localhost:8899/AccountManagerService7/rest/…` → **500**:
~37 `[chromium]` specs build their own `pwRequest.newContext()` with a **hardcoded `https://localhost:8899`**
(the Vite proxy), and that Vite proxies to the **local Eclipse Tomcat `:8443`, which is down** (Stephen's; I did
not start it). The browser half of each test was on the Docker stack (`:9443`, via `baseURL`) while the
fixture half hit a dead server — the per-spec `const BASE_URL = 'https://localhost:8899'` predates the Docker
gate. **Fix (§6.C item 20):** every such spec now reads
`const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899';` (the same fallback the
Vite-era runs used, so an unset env is unchanged). Deliberately left: `apiBaseUrl.spec.js:40` (its job is to
assert the Vite-proxy topology) and `worldGroupPicker.e2e.spec.js:17,31,77` (asserts a `:8899`/`:8443`
URL shape, not re-run).

**Triage run 2** (`C:\tmp\pw-triage-chromium-run2.log`, 16 specs serial, same command shape, specs
`apiBaseUrl testHarness listNavigation iso42001 webauthn connectionForm groupNav charPerson workflowRuntime
picker biometrics breadcrumb favoritesToggle groupMembers makeFact rangeSliderPersist`):
**`16 failed / 1 skipped / 53 passed (7.2m)`, `PW_EXIT=1`** (run 1 on 10 of these specs: `22 failed / 29 passed`).
Every one of the 16 is classified below with dated evidence; the Phase 0-touched Ux752 files
(`views/list.js`, `components/imageViewer.js`, `components/breadcrumb.js`, `components/formFieldRenderers.js`,
`core/formDef.js`) are **unmodified in `git status --porcelain`** — Phase 0's only Ux752 edits are
`features/chapBook.js`, `core/modelDef.js`, the two Vitest files, `playwright.config.js`, `vite.config.js`,
`e2e/helpers/api.js` and the e2e specs themselves.

| # | Failure | Class | Evidence |
|---|---|---|---|
| 1–2 | `apiBaseUrl:20`, `:52` | environmental | asserts the pre-Docker Vite-proxy topology by design (the one spec left on `localhost:8899`) |
| 3–5 | `testHarness:16`, `:25`, `:35` | environmental | `button[title="Tests"]` is `devOnly`; hidden in the production `dist` the container serves |
| 6 | `webauthn:112` | environmental | `SecurityError: This is an invalid domain` — the RP id rejects `127.0.0.1`; the fix is `--host-resolver-rules=MAP localhost 127.0.0.1` so the page URL can say `localhost` (troubleshooting.md item 1) — not applied, it changes every spec's origin |
| 7 | `biometrics:86` | missing fixture | `ENOENT … e2e\media\faceTest.jpg` — the file is not in the tree |
| 8 | `charPerson:258` | stale test | posts a schema-free body to `/reimage` → `400 Invalid JSON`; the handler's parse is unchanged since ≥ 2026-08-06 (`a3fb2edc`), the test dates from 2026-03-20 |
| 9 | `workflowRuntime:139` | stale test | expects `forms.data.reimage`; `d6374c40` (2026-03-28) removed it |
| 10 | `workflowRuntime:222` | stale test | `input[type="number"]).first()` is now the Denoising field (0.75), not the one the test fills |
| 11–12 | `listNavigation:103`, `:207` | stale test | strict-mode violation: `button:has(span:text("apps"))` matches both "apps App Panel" and the toolbar "apps" button |
| 13 | `picker:137` | stale test | `button:has-text("Clear")` resolves to the App Panel's "Clear Cache" button; the real picker buttons are icon buttons `title="Find"/"View"/"Clear"` with ligature text `search`/`file_open`/`backspace`, View/Clear rendered only when the field has a value (`formFieldRenderers.js:836-850`, renderer `a671884d` 2026-03-27; spec `13d55732` 2026-03-20). Screenshot `picker-…/test-failed-1.png` opened: charPerson form, Eye Color / Hair Color show `(none)` + the search icon, no text buttons |
| 14 | `charPerson:51` | stale test | same class — `button:has-text("Find")` count 0 since the 2026-03-27 icon-button renderer. The `[warning] Invalid field: 'beautyDescription'` in its console is benign: `beautyPicker` is a form-only format (`formDef.js:4154`), `beautySelector.spec.js:9` says charPerson intentionally lacks the model field |
| 15 | `breadcrumb:133` | never-valid test | `.breadcrumb-expand-btn` exists only in the spec — `git log -S breadcrumb-expand-btn -- src/components/breadcrumb.js` is empty (spec `2282ae48` 2026-04-06). The chevron does render (`breadcrumb.js:137`, `expand_more` button `id=bid`); screenshot shows `home / e2etest_… ⌄ / Data ⌄` |
| 16 | `listNavigation:155` | design question | after the carousel's Escape the list is in **GridPreview** mode (NavNote-012 header with Open/View/Delete + 25-item grid — screenshot opened), not the table the test expects: `list.js:304-333` `openItem()` sets `gridMode = 1; gridFullView = true` for data items since `25e07e45` (2026-03-27), and `onClose` (`:1228`) only clears `gridFullView`. Whether closing the carousel should restore table view is Stephen's call; the spec (`1f5cf910`, 2026-04-06) predates the design |

**`[chromium-vite]` re-run 1 — `bugfix3-live.spec.js`** (`C:\tmp\pw-bugfix3-live-run3.log`,
`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/bugfix3-live.spec.js --workers=1 --retries=0
--project=chromium-vite`): **`3 failed / 6 passed (4.1m)`, `PW_EXIT=1`** (gate 2 had 4 failures here; `:331`
now passes). Passed: `:25`, `:163`, `:198`, `:243`, `:331`, `:437` (the generateScene POST). Failed:

| Failure | Class | Evidence |
|---|---|---|
| `:93` reimage | stale test exposing a **pre-existing Service7 defect** | `generateImageForTags error: Error: null` after 4.7 s. The test picks **any** charPerson (`am7client.newQuery("olio.charPerson")`, `range(0,1)`, no owner/group filter, `:108-125`) → server log `AUDIT DENY system.user e2etest_shared to READ … olio.charPerson` → `AccessPoint.find` returns null → **`OlioService.reimageWithConfig` NPE at `OlioService.java:266`** (`BaseRecord oi = a1.get("profile.portrait");` sits outside the `if(a1 != null)` guard at `:244-265`) → 500 with an empty body. The test never reaches SD, so it cannot speak to §6.M either way (§6.M is proven live by `apparelSequence`). Not fixed — the Phase 0 change in `OlioService` is at `:691` only (P0-7); the NPE is recorded under §6.M "follow-ups" for Stephen |
| `:275` audio | never-valid test | waits for a URL containing `/audio/synthesize`; `audioTokens.js:129` has always POSTed to `applicationPath + "/rest/voice/" + encodeURIComponent(voiceName)` (first commit `13d55732` 2026-03-20 — the same commit as the spec) |
| `:509` pending indicator | never-valid test | `button[title="Send"]` never existed: `chat.js:1021-1025` renders `m("button", { type: "submit", … }, "Send")` with no `title`; `git log -S'title: "Send"'` is empty |

**`[chromium-vite]` re-run 2 — `sdConfigFlow.spec.js` + `pictureBookLive.spec.js`** (`C:\tmp\pw-pblive-sdcfg-run3.log`,
same command shape, 50 tests serial, 21:5x–22:1x local): **`6 failed / 1 skipped / 43 passed (13.2m)`, `PW_EXIT=1`** —
the same six as gate 2 (so they are deterministic, not load-related). The live parts passed: B.4 "Generate scene
image" `(2.6m)` through the Docker stack to Swarm `.39`, B.5 image record + URL, the whole D/E/F/G/I/J/K groups.
Classification:

| Failure | Class | Evidence |
|---|---|---|
| `pictureBookLive` C.2 `:614`, C.3 `:635` | stale test | the Work Selector has shown two picker buttons, "Browse Notes" / "Browse Documents" (`features/pictureBook.js:739-760`, `openDocumentPicker`), since `6866ac9a` 2026-03-30; the spec (`d6374c40` 2026-03-28) expects document names inline (`Items: 2 — Browse Notes / Browse Documents` in the run log). `pictureBook.js` is unmodified by Phase 0 |
| `pictureBookLive` H.1 `:1231` | stale test | the viewer's `edit` icon is "Edit Book" and reopens the wizard (`pictureBook.js:1026-1033`; the DOM snapshot after the click shows the wizard's "Image Generation" panel); the inline blurb textarea (`editingBlurb`) was removed in `e68e1ffe` 2026-03-31; spec `d6374c40` 2026-03-28 |
| `pictureBookLive` L.1 `:1748` | test fixture selection (same class as `bugfix3-live:93`) | picks **any** `olio.apparel` with an unfiltered org-wide search (`:1754-1760`); the by-id view then renders `Could not load olio.apparel 2b43502b-…` (DOM snapshot) — server log `AUDIT DENY system.user e2etest_pbmv… to READ … olio.apparel`. `AccessPoint.list` is not a per-record boundary (model-api.md), so the list returns rows the user cannot open. The test's own guard only checks for `Loading...` |
| `sdConfigFlow:134` | test fixture selection + §6.I residue | the probe's unfiltered `viewQuery('olio.charPerson')` (`:189-191`) returns the lowest-id charPerson org-wide — id 10 "Caleb MacKay", `ownerid=19`, a user that **no longer exists** in `a7_system_user_0_1` (the pre-§6.I shared user deleted by `bugfixSprint2`); the by-objectId re-read without a `groupId` is then `AUDIT DENY` → `qrHasResults:false`. `e2etest_shared` (228) owns 58 charPersons; a group-scoped first query would pass. Assertion dates from `65b2e9d5` 2026-05-12 |
| `sdConfigFlow:243` | internally inconsistent test | `:304-305` accepts "wizard appeared **or** library auto-loaded"; the run took the auto-load path (`[lib status after click] {"hasGroup":true,"groupName":"ChatConfigs"}`, `navUrl=true`, no toast) and then `:306` demands a wizard dialog in the DOM (`domDialogCount > 0`), which can never hold on the auto-load path. Assertion from `4a485902` 2026-05-11 |

Not re-run: `verifyFixes:272`, `chatDeleteMsgDebug:12`, `worldGroupPicker.e2e:41` (the last asserts the
`:8899` URL shape and is environmental by construction).

**Totals for the gate-2 e2e failures (75):** 61 `[chromium]` → one environmental cause fixed (§6.C item 20) +
16 residuals classified above; 14 `[chromium-vite]` → `bugfix3-live` 4 → 3 (classified), `sdConfigFlow` 2 + `pictureBookLive`
4 (classified, deterministic), 3 not re-run. **Zero Phase 0 regressions.** The classified residuals are listed for
Stephen under §6.M "follow-ups" (items 2–6 there, plus the five `[chromium-vite]` rows in this table).

**Scope note.** The 9 stale/never-valid specs and the missing `faceTest.jpg` are **not fixed** — they are
outside every Phase 0 task (memory `feedback-scope-discipline`) and listed for Stephen in §6.M "follow-ups".
The one Phase 0-adjacent item, the `:266` NPE, is likewise left: a 404/403 there is a one-line guard, but it is
not in the P0 brief.

---

## 5. Deviations from design

Each entry here is mirrored by a dated in-place edit in `PictureBookV3Design.md` (or the gap doc
`PictureBookImageGenerationDesign.md` where the error originated).

### 5.1 Doc corrections found during Phase 0 planning (2026-10-09) — facts, not design changes

1. **Orphaned runs are PENDING, not RUNNING.** `PbGraphUtil.startRun` (`:738`) writes PENDING; nothing
   stamps RUNNING. Edited: design opening note + §20 table row; gap doc Appendix A "Other".
2. **Only `PictureBookUtil.generateSceneImage` has a run lifecycle.** `PbNodeExecutor.executeNode`
   (`:113-143`) and `PbServiceFacade.testNode` (`:1472-1479`) open no run. P0-8 lands the finally-closure
   in `PictureBookUtil` and a FAILED stamp in `executeNode`, not in `PbServiceFacade`. Edited: design note.
3. **G-26 NPE is at `PictureBookUtil.resolveLandscapePrompt:3845`** (gap doc said `:3629`). Edited: gap doc.
4. **Raw `FIELD_BYTE_STORE` writes already reach `ByteModelUtil`** via `ByteArrayValueType.setValue`
   (`:49-61`); P0-7 is a convention/fail-loud change with no PBAC or vault effect (architect verified).
   Trap: `ByteModelUtil.setValue` throws if `dataBytesStore` is absent on the record, and reads
   `vaulted`/`enciphered`, so update branches must populate the full `byteStore`+`cipherExt`+`vaultExt`
   field set, not just `dataBytesStore`. Edited: gap doc.
5. **G-25 "no negative prompt" is wrong** — `SDUtil.createImage:1106-1109` applies a non-empty
   `negativePrompt`. Real gaps: style clause never appended for ChapBook; `hires`/`seed` args ignored;
   `hires` schema default `true` runs the refiner with null `refinerModel`. Edited: gap doc.
6. **`createImage:1117` NPEs on a sparse config** (unboxing outside the try); ChapBook's outer catch
   swallows it. Edited: gap doc.
7. **`SceneCompositeUtil:118-135` comment is wrong** — a `default` on an existing JSON field is picked up
   on restart (Path 1 caching of the resource-derived schema in `RecordFactory.getSchema`), not a Path 2
   blob migration. Fixed in code by P0-5; `objects7-reference.md` Path 2 wording to be clarified once
   the P0-5 test proves it empirically against `am7db`'s stale blob.
8. **ChapBook book settings live on the `olio.pb.book.sdConfig` FK**; `PUT /olio/picture-book/{id}/settings`
   404s for a ChapBook; `ChapBookService` has no settings endpoint. Edited: gap doc.
9. **G-24 live shape**: only Ux752 per-prompt mode leaks — the single `promptTemplate` wire param (the
   landscape slot) is applied to both the landscape and the scene-prompt `callLlm`. Edited: gap doc.

### 5.2 Design-level decisions made in Phase 0 (recorded defaults; redirect if wrong)

- **P0-11 chat fallback:** design §37.3 says `ChatService:1490` `resolveMode(sdConfig, true)` → `false`.
  With `false`, a config carrying neither `compositeMode` nor `useKontext` (null config, or a saved
  pre-`compositeMode` chat config) resolves **CLASSIC** (`SceneCompositeUtil:100`), not FLUX.2 — a
  schema-built config still resolves FLUX.2 from its `compositeMode` default. Implemented literally;
  the test is named for what it asserts. `legacyKontextDefault` then has no `true` caller; the
  parameter is kept (documented) until §37.4's Remove stage drops it.

### 5.3 Slice B implementation deviations and additions (2026-10-09) — mirrored in the gap doc Appendix A and design §30 row 0

1. **G-11 classic composite falls back to text-only txt2img** when neither a landscape nor any
   portrait is available. ✓ FOLLOWING INSTRUCTIONS — gap doc G-11 says literally "It should fall back
   to text-only"; the first cut only made the failure non-fatal and `SDUtil.compositeSceneCanvas` then
   composited a black canvas (luminance 1, §2.2). The fallback condition is `landscapeBytes == null &&
   !classicHasPortraits`; with portraits present the classic composite still runs on a blank background,
   exactly as it does when the landscape is skipped by config.
2. **G-11 surfaces the failure on the result:** two new fields on `olio.pictureBookResult` —
   `landscapeFailed` (boolean) and `landscapeError` (string). Not in the design; added so the UI can
   tell the user (G-31's "landscape is skipped" visibility). `olio.pictureBookResult` is constrained
   (`ioConstraints:["unknown"]`, no `DATABASE` entry → `DBUtil.isConstrained` true): response-only,
   never persisted, so the new fields are ephemeral and no schema path (Path 1/2, migration) applies.
   **[Corrected 2026-10-09 after the architect review — the earlier text here wrongly called them
   "column-backed, picked up on restart (Path 1)".]**
3. **G-11 refactor:** the landscape stage is extracted to `PictureBookUtil.renderLandscapeStage(...)`
   returning a `LandscapeStage{bytes,node,artifact}` holder, so the try/catch wraps one call and the
   node/artifact bookkeeping stays with it. Package-private seam `generateSceneImage(user, sceneOid,
   params, SDUtil)` added for fault injection; the public overload performs the SD-server null check
   (`500 SD server not configured`) **before** delegating, so an injected `SDUtil` never masks a missing
   server config. No-graph path: `FileUtil.emitFile("./land-<oid>.png", …)` is null-guarded.
4. **G-8 needs a pipeline-version bump.** The schema default is not part of `computeInputHash`, so
   previously cached composites would never re-render with the landscape reference;
   `PbGraphUtil.PB_PIPELINE_VERSION` is `pb2/3` (was `pb2/2`) and `TestPbGraph#case09`'s golden
   version line follows. Design §30 does not mention this; it is the mechanism §11.1 drift detection
   relies on. One-time cost: every existing graph's COMPOSITE nodes go stale on the next drift check.
5. **G-24 Option A:** the single `promptTemplate` wire parameter remains the landscape slot (and the
   extract-scenes slot); the scene-image prompt never receives a template override (`resolveScenePrompt(…,
   null)`). A separate scene-prompt template parameter was **not** added — the Phase 2 compiler owns
   prompt assembly, and adding a second wire field now would be removed again then.
6. **G-26:** the `null`/`"null"`/`"n/a"` guard is a local `sceneTextField(map, key)` helper over
   `NarrativeUtil.isMeaningful`, applied at the three read sites, plus the `BLANK_LANDSCAPE_FALLBACK`
   ("A detailed environment.") when the setting is not meaningful. Observation (not changed): a scene
   whose setting/action/mood are all null yields a scene prompt that is only the style clause (§2.2).
7. **P0-8:** `PbPipelineUtil.closeRun` now marks the `SceneGraph` closed **even when `g.run` is null**
   (the no-run case), so the `finally` closure is idempotent and never double-fails a node;
   `failNode(user,node,error)` is a new package-private overload used by `PbNodeExecutor.executeNode`
   (which stamps FAILED + `lastError` + `lastRunAt` and rethrows — it opens no run, §5.1 item 2).
8. **Test characterization flipped, not weakened:** `TestPbGraph#case01` asserted `planMost(true)` on
   `olio.pb.workflow` returns null (the pre-2026-10-07 nested-plan failure). The platform fix landed;
   the assertion is now `assertNotNull` with the record's fields checked. `TestOllamaModelUnload`
   asserts deltas against a drained baseline (§2.2) — the contract pinned is unchanged.
9. **WARN wording** at the classic-composite site distinguishes "landscape generation failed" from
   "landscape skipped by config" and names the degradation taken (blank background vs text-only).
10. **`PbPipelineUtil.failNode(SceneGraph, …)` (`:619-629`) now always increments `g.failed`.**
    Before, `g.failed++` sat inside the try after the `update` call, so a thrown `patchOf`/`update`
    skipped it (an `update` returning null was already counted). The split-out
    `failNode(user, node, error)` swallows its own exceptions, so the increment now always runs.
    Found by the verifier as undocumented;
    recorded here. Effect: `failedNodeCount` on the result reflects every node that was *asked* to
    fail, not only those whose FAILED stamp persisted. ⚠ MY JUDGMENT — the count is a report of what
    the pipeline did, and a node whose stamp failed to persist is still a failed node; redirect if the
    count should track persisted state only.
11. **`PbNodeExecutor.executeNode` (`:127-155`) catches every exception thrown inside the dispatch
    switch** — including `PictureBookException`s raised by the per-type executors (e.g. the 503 "No SD
    server URL available" at `:162`, 4xx/5xx from Swarm) and the switch's `default` 501 — and stamps
    the node FAILED + `lastError` before rethrowing. Found by the verifier as undocumented; recorded
    here. Checked 2026-10-09: the pre-dispatch 501 for a type outside `EXECUTABLE_TYPES` (`:122-126`)
    is thrown **before** the try, so an unimplemented node type still leaves the node untouched; the
    in-switch `default` 501 is unreachable because `EXECUTABLE_TYPES` and the switch cases are the
    same six types. So the only behaviour change is that executor-raised `PictureBookException`s (not
    just runtime exceptions) now stamp FAILED. ⚠ MY JUDGMENT — a node that was dispatched and could
    not complete is a failed node for drift/status purposes; redirect if a 503 "no SD server" should
    leave the node untouched.

### 5.4 Slice C transport deviations (2026-10-09) — P0-10c

1. ⚠ MY JUDGMENT — **`ChapBookService.ensureSchema` rewritten (root-object check, not substring).** Not
   in the design or the P0-10c plan; found because the new `PUT /settings` returned 400 for every body
   the Ux752 dialog shape produces (§2.4 run 1). Scope: the one private helper in `ChapBookService`,
   which `parseParams` calls for every ChapBook endpoint, so the fix also changes what `/create`,
   `/render`, `/scene/{id}/generate`, `/poem`, `/set` receive when the client nests a `schema` — they now
   get the fields they were documented to get. Alternatives considered and rejected: a `/settings`-only
   parse path (two parsers for one request model), or stripping the nested `schema` on the client
   (leaves the transport bug for every other caller). The design doc needs no edit (it does not
   describe `ensureSchema`); the gap doc Appendix A G-32 row gets a dated note that the ChapBook
   transport was dropping the client config before P0-10c. **`PictureBookService.ensureSchema` is the
   same code and was deliberately not touched** — §6.G item 1.
2. ✓ FOLLOWING INSTRUCTIONS — the Service7 contract test drives the real `parseParams` by reflection
   rather than a copy of its body, so the test fails if the production parse regresses
   (`llm-conduct.md` rule 1: exercise the actual functionality). The reflective call is the only way to
   reach a private method without a servlet container (`feedback-no-rest-mocking`).
3. ✓ FOLLOWING INSTRUCTIONS — the REST Playwright half of `chapBookBookSettings.spec.js` runs against
   the Docker stack as `e2etest_shared`, with a second persistent non-admin user for the cross-user case
   (`feedback-create-test-users-for-roles`); no admin, no mocks. The UI half is P0-10d.
4. ✓ FOLLOWING INSTRUCTIONS (security-reviewer finding, brief §4) — **the P0-10a settings skip list
   grew from per-render fields to per-render + inherited non-SD lists** (`PbBookUtil.BOOK_SD_CONFIG_SKIP`:
   `description`, `imagePath`, `imageName`, `tags`, `controls`, `attributes`). §6.C item 10 described the
   skip as identity + per-render only; a sparse body could therefore attach a tag/control/attribute to the
   stored `book-sdConfig` as a side effect of a settings write (proven red in §2.5 before the fix). The
   shared `SDUtil.SD_OVERRIDE_SKIP` was **not** widened: render overlays are in-memory and P0-10b's
   semantics are unchanged. Design doc needs no edit; the gap doc G-32 note gets a dated addition.
5. ✓ FOLLOWING INSTRUCTIONS (security-reviewer finding) — `ChapBookService.errorResponse` now escapes
   via Jackson instead of mirroring `PictureBookService.escapeJson`'s replace chain, because the mirror
   failed the new contract test on a tab character (§2.5 item 4). The two helpers now differ; the
   PictureBook twin is §6.G item 3.

---

## 6. Blocked / waiting on Stephen

### 6.A BLOCKED — three PB-series tests red in the Objects7 gate: `9085a440` made the policy cache correct and the series grant model does not satisfy the dynamic foreign-access rules (found 2026-10-09)

**Not Phase 0 scope** (the tests date from `d3cb77c3 PB Fix`, 2026-09-21) but they are red in the module
gate, so `VERIFY_OK` is unreachable until this is decided. **No product auth code was changed** — the
remedy touches Stephen's deliberate `CachePolicyUtil` fix or the series grant design (⚠ MY JUDGMENT boundary).

```
[ERROR]   TestPbDeleteSeries.testStrangerDeniedThenOwnerDeletesWholeSeries:119 chapter 1 row
[ERROR]   TestPbListSeriesBooks.testEntitledNonOwnerSeesAllChaptersStrangerSeesNone:138 entitled non-owner must see BOTH chapters expected:<2> but was:<0>
[ERROR]   TestPbSeriesCast.testRecopyAndMergeDenyReadOnlyUserAndDoNotMutate:532 subject MUST be able to READ the chapter book (else recopy 404s before the canUpdate gate)
```
(`C:\tmp\o7-suspects-run.log`; `TestPbListSeriesBooks` run alone: `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 15.74 s`.)

**Mechanism (PBAC trace, `getPolicyUtil().setTrace(true)` bracketed around
`PbServiceFacade.listSeriesBooks(writer, seriesOid)`; `C:\tmp\o7-listseries-trace.log`, bracket reverted).**
`PbBookUtil.readBook` → `AccessPoint.find` does two evaluations of `resource.policy.systemreadobject`
(policy = **ALL** rules) against the same chapter `olio.pb.book` urn:

1. Query-shape pre-check on a **narrow** read — `Scanning (objectId) … [name, groupId, objectId, organizationId, id, urn]`
   → `Adding 0 dynamic rules` → `Cache resource policy for systemReadObject-…pblistserieswriter-null-5JVy…` →
   `groupdatareadaccess … olio.universes.books.worlds.lcab51388d3.book = true` → PERMIT (trace :139-167).
2. `canRead` on the **full** record `bookRequest()` projects (`series`, `world`, `sourceData`, `sourceRange`
   populated) → `PolicyUtil.getSchemaRules` adds one `generic.policy.rule.onerequired` per populated
   identity-bearing foreign field → `Adding 4 dynamic rules` → `Cache resource policy for …-4pwE…` (a
   **different** key, :249-250). The `series` rule evaluates `ANY 0:6`: not owner (`584 == 133 = false`), not
   group owner, not `accountadministrators`, **no `/Read` on `auth.group.data:…olio.universes.books.book`**
   (the series row group, `PbOlioContextUtil.seriesRowGroupPath()`; the series Writer role is granted CRUD
   only on the chapter's own Book/Workflow/Artifacts groups by `grantSeriesRolesOnChapterGroups`), no
   `resourceReadAccess` on the series → `Policy ruled failed evaluation … ALL Pass = 1 Size = 5 = false` →
   `AUDIT DENY system.user pbListSeriesWriter to READ … olio.pb.book` (:360-377).

**Why it passed before.** Until `9085a440 Patch` (2026-10-09 09:49) `CachePolicyUtil.getResourcePolicy`
keyed the resolved policy by `name-actorUrn-token-resourceUrn`, and `policyCache` was never cleared
(`clearCache()`/`checkCache()` did not touch it). Evaluations 1 and 2 share actor and urn, so the
0-dynamic-rule policy cached by the narrow pre-check answered the full `canRead`: **the foreign-access rules
never fired on any `AccessPoint.find`.** `9085a440` keys by `resource.hash()` precisely to stop that
("keyed by urn, the first request's rule set answered every later one on that record") — and nothing else in
`git diff d3cb77c3 HEAD` on `PolicyUtil`/`AuthorizationUtil`/`PbOlioContextUtil`/`PbBookUtil` restricts
anything (the `PolicyUtil` changes only *relax* via `access.policies.bind`, which `olio.pb.book` does not
declare; the `PbOlioContextUtil` diff is the additive health audit; series grants are unchanged). So the
three tests were green only because the cache masked the rules; they are now exercising the rules as
designed, and the series grant model does not satisfy them.

**The same rule set explains all three failures, and two are product paths, not test artefacts:**
- `TestPbListSeriesBooks` — a series **Writer** who is not the owner cannot read any chapter (N4
  whole-series canvas: `listSeriesBooks` returns `[]` for every collaborator).
- `TestPbDeleteSeries:119` — the **olio principal** (`olioUser`, owner of every series/world/chapter row)
  cannot `readBook` a chapter because the chapter's `sourceData` is the author's private
  `data.data` (`/home/pbListSeriesOwner/PbListSeriesData`, ownerId 583) and `sourceRange` is owner 583 too.
  Series delete runs as the olio principal, so whole-series delete is affected in product.
- `TestPbSeriesCast:532` — a subject with a **direct Read grant on the chapter's Book group** still cannot
  read the chapter (same `series` + `sourceData` rules).

**Remedy options — your call; none applied:**
1. **Grant design.** Have `grantSeriesRolesOnChapterGroups` / `newSeriesConfiguration` also grant the series
   Writer+Admin roles (and the universe Reader) `Read` on the series row group (`/Olio/Universes/Books/Book`)
   — fixes the `series` rule, but **not** `sourceData`/`sourceRange`, which live in the author's home
   directory: a collaborator would additionally need Read on the author's manuscript, and the olio principal
   would too. That is a data-sharing decision (should a chapter's existence be visible to someone who may not
   read its manuscript?).
2. **Read-path projection.** Make `PbBookUtil.readBook`'s authorizing read project foreign fields as
   id-only references (no populated nested records → `isIdentityRecord` false → no dynamic rule), then populate
   the linkage DTO from the already-authorized record. Keeps today's "entitled by the chapter's own group"
   semantics; the collaborator sees the linked records' identity/common fields without a per-record read check
   on them. Smallest change, but it is a deliberate narrowing of the foreign-access rule for this model.
3. **Rule semantics.** Decide in `PolicyUtil.getSchemaRules` which foreign fields impose a read rule (e.g.
   only fields the model marks, or only when the linked record is in a different group than the parent).
   Engine-level — yours.

Until decided: these three stay red in the module gate and are reported, not closed as "pre-existing".
If you want option 2 as a Phase 0 stopgap, say so and it gets a test (`listSeriesBooks` as Writer/olio
principal/direct-grant subject, with the stranger still denied).

### 6.B BLOCKED — `TestUpstreamResolution.testLiveNullColumnRowsResolveFromDialect`: fixture expired

The test asserts that `system.connection` rows with a `NULL` `upstream` column resolve from `dialect`; `am7db`
now has **0 of 142** rows with a NULL `upstream`, so there is nothing to assert against and the test's own
comment forbids weakening it. Options: seed one NULL-upstream row from the test (an owned, named
`system.connection` it creates and deletes), or retire the live case now that the backfill is complete.
Needs your call on which; not touched.

### 6.D Waiting on Stephen — PostgreSQL PANIC in `postgres3` during module-gate run 1 (2026-10-09)

Gate run 1 (§4.1) hit `Failures: 545` because `postgres3` (the `am7db`/`am72db` container on `:15432`)
crashed mid-run and every later test lost its connection. Verbatim from `docker logs postgres3` (UTC):

```
22:25:24.652 [913] LOG:  checkpoint starting: time
   (previous checkpoint: wrote 59870 buffers (11.4%) … write=269.346 s … total=270.062 s)
22:28:19.487 [6118] PANIC:  could not write to file "pg_wal/xlogtemp.6118": Interrupted system call
22:28:19.487 [6118] STATEMENT:  COMMIT
22:28:29.308 [1] LOG:  client backend (PID 6118) was terminated by signal 6: Aborted
22:28:29.308 [1] LOG:  terminating any other active server processes
22:28:43.577 [6131] LOG:  database system was not properly shut down; automatic recovery in progress
22:30:32.865 [1] LOG:  database system is ready to accept connections
```

The crash was on a WAL write (`COMMIT`) while `TestOlioLoadDataFreshOrg` was loading the Olio seed into a
fresh org — the test that grows `A7_data_cenWord_0_1` every run — right after a 270 s checkpoint of
59 870 buffers. The container recovered on its own (~2 min, 22:28:43 → 22:30:32) and run 2 (§4.1) ran the
full module against the same database with no DB-side failure; `am7db` re-verified intact (170
`a7_system_modelschema_0_1` rows). `am72db` was not touched. The host was at `df -h /c -> 944G total, 451G
used, 494G free (48%)` at the next probe (§3), so this is write-latency pressure on `docker_data.vhdx`, not a
full disk.

Not acted on — two decisions are yours:
1. **Does `TestOlioLoadDataFreshOrg` belong in the default module gate?** It is the one test in the module
   that loads a full Olio seed per run; every gate run grows `am7db` and spends several minutes in a single
   transaction. Options: keep it (and accept the ~3 min + growth per gate), gate it behind a property the way
   the SD/LLM tests are, or move it to an explicit-only suite. ⚠ MY JUDGMENT would be the property gate, but
   it is not mine to make under `feedback-scope-discipline`.
2. **Does the vhdx / `C:` write pressure need looking at** (e.g. `docker_data.vhdx` placement, WSL memory /
   `fsync` settings, or a `checkpoint_completion_target` / `max_wal_size` bump in `postgres3`)? I have not
   changed any Docker or PostgreSQL setting.

### 6.E Flags from the architect code review of Slices A/B (2026-10-09) — yes/no from Stephen

1. **Check order in the public `generateSceneImage` overload (`PictureBookUtil.java:9443`, P0-6/P0-8).**
   The P0-8 seam had moved the sdApiType/sdServer null check **before** `authorizeSceneAccess`, so an
   unauthorized caller against a misconfigured SD server would get 500 instead of 403. Not a PBAC bypass
   (the package-private body still authorizes first on every path), but it was a behaviour change the task
   did not ask for: the committed code (`97d411bb:9422-9428`) authorized first. **Restored** — the overload
   now passes a null `SDUtil` through and the body's post-authorization `sdu == null` check throws the 500.
   ✓ FOLLOWING INSTRUCTIONS (committed behaviour). Covered by the existing `TestPbGraph#case16` path
   (non-admin user, unreachable server); re-run in the next gate.
2. **Pre-existing `SDAPIEnumType.valueOf(sdApiType)` at the same site** contradicts the "never manual
   `valueOf`, use `getEnum()`" convention. Out of scope; noted only.
3. **Raw `FIELD_BYTE_STORE` access outside the PB path** (not demanded by P0-7): Objects7 `ThumbnailUtil:153`,
   `StreamUtil:534`, `ScriptUtil:75`, `FeatureConfigUtil:422/553/576`, `DocumentUtil:202`,
   `RecordSerializer:156/403`, `Am7ToolProvider:278`, `Am7ResourceProvider:112/117`, `Chat:2389/2392`,
   `FileIndexer:390/608/634`, `ChatUtil:1895/1914`; Service7 `ArticleUtil:338`, `ComplianceService:227/258/266`,
   `ISO42001Service:290`; ISO42001 `TestRunner:228`, `PdfExporter:163`. Left alone under scope discipline.
4. **Rules gap:** `architecture.md` says nothing about constrained/response-only models
   (`ioConstraints:["unknown"]` → no table, so no schema-change path applies). The §5.3 item 2 mistake came
   from exactly that gap; a one-line rule would prevent the next one. Not added — rules files are yours.

Non-blocking suggestions from the architect code review of **Slices C/D** (23:40), none applied (scope
discipline), yes/no from Stephen:

5. **`TestChapBookRenderStyle.java:54,216` `@Test(timeout = 20 min)` for one scene.** Recorded feedback
   (`feedback-timings-never-test-thresholds`) says per-scene ceilings ≥ 30 min; raise it or drop the attribute.
6. **`ChapBookUtil` public `renderChapBookScene` overload** still uses `SDAPIEnumType.valueOf(sdApiType.toUpperCase())`
   — same convention gap as item 2, pre-existing.
7. **`PbConfigUtil.java:324` javadoc `{@link SDUtil#SD_OVERRIDE_SKIP}`** targets a private field; the link breaks.
8. **`TestChapBookRestContract.java:57`** invokes private `parseParams` via `setAccessible(true)`; a
   package-private seam would be cleaner.
9. **Doc path error in my brief** to the reviewer: `persistBookSettings` lives at
   `src/AccountManagerUx752/src/workflows/pictureBook.js:1282-1291`, not `src/features/pictureBook.js`.
10. **Rules gap:** `architecture.md` says nothing about JUnit `@Test(timeout)` on render/LLM tests; the only
    guidance is in memory. Consider promoting "timings are budgets, never thresholds; per-scene ≥ 30 min"
    into the Verification standard section. Not added — rules files are yours.
11. **`OlioService.java:218` and `:644` unbox `imp.get("hires")` directly** (phase-level verifier,
    2026-10-10, §4). On a client config whose `hires` field is present but null — the exact shape P0-2
    made `SDUtil.createImage` safe against via `SDUtil.hiresOf` — the implicit unboxing into
    `createImage`'s primitive `boolean hires` / `long seed` parameters NPEs in the resource method
    before `createImage` is reached, so the P0-2 fix is unreachable from `reimage` / `generateArt` for
    that input (verified: both lines pass `imp.get("hires"), imp.get("seed")` straight through).
    Pre-existing; P0-2 touched neither line. Fix: `SDUtil.hiresOf(imp)` plus a null-safe seed read.

### 6.F Waiting on Stephen — `TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender` fails alone: the test creates characters where the W3 resolver no longer looks (found 2026-10-09)

**Not Phase 0 scope and not caused by Slices A/B** (`git diff` of `PictureBookUtil.java` touches neither
`resolveSceneCharacter`, `listCharacters` nor `charsGroupPath`), but it is one of the red tests in the
module gate, so it is reported here rather than closed as "pre-existing". Solo-run output verbatim in §4.1.

**Mechanism (confirmed in `am7db` after the solo run, recursive-CTE path over `a7_auth_group_0_1.parentid`
joined to `a7_olio_charperson_0_1.groupid`):**
```
11170 | home/pbCustomTestUser/Data/PictureBooks/PB2 Fresh Character Book             | 0
11172 | home/pbCustomTestUser/Data/PictureBooks/PB2 Fresh Character Book/Characters  | 2   Jideon, Touvier
11171 | home/pbCustomTestUser/Data/PictureBooks/PB2 Fresh Character Book/Scenes      | 0
 8079 | Olio/Universes/Books/Worlds/pb2-fresh-character-book                          | 0
 8083 | Olio/Universes/Books/Worlds/pb2-fresh-character-book/Population               | 0
```
1. The test (`TestPictureBookWorkflow.java:615`) builds the book with the **8-arg legacy
   `createFromScenes`** (`PictureBookUtil.java:9024`), which delegates to the 9-arg overload with
   `pb2BookObjectId=null`; on that path (`:9147-9154`) `groupName = effectiveBookName`, `ensureBookGroup`,
   `charsGroup = ensureSubGroup(bookGroupPath, "Characters")` — so `createCharPerson` writes both charPersons
   into the PB1 group `<book>/Characters` (id 11172). The test's own javadoc (`:866-869`) documents this
   deliberately: "the fresh-character case uses the 8-arg legacy createFromScenes, which keys the PB1 group on
   the book name". `listCharacters` (`:11006`) still finds them there (legacy `{bookGroupPath}/Characters`
   fallback), which is why `assertSubRecordsPersisted` passes and `createFromScenes created 2 character(s)
   from scratch` is logged.
2. The render then calls `generateSceneImage(…, params(slug, true, FIXED_SEED), "SWARM", …)`, and
   `resolveSceneCharacter` (`:2935`) → `findSceneCharacterGroups` (`:2889`) → `findBookPopulationGroup` (`:2686`)
   searches **only** the book world's `Population` group (id 8083, 0 rows). The comment at `:2956-2960` is
   explicit — Stephen's commit `2fe062a3` (2026-09-20, "Mark feat prep", W3): *"characters live ONLY in the book
   WORLD's Population group. createFromScenes is now a single path and createCharPerson (B4) routes every book's
   characters into that Population group, so the legacy PB1 <book>/Characters probe has been removed"*. The
   Population group is found (no "No book-world Population group found" warning), it is simply empty, so both
   names fall through all four `findCharPersonByNameInGroup` passes → `Could not resolve scene character` →
   `Stage 1 complete: 0 portraits generated` → no PORTRAIT nodes → `:710` fails.

So the W3 statement "createFromScenes is now a single path" is true only for the 9-arg PB2 overload; the
8-arg legacy overload still exists and still routes characters to `<book>/Characters`, and this test has
been using it since before W3. Deterministic; the earlier "re-run alone" classification in §4.1 was wrong for
this test (it was right for `TestStepBudgetComparison`).

**Remedy options — your call; none applied (⚠ MY JUDGMENT boundary — both touch a path you changed in W3):**
- **(a) Fix the test** to build the fresh book through the 9-arg PB2 `createFromScenes(…, pb2BookObjectId)` so
  characters land in the world Population, consistent with W3's single-path decision. The test's intent
  ("createFromScenes → createCharPerson genuinely runs") still holds. **Recommended.** It would also mean the
  8-arg overload has no caller left that expects its characters to render — worth deciding whether it should
  be retired or whether the 8-arg path should itself route to the Population group.
- **(b) Restore the legacy `<book>/Characters` probe** in `resolveSceneCharacter` as a fallback after the
  Population group. Contradicts the W3 comment, so I will not do it without your say-so.

**Secondary fixture issue, same test class (also not touched):** `PB_CHAT_CONFIG_NAME = "PictureBook
gpt-oss:120b 6.chat"` (`TestPictureBookWorkflow.java:87-89`) names a model only `.42` serves, but
`LlmTestGate` rewrites `test.llm.ollama.server` in place to the tier that answered — the local container
(`http://127.0.0.1:11435`, which serves only `qwen3:8b`, `goekdenizguelmez/JOSIEFIED-Qwen3:8b`,
`nomic-embed-text`) — so every LLM call in this test logs `Chat config: model=gpt-oss:120b …
serverUrl=http://127.0.0.1:11435` → `LLM call failed (HTTP 404): model 'gpt-oss:120b' not found` and falls back
(reduce-character → raw text, guess-apparel → random outfit, landscape-prompt → setting text, scene-image-prompt
→ raw concatenation). `TestPictureBookCustom` (`:170-204`) already guards the same constant with
`LlmTestGate.servesModel(directServer, PB_LLM_MODEL)` and falls back to the route's PB model;
`TestPictureBookWorkflow` has no such guard. It does not cause the `:710` failure, but it means the test is not
exercising the LLM path it names. Options: port the `servesModel` fallback, or pin the chat config to the
route's resolved PB model (`test.llm.model.pb`). Your call.

### 6.C Decisions already made under auto mode

Items 2–7 are ⚠ MY JUDGMENT calls made under auto mode after the architect review
(2026-10-09; verdict: P0-1/3/4/7/8/9/10 APPROVED, P0-2/5/6/11 CHANGES-NEEDED, all resolved below).
Say so at the phase stop if any should go the other way.

2. **P0-2 `hires` semantics.** `createImage` now honours its `hires` argument, and the refiner block
   runs only when `hires && refinerModel` is non-blank (WARN otherwise — a generic guard, per
   `feedback-model-agnostic-llm-safeguards`). Callers that passed a literal `false` while their
   config said `hires=true` (`ChapBookUtil:1876`, `PbNodeExecutor:189/:564`, `PictureBookUtil:9854`)
   now pass the effective config's `hires`, so today's config-driven behaviour is preserved except
   that a config with **no** `refinerModel` no longer runs a refiner pass at all. `OlioService:217/:643`
   left as they are unless they pass a literal.
3. **P0-5 side effects.** Adding `"default": true` to `flux2IncludeLandscapeRef` supersedes the
   "NO SCHEMA DEFAULT ON PURPOSE" note at `configModel.json:366` (rewritten). `flux2Defaults.json`'s
   `includeLandscapeRef` stays as the fallback for *sparse* records (field absent → `Flux2Defaults`),
   documented as unreachable for schema-built configs. `PbGraphUtil.PB_PIPELINE_VERSION` is bumped
   because `configModel.json:8` says a changed default must bump it. Every API-/test-created FLUX.2
   book now pays the third (landscape) reference unless it opts out; Ux752's wizard still pins
   `flux2IncludeLandscapeRef=false` + `skipLandscape=true` (`pictureBook.js:243-244`) — left as is.
4. **P0-6 test seam.** No static test toggle in production code (`feedback-test-only-instrumentation`).
   The landscape stage is factored so the test can inject the failure from the test side (an `SDUtil`
   subclass supplied through a package-private overload, or a poisoned per-call landscape config);
   the subclass lives in the test.
5. **P0-8 PENDING semantics** left alone (no RUNNING stamp in Phase 0; Phase 1 §21 owns it).
6. **P0-9 `applyConfigStyle`** hosted on `SDUtil` (the style seam) rather than widening `PictureBookUtil`;
   ChapBook follows the effective config's `hires` (no forced `false`) and relies on the P0-2 guard.
7. **P0-10 transport** = new `PUT /rest/olio/chap-book/{bookObjectId}/settings` in `ChapBookService`
   (`@RolesAllowed`, transport-only, delegating to `PbBookUtil.setBookSdConfig`) with an H2 Service7
   test, instead of widening `PictureBookUtil.setBookSdConfig` (coupled to PB1 `.pictureBookMeta`).
   The client overlay is request-transient and is **not** folded into `resolveDeclaredConfig`/`configHash`.

1. **Four artifact schema fields — Phase 0 or Phase 1?** Design §23 says `attempt`, `variantIndex`,
   `reviewStatus` and `durationMs` "do not exist on `artifactModel.json` today … they are added in
   Phase 0 (§32)", but §30 row 0 (and the brief's P0 list, which reproduces it) does not name them.
   ⚠ MY JUDGMENT: **not** adding them in Phase 0; they are nullable column-backed fields (a JSON edit +
   restart per `objects7-reference.md` Path 1) and the first consumer is Phase 1's review status, so
   adding them at the start of Phase 1 loses nothing. Say if you want them in Phase 0.

**Slices C/D (2026-10-09, after the planner's plan and the architect's plan review — §4 table).**
Items 8–15 are the resolutions; 8–11 and 14 are ✓ FOLLOWING INSTRUCTIONS (rules files / architect
blocking item), 12, 13 and 15 are ⚠ MY JUDGMENT.

8. **P0-10a persistence shape** = one `olio.sd.config` row named `book-sdConfig` in the book's
   group, FK'd from `olio.pb.book.sdConfig` — the only shape the existing `foreign` FK
   (`bookModel.json:64-69`) admits; same name/group PB1's `persistBookSdConfigFk` already uses, so a
   PB1 book and a ChapBook look identical to `PbConfigUtil.bookConfig`. The create branch first
   **adopts** an existing `book-sdConfig` row in that group (`SdConfigUtil.findConfig`) before creating
   one (architect blocking item: PB1's FK patch is best-effort, so an unlinked row is reachable);
   covered by a test that pre-seeds an unlinked row and asserts no second row.
9. **Writes run as the request user via `AccessPoint`**, never the olio principal; the GET never
   writes (`architecture.md` "read paths must not create"). The REST GET reads the config via
   `AccessPoint.find` with an explicit projection, not through `ensureFullSdConfig`'s search-layer
   refetch (architect suggestion adopted).
10. **Update path** = field-name `newInstance` patch of identity + `name` (copied from the existing
    row) + the changed fields; the client's identity fields are discarded (`SD_OVERRIDE_SKIP`), so a
    client config carrying another row's `objectId`/`groupId` cannot redirect the write. Update results
    are asserted; a null result is an error, not a WARN (unlike PB1 `:2128`, left as is).
11. **P0-10b overlay never hashed.** `resolveEffectiveConfig(book, node, composite, clientOverlay)` is
    additive; `resolveDeclaredConfig`/`configHash` are untouched. The overlay sits **below** the page
    `configOverride`. It must never be called from `PbNodeExecutor` (a transient overlay that changes
    the image but not the hash would be a §11.1 drift blind spot once ChapBook pages are graph nodes in
    Phase 1) — recorded in the overload's javadoc and here; Phase 1 must either persist the overlay into
    the node's `configOverride` or reject it.
12. ⚠ **Render persists settings.** In the Ux752 ChapBook render dialog, "Render" first `PUT`s
    `/settings` when the book-level config has unsaved edits, then sends only the per-render delta as
    `sdConfig` on the render POST. A render click therefore saves book settings as a side effect. Chosen
    so that what the user sees in the dialog is what the stored config says (G-32: the client config must
    not silently become the whole config). If you want render to stay side-effect free (explicit Save
    only), say so.
    **Made precise for P0-10d (2026-10-09, planner + architect review, both CHANGES-NEEDED → resolved):**
    (a) "unsaved edits" on a book that has **no** `book-sdConfig` row yet means the **whole seeded
    entity** — the dialog seeds from the user's `sdcfg-default` preference, but the server render chain
    (`PbConfigUtil.mergeTiers` `:371-381`: schema defaults → stored book row → client overlay → scene
    override) has **no** user-preference tier, so sending only the touched fields would render with
    `configModel.json` defaults while the dialog showed `sdcfg-default` — the exact mismatch item 12
    exists to prevent. So the **first** Save/Render/Regenerate/Re-render of a row-less book PUTs the
    full seeded entity (the same thing PB2 `persistBookSettings`, `pictureBook.js:1282-1291`, already
    does), and from then on only `inst.changes` fields go on the wire. Consequence: once a row exists a
    later change to the user's `sdcfg-default` does **not** propagate to that book — say if you want it
    to. (b) A stored row's identity, `name`, `description`, `imagePath`, `imageName`, `tags`, `controls`
    and `attributes` are stripped client-side before PUT **only** to keep the wire sparse; the server's
    `BOOK_SD_CONFIG_SKIP` / `SD_OVERRIDE_SKIP` stay authoritative. (c) Explicit Save buttons exist too
    (`cb-save-book-sd` in the review toolbar panel, `cb-dialog-save-book-sd` in the render dialog), so
    "explicit Save only" is a one-line change (drop the PUT from the render sync) if you want it.
13. ⚠ **Transport names.** `GET`/`PUT /rest/olio/chap-book/{bookObjectId}/settings`, mirroring
    `PictureBookService`'s `/settings` pair; body/response is the `olio.sd.config` record (`toFullString`),
    PUT accepts `{"sdConfig": {...}}` via `olio.pictureBookRequest` (already the service's request model).
14. **P0-11 honesty of the handoff.** The `ChatService.java:1490` `resolveMode(sdConfig, false)` flip is
    verified by **compile + the Objects7 `resolveMode` tests only** — there is no Service7 test for it
    (servlet mocking is prohibited); it is reported that way, not as "tested". `TestKontextDeprecation`'s
    description-prefix assertions are a documentation check and do not count toward behavioural
    coverage; the behavioural assertions (neither field → CLASSIC, `useKontext` → KONTEXT, WARN emitted)
    are the coverage. **The phase-level verifier (2026-10-10, §4) rates this gap Medium:** a future
    edit restoring `true` at `ChatService.java:1492` re-activates Kontext for chat image requests with
    no red test. ⚠ MY JUDGMENT at the time, not done: the only honest test is a live `generateScene` call
    (chat session + two characters + LLM scene description + SD render) asserting the classic pipeline
    ran, and the mode is not in the response — it is observable only in the log or by image shape.
    **DONE 2026-10-10 — Stephen: "Yes I want the generateScene 2e2 done live."** Written as the
    `E2E_SD_LIVE`-gated describe `generateScene live composite modes (item 14 live)` in
    `e2e/chatChainSceneReimageRoutes.spec.js`, green `10 passed` in runs 1/3/4 with the pipeline asserted
    from the container log (`Scene composite [classic]` at 512×512 for an explicit `classic`;
    `Scene composite [flux2]` at 1024×768 when the mode is absent) — full results, PNG descriptions and the
    four findings it surfaced in §2.8. Findings, for the record: (1) the REST contract is "absent mode ⇒
    schema default `flux2`", not classic — the `legacyKontextDefault=false` flip only matters for hand-built
    null-mode configs; (2) `Chat.generateSceneDescription`'s `pruneSkip + 2` start index hides a single
    exchange under `assist=false` and falls back to `two people facing each other` with no LLM call
    (§6.G item 13); (3) bare-fixture charPersons prompt as `(null) (null hair)` + `naked/nude` (§6.L);
    (4) host-vs-Docker clock skew makes `docker logs --since now-Ns` windows leak between requests — the
    test snapshots with one absolute `--since` and slices the delta.
15. ⚠ **Tracked `kontext-test-output/kontext-request.json` not deleted** (it carried uncommitted local
    modifications — §6.E). Writers redirected to `target/kontext-test-output/`, `.gitignore` entry added.
    **DONE 2026-10-10 — Stephen: "that kontext json is from one of your older runs - delete it."** Deleted
    from the working tree (`git status`: `D src/AccountManagerObjects7/kontext-test-output/kontext-request.json`);
    the deletion sits in the working tree, unstaged, for Stephen's commit like everything else in this session
    (no commits by me).
16. ⚠ **P0-10d: the create view's SD panel is persisted to the new book on create success, not carried
    over by heuristic.** The create view ("Default image (SD) settings", `chapBook.js:1515-1533`) has no
    book yet, and `createChapBookBody` (`:83-90`) never sends an sdConfig, so its edits had no path to
    the book at all. My first draft carried the create-view instance into "the next row-less book opened";
    the architect and the planner independently rejected that (it would leak create-view edits into any
    older row-less book opened next, and then persist them there via item 12). Chosen instead: in
    `doCreateChapBook`, once the new book's objectId is known (`:1248`) and before navigating to the
    reader, `PUT /settings` the create-view config (whole entity per item 12(a)) to **that** book, then
    re-key the in-memory instance to it; on PUT failure toast a warning, keep the instance keyed to the
    new book as unsaved, and let the first render retry (item 12). This is what PB2 does
    (`persistBookSettings` before the first batch) and removes the heuristic entirely. The in-memory SD
    instance is otherwise strictly per book: opening a different book reloads from `GET /settings` (or
    reseeds from `sdcfg-default` when the book has no row), and returning to the create view always
    reseeds — a book's values never flow back into the create view. The alternative (key the carry-over
    to `lastCreatedBook`'s objectId only) was rejected because `lastCreatedBook` outlives the create
    view, so later create-view edits could still reach that book. Say if you prefer no PUT at create time.
17. ⚠ **P0-10d implementation calls made by the ux-specialist, accepted by me** (§2.6). (a) The
    create-view "Image settings" toggle (`renderConfigDesignSettings`) is rendered by all three mounts
    (PoemLibrary / Reader / Review), so it keys the instance on the mount's book —
    `ensureRenderSdConfig(bookObjectId || null)` — rather than the literal `null` my brief said; the
    literal would have re-seeded a review book's loaded row the moment the toggle was opened. (b)
    `getBookSettings` treats a 200 body without `schema`/`objectId` (the server's `{}`) as "no row" and
    any non-2xx as an error; `ensureRenderSdConfig` catches the error, toasts a warning and falls to the
    seed path rather than leaving the panel blank. The risk of (b): a transient GET failure on a book that
    **does** have a row seeds the panel from `sdcfg-default`, and a Save in that state PUTs the whole
    seeded entity over the stored row (item 12(a)). The warn toast is the only signal. Say if you want a
    hard failure (panel disabled until GET succeeds) instead. **(b) superseded by item 19** — the P0-10d
    verifier rated exactly this risk Medium and it is now the hard failure.
18. ⚠ **Ux752 `core/modelDef.js` `flux2IncludeLandscapeRef` mirror synced to `"default": true`** and the
    KI-66 mirror test updated (`sdConfigPanelLandscapeToggle.test.js:64-76`, `toBeUndefined()` →
    `toBe(true)`). P0-11's scope was the six kontext descriptions in the mirror, but P0-5 had already
    changed the server schema and the mirror still said the field had no default — a statement that was
    now false, and `am7model.newPrimitive('olio.sd.config')` seeded `false` from it, so a whole-entity
    first Save (item 12(a), P0-10d; PB2 `persistBookSettings`) would have persisted `false` and undone
    G-8 for that book through the stored-row tier. No ChapBook render effect (`ChapBookUtil` never reads
    `includeLandscapeRef`); PictureBook composites are the exposure. Persisted `sdcfg-default` /
    `book-sdConfig` rows that already carry `false` are data and stay `false` (P0-5 said the same). The
    verifier confirmed the mirror now matches `configModel.json:366`. Reverting is a one-line change if
    you want the mirror to lag the server deliberately.
19. ⚠ **P0-10d verifier findings fixed by me, 23:40–23:50, without asking first** (§2.6 "P0-10d verifier
    verdict"). The verifier rated item 17(b) a Medium: a failed `GET /settings` seeded the panel and the
    next Save/Render PUT the whole seed over a real stored row — a silent clobber of the very row G-32
    exists to persist, so I fixed it rather than defer it. Shape chosen: (a) a failed GET for a book key
    never seeds — nothing is published, a warn toast fires, the three panel sites show the error with a
    **Retry** button, both Save buttons stay disabled (null delta) and a render proceeds on the server's
    stored row (`{sdConfig:null}`); the create view (key `null`) never GETs and still seeds. The
    alternative — keep the seed but mark it "unsafe" so Save/Render refuse — was rejected as showing the
    user values the render would not use. (b) The Low (`null` is both the create-view key and the idle
    sentinel) is fixed with a monotonic build token (`_renderSdConfigBuild`); only the newest build may
    publish or clear the in-flight promise. (c) The Playwright test for (a) forces **one** browser GET to
    503 with `page.route` — the stack is live, the seed PUT, the Retry GET and the final REST check are
    real; it is the only way to reach the client failure path without breaking the stack. This is not
    the REST mocking the no-mocking rule forbids (no business logic is faked), but it is a synthesized
    response in an e2e test, so it is flagged. Say if you want the error state to look different
    (e.g. disabled panel instead of an error line), or no `page.route` in e2e.
20. ✓ (continuation of §6.K under "These are your own tests and test conditions from prior runs, so
    improvise") **~37 `[chromium]` e2e specs changed from a hardcoded `https://localhost:8899` REST base to
    `process.env.PLAYWRIGHT_BASE_URL || 'https://localhost:8899'`** (§4.3). Cause: those specs log their
    fixture `pwRequest` context in at the Vite proxy, and the proxy's upstream — the **local Eclipse Tomcat
    `:8443` — is down** (it is Stephen's; I did not start it and nothing in Phase 0 touches it), so every
    fixture call 500'd while the browser half of the same test ran on the Docker stack. With the env unset the
    specs behave exactly as before. Same class as the §6.K `sdConfigLandscapePersist` fix (a hardcoded `:8899`
    would also have sent the Docker session cookie to the local Tomcat/`am72db`), applied across the suite
    instead of per spec. Left on `:8899` on purpose: `apiBaseUrl.spec.js:40` (asserts the Vite topology) and
    `worldGroupPicker.e2e.spec.js:17,31,77` (asserts a URL shape). Not changed: any spec's assertions,
    selectors or fixtures — the 9 stale/never-valid tests found by the same triage are left for Stephen
    (§6.M follow-ups) under scope discipline.

### 6.G Found in Slice C transport (P0-10c), left alone under scope discipline — your call on each

None of these is in the P0-10c task; each is recorded here with where it was seen so nothing is lost.
Say which, if any, should be picked up in Phase 0.

1. **`PictureBookService.ensureSchema` (`:172-181`) has the same substring defect** that broke every
   ChapBook body carrying a nested `schema` (§2.4, §5.4 item 1): `trimmed.contains("\"schema\"")` is
   satisfied by a nested `sdConfig.schema`, so the top-level `olio.pictureBookRequest` injection is
   skipped and `parseParams` returns null. Not reproduced live on a PB route (no PB transport test was in
   scope); the Ux752 PictureBook screens nest `sdConfig` the same way `chapBook.js:812` does, so expect
   the same `Request body is required` 400 on any PB endpoint that receives a nested record. Same
   one-line fix (parse the root with Jackson); the P0-10c regression test shape
   (`TestNestedSchemaDoesNotSuppressTheRequestSchema`) transfers directly.
2. **`POST /rest/olio/chap-book/poem` and `POST …/set` accept fields `olio.pictureBookRequest` does not
   declare** (`sources`, `noteObjectId`, `dataObjectId`, …); `RecordDeserializer` silently drops any
   undeclared field, so a client typo yields "text is required" / "sources must contain at least one
   entry" with no hint that the field name was wrong. Pre-existing; surfaced while writing the reflective
   `parseBody` contract test. Either declare them on the request model or document the drop.
3. **`PictureBookService.escapeJson` (`:203-207`) does not escape control characters other than `\n`/`\r`**
   — a tab (CTRL-CHAR 9, present in any stack-trace-shaped message) produces an error body Jackson rejects.
   Found by the P0-10c contract test the moment `ChapBookService` mirrored it (§2.5 item 4, run 4).
   `ChapBookService` now serializes with Jackson; `PictureBookService` still uses the replace chain.
4. **Docker-stack `contentAnalysis` chatConfig → `http://litellm:4000` with `authToken=null`** → LiteLLM
   answers `HTTP 401 No api key passed in` on the landscape-prompt calls during ChapBook create; create
   falls back and succeeds, so the Playwright spec is green, but every LLM-assisted step in the Docker
   stack is currently silently degraded. Environment/config, not code — set the token on that
   `system.connection` (or point it at the Ollama container directly) in `am7test`.
   **Expanded 2026-10-10 — this is now the only thing keeping two Playwright tests red, and the repair is
   yours by classifier ruling.** Facts established on `am7test`: the shared chat **library** was initialized
   by `e2e/pictureBookFanOut409Reuse.spec.js` (`POST …/library/init`), which created library connection
   **id 9** = `http://litellm:4000`, model `gpt-5.6-terra`, **no API key** — `library/init` has no way to
   set one — plus library chatConfigs **9–15** pointing at it. `ChapBookUtil.resolveDefaultChatConfig`
   (`:356-381`) is **library-first** (`contentAnalysis` → `generalChat` → first library config) and only
   falls back to a user-owned config when the library has none, so the user-owned config a spec creates
   is **not** what a ChapBook render uses (stale comment in `chapBookConfigPanel.spec.js:165-168`
   corrected). Consequence: the two `[LLM]`-tagged `chapBookConfigPanel` tests (gated behind
   `CHAPBOOK_LLM_TESTS`) fail with `401 No api key passed in` until the library connection is usable.
   Two repair paths, **both denied by the auto-mode classifier** when I tried (writing a script that stores
   the LiteLLM key onto the admin-owned connection = "Secret-Store Write"; an admin-login script to PATCH
   connection 9 and configs 9–15 = "Auto-Mode Bypass"), so **not done and not to be retried by me**:
   (a) set `apiKey` + `dialect=OPENAI_COMPAT` + `upstream=OLLAMA` on connection 9 (the key is
   `LITELLM_MASTER_KEY`, `docker-compose.test.yml:549`, as `dockerDevSetup.md:995` prescribes for a
   `system.connection`); or (b) repoint connection 9 at `http://ollama:11434`
   with `dialect`/`upstream` `OLLAMA` and set the library configs' `model` to `qwen3:8b`; or (c) delete the
   library and re-init it after fixing what `library/init` seeds. Duplicate `e2e-chapbook-conn` records also
   exist in the shared user's `~/Chat` (harmless, `ensureChatConfig` now scopes by `groupId`).
5. **Per-request `new ObjectMapper()` at `ChapBookService` `:451`, `:508`, `:566`, `:616`, `:838`** —
   pre-existing, flagged by the security review as a hygiene item alongside the `ensureSchema` one
   that was fixed (§2.5 item 5). Trivial to switch to the new `SCHEMA_MAPPER`; not done because they
   are not P0-10c code.
6. **`PbConfigUtil.mergeTiers` `:374` strips only `SD_OVERRIDE_SKIP` from the render-time client overlay**
   (architect note during the P0-10d review, §2.6), so `description`/`imagePath`/`imageName` on a render
   body's `sdConfig` reach the effective config, while the stored-row path strips them via
   `BOOK_SD_CONFIG_SKIP`. Harmless today — P0-10d's `computeBookSdDelta` strips those client-side, and
   the overlay is transient (never persisted, never hashed, P0-10b) — but a non-Ux752 client could carry
   them. One-line fix (`applyOverrides(base, overlay, BOOK_SD_CONFIG_SKIP)`); not P0-10d code.
7. **`page.logout` (`pageClient.js:855`) routes to `/sig` without reloading, so every `chapBook.js`
   module-level memo survives a same-tab user switch** (security-review Low on P0-10d, §4). For the new
   `renderSdConfigInst` that means the next user who opens the same book objectId reuses the previous
   user's in-memory instance instead of a fresh PBAC'd `GET /settings`. Same exposure already exists for
   `reviewScenes`, `lastCreatedBook`, the poem library cache, and every other module-level `let` in the
   file. Either feature modules clear per-user state on logout (an event from `pageClient`) or logout
   reloads the page. Not P0-10d code; say which fix you want.
8. **`PictureBookUtil.java:10358-10360` writes `./comp-<sceneObjectId>.png` / `./land-<sceneObjectId>.png`
   into the process cwd in production** on the `openSceneGraph` failure branch (security-review Slices A/B,
   §4). In Tomcat that is `/opt/tomcat` (or wherever the JVM was launched) — generated images with a
   scene id in the name land on disk outside any vault/group, unauthorized by PBAC, never cleaned up.
   Pre-existing PB2 debug fallback, not Phase 0 code (P0-6 removed the sibling Kontext `./land-*.png`
   writer at `:10277`; this one is the composite path). Fix: drop the fallback, or route it through
   `FileUtil.emitFile` under the test-output dir only when a test-only system property is set.
9. **Raw `e.getMessage()` is persisted into `olio.pb.run.lastError` and scene `error`** (security-review
   Slices A/B, §4). Exception messages from the SD/LLM clients carry the upstream `host:port` and path
   (`Connection refused: 192.168.1.39:7801`, LiteLLM/Ollama URLs), which then surface in the review
   UI and any REST read of the run. `nodeModel.json:101` `lastError` has no `maxLength`, so a long
   stack-ish message is stored whole. Not Phase 0 code (P0-7 only ensured the closure fires). Fix:
   persist a sanitized/truncated message (strip URLs/hosts, cap at e.g. 512) and log the raw one.
10. **`SDUtil.newDataByNameQuery` (`SDUtil.java:1084`, callers `:774`, `:1290`) reads `data.data` rows with
    raw `getSearch().findRecord`** — no `AccessPoint` — and after P0-9 the projection includes `keyId`,
    `vaultId` and `byteStore` so `ByteModelUtil` can write the raw store (security-review Slices A/B,
    §4). The group is the user's own PB library group and the write is PBAC-authorized upstream, so
    this is not a bypass today, but the lookup now returns vault identity fields unauthorized. Not
    Phase 0 code (the projection change is; the raw lookup is pre-existing). Fix: route the lookup
    through `AccessPoint.find(user, q)`, or keep raw but pass the user and assert `canRead`.
11. **`promptTemplate` override on `PictureBookService` landscape / extract-scenes
    (`PictureBookService.java:278`, `:712`, `:765`) has no allowlist** (security-review Slices A/B,
    §4). An authenticated user can name any prompt template resource the server can resolve, not only
    the `pictureBook.*` ones. Pre-existing; Phase 0 didn't touch it. Fix: reject at Service7 unless the
    name starts with `pictureBook.` (transport-level input validation, no business logic).
12. **`TestFlux2Composite.java:685` falls back to a literal `http://192.168.1.42:7801`** when
    `test.sd.server` is empty (security-review Slices A/B, §4). Test-only; `.42` is the LLM box, and
    `reference-sd-llm-hardware` says SD tests use `.39` — so the fallback both hardcodes a LAN host
    and points at the wrong one. Pre-existing. Fix: fail the test with a clear message instead of
    falling back.
13. **`Chat.generateSceneDescription` loses the first exchange when `assist=false`** (found 2026-10-10 by
    the live `generateScene` e2e, §2.8). `Chat.java:2322-2327` → `ChatUtil.getFormattedChatHistoryFrom(…,
    pruneSkip + 2)` (`ChatUtil.java:2618-2619`) starts at index 3, which assumes the assist-template layout
    (system, user-template, assistant-template); with the chatConfig schema default `assist=false` the
    session after one turn is system(0)+user(1)+assistant(2), the formatted history is empty, and the scene
    description silently becomes `"two people facing each other"` **with no LLM call** — the user's first
    `generateScene` after a short chat ignores the chat. Pre-existing, outside Phase 0 scope. Fix: compute
    the start index from the actual prompt layout (`assist ? pruneSkip + 2 : pruneSkip`) or from the first
    non-system message. The e2e works around it with two turns and asserts the fallback text is absent.
14. **`GET /rest/model/cleanup` (`ModelService.java:54-65`, `@RolesAllowed("user")`) runs `VACUUM FULL`
    for any authenticated user.** Found while tracing the §6.J deadlock (three concurrent `vacuum(full,
    analyze, verbose)` from parallel specs). An ordinary user can take an `AccessExclusiveLock` on every
    table in the deployment at will — a denial-of-service surface, not a test issue. The e2e helpers no
    longer call it (§4.2 item 2); the route itself is yours: restrict to `admin`, or remove it.
15. **Leftover e2e users on `am7test`:** several `e2etest_pbmv*` — `pictureBookLive.spec.js:60`'s per-run
    user, `setupWorkflowTestData(request, { suffix: 'pb' + Date.now().toString(36) })` (a 2026 epoch in
    base-36 starts `mv…`), whose `afterAll` cleanup did not complete in the gate runs that overlapped the §6.J
    outages — and duplicate `e2e-chapbook-conn` records in the shared user's `~/Chat`. Harmless; left in
    place (fixture hygiene, not Phase 0 code). Say if you want a sweep.
16. **`charGallery.spec.js` test 1 passes while logging `Images tab not visible — charPerson form may not
    have loaded`** (seen in the §4.2 split verification). A soft assertion that cannot fail the test;
    pre-existing, not changed.

### 6.L Question for Stephen — the `generateScene` e2e fixtures render nude (found 2026-10-10, §2.8)

`Mira SceneE2E` / `Theo SceneE2E` are bare `olio.charPerson` rows (name, gender, age 30, race `E`) created by
the spec because the shared user's `~/Characters` had nothing populated. With no narrative and no apparel,
`NarrativeUtil.getSDMinPrompt` (`:1057-1105`) emits `(null) (null hair)` and `describeOutfit` (`:341-347`)
emits `"naked/nude, wearing no clothes"`, so every scene the test renders — and every portrait item 15
renders for them — is nude. The pipeline is behaving as designed for an undressed character; the fixture is
the problem. Options, your call (⚠ MY JUDGMENT would be 1):
1. **Dress the fixtures in the spec** — give each a minimal `store.apparel` (or at least a narrative
   `outfitDescription`) via the same REST calls the PictureBook cast path uses; one-off per stack, idempotent.
2. **Reuse a populated PictureBook cast character** from an existing book on the stack — depends on the
   stack having one, which a fresh stack does not.
3. **Leave it** — the assertions do not look at the figures, only at the pipeline and the dimensions.
Until decided the test stays as is; the two PNGs are described in §2.8.

### 6.H BLOCKED — Service7 module gate RED on a broken local fixture: H2 org 4 (`/Development/MediaUtilStreamTest`) has no keystores on disk (found 2026-10-09)

`TestMediaUtilStreaming.TestWriteBinaryDataStreamsLargeStreamBackedFileWithoutFullBuffer` (KI-22's
end-to-end test, committed 2026-07-14) errors deterministically in the Service7 gate — full evidence in
§4.1. The organization row exists in the gitignored `src/AccountManagerService7/am7/h2.mv.db`, but
`src/AccountManagerService7/am7/.jks/4/{keystore,truststore}.jks` do not exist, and the only code that
writes those files runs at organization creation (`Factory.configureOrganizationStore`). Nothing in this
session's diff touches organizations, keystores, the vault or `MediaUtil`; the Slice C classes in the same
run are green (`TestChapBookRestContract` 8/8, `TestPictureBookRestContract` 14/14). It still blocks
`VERIFY_OK` for Service7, and under the no-closing-out-as-pre-existing rule it is listed here as open
rather than dismissed. **I have not changed the fixture; the choice is yours** — ⚠ MY JUDGMENT would be
option 1, and it is a one-off, so I did not take it:

1. **Regenerate org 4's keystores from the DB state** — the ops user's `"MediaUtilStreamTest Certificate
   Authority Certificate"` `KeyStoreBean` and its `auth.credential` (the store password) are still in H2, so
   `configureOrganizationStore`'s body minus the random password + credential create would rebuild
   `keystore.jks`/`truststore.jks`. A one-off; nothing in product code needs to change.
2. **Delete org 4's rows from the H2 fixture** so the test recreates it (and `.jks/4/`) on its next run.
   Data-destructive on a test fixture; I will not do it unprompted (`never-reset-db-schema` culture).
3. **Rebuild the H2 fixture yourself** (delete `am7/h2.mv.db` + `am7/.jks/`; orgs 1–3 and 5 would be
   recreated by the next run — `TestPrincipalProfileCache` creates 5). Same destructiveness as 2, wider.
4. **Leave it red for Phase 0** and treat the Service7 gate as green-minus-this-fixture. Your call; the
   brief says `VERIFY_OK` or it is not done, so I am not assuming this.

Related hygiene, not acted on: `target/surefire-reports/…TestProbeBlankName.txt` (2026-10-07) is a stale
report for a class that no longer exists in the tree — harmless, surefire ignores it.

### 6.I RESOLVED 2026-10-10 (was BLOCKED) — the Docker test stack's shared-user fixture is damaged: `e2etest_shared` no longer owns its home tree, so every ChapBook Playwright spec fails (found 2026-10-10 in the full gate)

**Stephen, 2026-10-10: "You have full control over docker stack, so you may reset it, and/or create another fresh
test user."** Done (option 1 as a one-off admin fixture repair — the only admin use this session, never for a test — plus
option 3; the DB was **not** reset, the §6.J volume move carried the data across):
- As admin: deleted the orphaned `/home/e2etest_shared` group tree (11667/11668 and children), the stale
  `auth.permission`/`identity.person` rows named `e2etest_shared`, and user 134; one `GET /rest/model/cleanup`;
  then `ensureSharedTestUser()` recreated the user and a user-login `findPath('/home/e2etest_shared')` rebuilt the
  home tree. Verified read-only in `am7-pg`: `e2etest_shared` is **id 228**, objectId
  `97f13143-f4ee-47aa-aeee-a419dc25ebd3`, home group **12055** (owner 6 — every `e2etest_*` home group on this stack
  is admin-owned, so that is the provisioning norm, not damage), and the groups beneath it — `Poems` 12056, `Data`
  12217, `.preferences` 12218, `Chat` 12621 — are **owned by 228**, which is what the ChapBook specs need.
- **Root cause removed:** `e2e/bugfixSprint2.spec.js` no longer has the `afterAll` that deleted the shared user
  (comment at `:12` now states the shared user is persistent and only its per-test `wf` user is cleaned).
- **Belt and braces in `e2e/helpers/api.js`:** `PERSISTENT_USERS` (`:934` — shared, no-role, admin-role, ISO users)
  makes `cleanupTestUser` (`:276`) refuse to delete them even if a spec asks; `cleanupTestUserFull` (`:330-340`) no
  longer calls `GET /rest/model/cleanup` (the `VACUUM FULL` behind the §6.J deadlock, now §6.G item 14); name
  lookups that are not unique across users (`ensureChatConfig`'s connection/chatConfig, `:684-755`) are scoped by
  `groupId` so a duplicate record in another user's group can no longer make them return null.
- Option 4 (`ensurePoem` loud failure on an empty 200) **not** done — the helper is unchanged; the empty-body
  200 from `ModelService.createModel` on a denied create (`ModelService.java:259-310`) is still a product issue
  worth a 403/409 (§6.G item 2's neighbour).
- Proof: every ChapBook/scene Playwright run since — `chatChainSceneReimageRoutes` live `10 passed` ×3 (§2.8), the
  `chromium`/`chromium-vite` split `8 passed (51.9s)` (§4.2) — ran as this user; the second full gate (§4.2) is the
  suite-wide proof.

What happened (full evidence and verbatim log lines in §4.1 "Root causes", item 1): during the full gate's
4-worker e2e phase, `e2e/bugfixSprint2.spec.js` ran its `afterAll` —
```js
test.beforeAll(async ({ request }) => { testInfo = await ensureSharedTestUser(request); });
test.afterAll(async ({ request }) => { await cleanupTestUser(request, testInfo.user?.objectId, { userName: testInfo.testUserName }); });
```
(`bugfixSprint2.spec.js:12-18`; the only spec under `e2e/` that tears down the user it `ensureSharedTestUser`'d —
grep-verified) — and `cleanupTestUser` (`api.js:255-297`) deleted the persistent shared user **and its home group
tree** as admin at 08:40:17Z, while 3 other workers were mid-spec on that user. `ensureSharedTestUser` recreated
the user 0.3 s later with a new id, then nine more times at 09:08:45–59Z (parallel callers racing after the first
Postgres recovery). Result, verified read-only in `am7-pg` (org 2):

| object | before the gate | now |
|---|---|---|
| `system.user e2etest_shared` | id 19 | **id 134** (19 → 50 → 134) |
| `/home/e2etest_shared` | group 10687, owner 19 | **group 11667, owner 6 (`admin`)** |
| `/home/e2etest_shared/Poems` | group 10697, owner 19 | **group 11668, owner 50 (a deleted user)** |

So the live user is denied `ADD olio.cb.poem` into its own `~/Poems` (`AUDIT DENY system.user e2etest_shared to ADD
olio.cb.poem …`), `ModelService.createModel` turns the null create into **HTTP 200 with an empty body**
(`ModelService.java:259-310`), and `ensurePoem` (`e2e/helpers/chapbook.js:147`) dies on `r.json()` —
`SyntaxError: Unexpected end of JSON input`. Measured after the gate with the stack idle and all six service probes
green (§3 04:34:46): `chapBookBookSettings.spec.js` serial, chromium only → **`8 failed`**
(`C:\tmp\pw-p012-postgate-bookSettings.log`). The same spec passed `12 passed / 2 skipped` with its two siblings at
01:44 (`C:\tmp\pw-p012-chapbook-3specs.log`). **No Phase 0 code changed between those two runs.**

**I have not touched the stack's user/group data, the spec, or the helpers** — ⚠ MY JUDGMENT on all of these,
so they are your call:

1. **Repair the fixture** (one-off, admin): re-own groups 11667 and 11668 to user 134 (or delete the orphaned
   `/home/e2etest_shared` tree and let `ensureSharedTestUser` → login rebuild the home dir). Cheapest; keeps every
   other fixture in the DB. I would do this if you say so.
2. **Reset the test DB yourself** (`am7-pg`'s `am72db` for the Docker stack — the resettable one, not the local
   Tomcat's) and re-run initial setup per `dockerDevSetup.md`. Also clears the WAL-trouble history in §6.J.
3. **Fix `bugfixSprint2.spec.js`** so it stops deleting the shared user — e.g. create and clean up its own
   `wf`-style per-spec user as its line 271 already does for `setupWorkflowTestData`, or drop the `afterAll`.
   Out of Phase 0 scope (`feedback-scope-discipline`), and it is the root cause of the fixture damage every time the
   full suite runs, so the full gate cannot be green until it is changed. A separate, cheap task if you want it.
4. **Make `ensurePoem` fail loudly on an empty 200** (`chapbook.js:147`: check `(await r.text()).length` before
   `r.json()`, or treat an empty body as a create failure with the audit hint). Helper-only, would not have
   prevented the failure but would have named it in the first line of every trace. Also optional/out of scope.

Until 1 or 2 is done, **no ChapBook Playwright spec (Phase 0's or any earlier one) can pass against this stack**;
the Phase 0 P0-10d/P0-11 Playwright evidence stands on the 23:11 / 01:44 log-backed runs (§2.6, §2.7).

### 6.J RESOLVED 2026-10-10 (was waiting on Stephen) — `am7-pg` (Docker test stack) PANIC'd twice during the full gate's e2e phase on WAL writes, plus a three-way `VACUUM FULL` deadlock from `/rest/model/cleanup` (found 2026-10-10)

**Stephen, 2026-10-10: "Check docker settings (am7-docker-up.bat/sh for how the mount is set - lets changre/fix
it if needed."** Root cause and fix:
- `docker-compose.test.yml` mounted the Postgres data directory from the host as a **bind mount**
  (`${AM7_DATA_DIR:-./docker-data}/pg:/var/lib/postgresql/data`). On Docker Desktop for Windows a bind mount is
  a 9p/gRPC-FUSE share, and Postgres WAL writes through it are what produced `PANIC: could not write to file
  "pg_wal/xlogtemp.…": Interrupted system call` and the recurring `could not open file "pg_wal/…": No such file
  or directory` checkpointer lines — same class as §6.D on `postgres3` if that one also sits on a Docker Desktop
  bind mount. Not load per se: load only made it frequent.
- **Fix applied:** the data directory is now the Docker **named volume `am7test_am7-test-pg`**
  (`docker-compose.test.yml:156` `- am7-test-pg:/var/lib/postgresql/data`, declared at `:991`; header comment
  `:15-35` and the service comment `:142-148` explain why and how to reset). The existing cluster was copied in
  with `docker run --rm --volumes-from am7-pg -v am7test_am7-test-pg:/to alpine sh -c 'cp -a
  /var/lib/postgresql/data/. /to/'` (am7-pg stopped), so **no data was lost** (ids continued — §6.I); the old
  `./docker-data/pg` directory is no longer read (left on disk for Stephen to delete). The ARM64 note in
  `am7-docker-up.sh:77-80` now names the volume; the only other up-script change is the `:8898` CORS origins
  (`am7-docker-up.bat:146` / `.sh:206`, §6.K). New `am7-pg` container started 15:55:51Z (10:55 local) and
  `docker inspect` shows `volume:am7test_am7-test-pg → /var/lib/postgresql/data`; `docker logs --since
  2026-10-10T15:00:00Z am7-pg | grep -c "PANIC\|pg_wal"` → **0** as of 12:50 local, through the live
  `generateScene` runs, the split verification and the second full gate's backend phases. Docs: `dockerDevSetup.md`
  §7 (down vs full reset, `docker volume rm am7test_am7-test-pg`, matched-pair warning), its storage map, and
  `DockerComposeDesign.md`'s persistence/reset paragraphs.
- **The deadlock side:** the e2e helpers no longer call `GET /rest/model/cleanup` (§6.I); the route itself being
  user-callable `VACUUM FULL` is §6.G item 14 for you.
- **Not done:** `postgres3`/`am7db` (§6.D) is Stephen's box and was not touched; if its data directory is also a
  bind mount the same move applies.

Verbatim `docker logs am7-pg` lines are in §4.1 "Root causes", item 2. Summary of the original finding:

- **08:48:21Z** `PANIC: could not write to file "pg_wal/xlogtemp.182248": Interrupted system call` on a `COMMIT` →
  all backends killed → crash recovery (`redo done at 2/59FBAB38 … elapsed: 361.60 s`) → ready **08:57:01Z**.
  ~9 minutes with no database, mid-suite.
- **09:20:45Z** `ERROR: deadlock detected` ×3 — three concurrent `vacuum(full, analyze, verbose)` backends waiting on
  each other (`relation 1214 of database 0` = `pg_shdepend`, `relation 1247 of database 16384` = `pg_type`). That
  statement is `GET /rest/model/cleanup`, which `cleanupTestUserFull()` (`api.js:299-311`) calls at the end of
  several specs; with 4 workers they overlap. One second later, **09:20:46Z** `PANIC: could not write to file
  "pg_wal/xlogtemp.210058": Interrupted system call` on another `COMMIT` → recovery (`redo done at 3/6BFEE798 …
  elapsed: 463.34 s`) → ready **09:30:47Z** — seven minutes **after** the gate had already finished (09:23:47Z),
  which is why the gate reported `222 did not run` and the container was `unhealthy` at 09:30:02Z.
- Recurring, outside the gate too: `could not open file "pg_wal/0000000100000001000000DA": No such file or directory`
  / `could not remove file …` pairs from backend `[30]` (the checkpointer) at 2026-10-09 05:59Z and 2026-10-10 03:07Z,
  04:38Z, 04:53Z, 06:53Z, 09:08Z, 09:13Z.

This is the Docker stack's database (`am7-pg`, volume-backed), not `postgres3`/`am7db` — but it is the **same
symptom class as §6.D** (`postgres3` PANIC during module-gate run 1 on 2026-10-09): a WAL write interrupted by a
signal under heavy concurrent load on Docker Desktop's bind/volume layer. I have no fix to propose that is mine to
make; candidates are all infra: run the gate's e2e at `--workers=1` (or 2) against one stack, stop calling
`/rest/model/cleanup` (`VACUUM FULL`) from parallel specs, check the `am7-data` volume / WSL2 `ext4.vhdx` for the
`pg_wal` errors (`feedback-docker-vhdx-host-disk-testauthorization`), or move `pg_wal` off the volume type that is
producing `EINTR`. Host disk was fine throughout (`df -h /c` 49–50 %). **Nothing in Phase 0's diff touches the DB
layer or runs `VACUUM`.**

### 6.K RESOLVED 2026-10-10 (was waiting on Stephen) — the full gate's Ux752 e2e phase cannot be green against the Docker stack as configured (found 2026-10-10)

**Stephen, 2026-10-10: "Then use Chrome. These are your own tests and test conditions from prior runs, so
improvise."** Applied, in order of the three items below:
1. **Firefox** — the `firefox` project is now **opt-in** (`playwright.config.js:20` `INCLUDE_FIREFOX =
   process.env.PLAYWRIGHT_FIREFOX === '1'`), so the gate's unchanged bare `npx playwright test`
   (`.claude/loop/detect.sh:81`) runs `chromium` + `chromium-vite` only; anyone who installs Firefox sets
   `PLAYWRIGHT_FIREFOX=1`. Default workers **2** (`:22-25`, `PLAYWRIGHT_WORKERS` or the CLI flag override).
   `verify.sh`/`detect.sh` untouched. No test was removed.
2. **Vite-source specs** — ⚠ MY JUDGMENT under "improvise", recorded here: rather than rewrite 23 specs, the
   split is at config level. `playwright.config.js` defines `VITE_SOURCE_SPECS` (the 23 files found by
   `rg -l "import\(['\"]/src/" e2e/*.spec.js`), a `chromium-vite` project whose `testMatch` is that list and
   whose `baseURL` is `PLAYWRIGHT_VITE_URL || (PLAYWRIGHT_BASE_URL set ? 'https://127.0.0.1:8898' : BASE_URL)`,
   and `testIgnore`s the same list on `chromium`/`firefox`. The 8898 server is a second Vite started with
   `AM7_BACKEND=https://127.0.0.1:9443 npx vite --port 8898` (`vite.config.js:12,17` honours `AM7_BACKEND`)
   — it cannot share `:8899` because `src/core/config.js:18-21` points any page served from port 8899 at the
   **local** Tomcat `https://localhost:8443` (the forbidden `am72db`), so a Docker-targeted run on 8899 would
   silently test the wrong backend. `https://localhost:8898,https://127.0.0.1:8898` added to
   `CORS_ALLOWED_ORIGINS` (`docker-compose.test.yml`, `am7-docker-up.bat:146`, `.sh:206`). `--list` under the
   gate env: `chromium` 473 tests, `chromium-vite` 131 tests / 23 files. With `PLAYWRIGHT_BASE_URL` unset both
   projects use `:8899` exactly as before, so local developer runs are unchanged. One spec needed a fix to be
   safe under the split: `sdConfigLandscapePersist.spec.js` hardcoded `https://localhost:8899` for its REST
   read-back/cleanup, which under `chromium-vite` would have sent the Docker session cookie (host-scoped,
   port-agnostic) to the local Tomcat — now relative to `test.info().project.use.baseURL`.
   `worldGroupPicker.e2e.spec.js`'s `:8443 → :8899` route rewrite is a no-op on 8898 (config.js uses a
   relative path there) and was left alone. Runbook: `dockerDevSetup.md` §9 "Vite-source specs: the
   `chromium-vite` project and the 8898 dev server (Path C)". **Caveat:** `--project=chromium` alone now
   silently skips those 23 specs — the runbook says so.
3. **Parallelism** — 2 workers; plus the §6.I/§6.J helper changes (no shared-user teardown, no `VACUUM FULL`)
   that removed the two failure modes 4 workers exposed.
Proof so far: §4.2 split verification `8 passed (51.9s)` across both projects; the second full gate (§4.2) is the
suite-wide proof.

Original finding — three suite/environment facts that accounted for most of the 596 e2e failures (classification
in §4.1), none Phase 0 code:

1. **Firefox is not installed** — `browserType.launch: Executable doesn't exist at
   C:\Users\scote\AppData\Local\ms-playwright\firefox-1509\…` × **364**. The gate runs the bare `npx playwright test`
   (both projects), so the firefox half of the suite is guaranteed red on this box. Either `npx playwright install
   firefox` or make the gate's e2e command `--project=chromium` (`.claude/loop/verify.sh` / the Ux752 e2e hook).
2. **~41 chromium specs `import()` Vite source paths** (`https://127.0.0.1:9443/src/core/am7client.js` ×29,
   `/src/core/config.js` ×5, `/src/core/model.js`, `/src/chat/LLMConnect…`, `/src/core/pageClient…`,
   `/src/cardGame/…`) — those only exist on the Vite dev server (`:8899`), not in the nginx-served `dist` on
   `:9443`. They need `PLAYWRIGHT_BASE_URL=https://127.0.0.1:8899` (the Vite proxy) or a rewrite to bundle-safe
   access; the brief's §5 mandates `PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443` for the Docker stack, so I did not
   switch the base URL.
3. **4 workers against one stack** is what produced the §6.I user-recreate race and the §6.J `VACUUM FULL` deadlock.
   `--workers=1` is already the rule for LLM/SD specs; extending it (or `--workers=2`) to the gate's e2e command is
   the smallest change that removes both failure modes.

Also noted, unclassified and not investigated: `ENOENT … media\faceTes…` (a missing local media fixture),
`base() should include the app server host:port` / `all REST calls should target the app server on 8443` (specs
asserting the pre-Docker `:8443` host), `MCP initialize should succeed`, `ISO Tester create should succeed (200)`,
`create without an ISO role must be denied, got 500`, `book row STILL present in UX list AFTER delete`. Each is one
test; none is in a Phase 0 spec.

### 6.M FIXED 2026-10-10 — the client was sending a retired SDXL checkpoint name to Swarm (Stephen: "You are sending wrong sdXL model ref to server - you need to check models")

**Stephen's instruction (2026-10-10, verbatim):** "You are sending wrong sdXL model ref to server - you need to
check models".

**What was sent.** `docker logs am7test-am7-1` at 20:39:43Z (15:39 local, during the §4.2 gate's e2e phase):
`SDUtil - Generating image for Seqtest Charmv2uy9vr` → `txt2img sdConfig: model=sdXL_v10VAEFix.safetensors steps=10
cfgScale=5.0 sampler=dpmpp_2m scheduler=karras width=512 height=512 refinerModel=null` → Swarm:
`Invalid value for parameter Model: Invalid model value for param Model - 'sdXL_v10VAEFix.safetensors' - are you sure
that model name is correct?` → retried once on a fresh session, same error → `No images returned in response`. That
checkpoint is not installed on `.39`; the 13 installed Stable-Diffusion checkpoints (`GET /rest/olio/sdModels`, i.e.
Swarm `/API/ListModels`) are `OfficialStableDiffusion/sd_xl_base_1.0.safetensors`,
`ZImage/SwarmUI_Z-Image-Turbo-FP8Mix.safetensors`, `chilloutmix_Ni`, `chroma_v10HD`, `chromagenIL_somni`,
`cyberrealisticPony_v160`, `flux2Klein_9b`, `flux2_dev`, `juggernautXL_ragnarokBy`,
`krea2TurboOfficialComfy_krea2RawInt8Convrot`, `lustifySDXLNSFW_endgame`, `ponyRealism_V22`, `realmixXL_v10`
(all `.safetensors`).

**Where it came from — the client, not the server or the DB.** `src/AccountManagerUx752/src/core/modelDef.js` is the
hand-curated client mirror of the server model JSON (its header says edit by hand to mirror
`AccountManagerObjects7/src/main/resources/models/<domain>/*.json`; never regenerate from `/rest/schema`). Its
`olio.sd.config` block declared `model` default `"sdXL_v10VAEFix.safetensors"` and **invented** a `refinerModel` default
`"juggernautXL_ragnarokBy.safetensors"` — the server (`olio/sd/configModel.json`) declares `model` default
`OfficialStableDiffusion/sd_xl_base_1.0.safetensors` and **no** `refinerModel` default. `am7model.newPrimitive('olio.sd.config')`
seeds those client defaults into every config the client builds without a user model pick — callers:
`cardGame/services/artPipeline.js:257`, `chat/imageTokens.js:169`, `chat/SceneGenerator.js:124`,
`features/chapBook.js:859,3375`, `workflows/pictureBook.js:283,1237`, `workflows/reimage.js:124`,
`workflows/reimageApparel.js:24`, `e2e/apparelSequence.spec.js`. The server honours whatever model the request carries:
`SDUtil.resolveModel(cfgModel)` (`SDUtil.java:1927`) falls back to `getDefaultModel()` (`sd.default.model` init-param,
else the schema default) **only when the field is blank**, and `OlioService.reimageWithConfig`
(`OlioService.java:172-273`) applies the `sd.model` / `sd.refinerModel` init-params only when the field is null, then
returns `200 "{}"` when the portrait is null after generation (`:267-270`). No bad name is persisted anywhere:
`a7_olio_sd_config_0_1.model` on the stack's `am72db` is `OfficialStableDiffusion/sd_xl_base_1.0.safetensors` ×45,
`juggernautXL_ragnarokBy` ×15, `ponyRealism_V22` ×4 (refiner empty ×60, juggernaut ×4); on Objects7's `am7db`
`OfficialStableDiffusion/…` ×59, refiner empty ×59.

**Why the gate did not catch it.** The spec that sent it, `e2e/apparelSequence.spec.js`, had **no `expect` at all** —
it `console.log`ged "VERIFIED … image created: undefined" and passed (`ok 554` in the §4.2 gate) while the server
refused the model and answered `{}`. A fake green, exactly the class `llm-conduct.md` rule 1 forbids; it was written
before Phase 0 and is in the suite the gate runs, so it is mine to fix.

**Fix (all Ux752; no backend change):**
1. `src/core/modelDef.js` `olio.sd.config`: `model` default → `"OfficialStableDiffusion/sd_xl_base_1.0.safetensors"`
   (✓ FOLLOWING INSTRUCTIONS — mirror the server); `refinerModel` default **removed** (⚠ MY JUDGMENT, same defect
   class: the server declares none, and a null refiner is safe by design — `SDUtil.java:1241-1245` only uses the
   refiner when `hires && refinerModel` is non-blank, else WARNs "hires requested but no refinerModel configured;
   skipping refiner"). `grep -c sdXL_v10VAEFix modelDef.js` → 0. **Timing:** this edit landed ~16:19 local while the
   §4.2 gate's `chromium-vite` phase was still running; that project serves `src/` live from Vite `:8898`, so any
   `chromium-vite` test after 16:19 used the new default (the `[chromium]` project serves the baked `dist`, unaffected).
2. **New Vitest `src/test/sdConfigModelParity.test.js`** (3 tests) reads the real server JSON off disk and asserts the
   client block has the same field-name set, agrees on `type/default/minValue/maxValue/baseModel/foreign` for every
   field (diff list in the failure message), and that every `*Model` default `newPrimitive` seeds equals the server
   default or is `null` where the server declares none. Proven red against the old value (2 failures:
   `model.default: server="OfficialStableDiffusion/…" client="sdXL_v10VAEFix.safetensors"`,
   `refinerModel.default: server=undefined client="juggernautXL_…"`), then `Tests 3 passed (3)`. Full suite after:
   `Test Files 82 passed (82) / Tests 1010 passed (1010)`; `vite build ✓ 6.75s`.
3. **`e2e/apparelSequence.spec.js` rewritten** as a real test (`chromium-vite` project, shared user, no admin): logs in
   via `login()`, `GET /rest/olio/sdModels` and asserts the list is non-empty, builds the character in-page exactly as
   before and `am7model.newPrimitive('olio.sd.config')` (10 steps / cfg 5 / 512×512 / `hires=false`), then **asserts
   the client's `model` (and `refinerModel` if set) is in the installed list before sending** — the "check models"
   step — `POST …/olio.charPerson/{id}/reimage` from Node (`page.request`, 280 s timeout, `test.setTimeout(300000)`),
   asserts `ok()` **and** `objectId` on the returned portrait (a refused generation is `200 {}` and must fail), fetches
   the bytes from `/AccountManagerService7/media/<org>/data.data<groupPath>/<name>`, asserts the PNG magic + IHDR
   dimensions, writes it to `test.info().outputPath('apparelSequence-portrait.png')` and attaches it; `durationMs` is
   logged, never asserted.
4. `dist` rebuilt and `docker cp`'d into `am7test-am7-1:/opt/ux752/dist/` (the container's `assets/` had 60 orphaned
   chunks from earlier copies, one still carrying the old literal though unreferenced by `index.html`; `assets/`
   was removed and recopied — now 153 files, identical `index.html` md5 `0af990e7…`, 0 hits for the old literal).

**Live result 2026-10-10 16:25–16:26 local** (services probed 16:25:15 — all GREEN, `C:\tmp\apparelSequence-run1.log`),
`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test e2e/apparelSequence.spec.js --workers=1
--project=chromium-vite --retries=0`:
```
[sd-models] 13 installed: OfficialStableDiffusion/sd_xl_base_1.0.safetensors, ZImage/SwarmUI_Z-Image-Turbo-FP8Mix.safetensors, …
[character] Seqtest Charmv2wlm0w (894be299-1e21-4fa5-8a67-3744cff64480)
[sd-config] model=OfficialStableDiffusion/sd_xl_base_1.0.safetensors refinerModel=null steps=10 cfg=5 512x512
[reimage] durationMs=24838 -> {"objectId":"ec7d9518-a763-4f1c-9bdf-785c2c849c03","name":"Seqtest Charmv2wlm0w - Photo Op - 1 - 2134094033 - 856695004","groupPath":"/Olio/Universes/My Grid Universe/Worlds/My Grid World/Gallery/Characters/Seqtest Charmv2wlm0w"}
[portrait] 512x512 (442589 bytes) -> …\e2e\results\apparelSequence-…-chromium-vite\apparelSequence-portrait.png
  ok 1 [chromium-vite] › e2e\apparelSequence.spec.js:29:5 › … › generate one image via /reimage for test character (27.4s)
  1 passed (31.3s)
```
Container log for that request: `SDUtil - txt2img sdConfig: model=OfficialStableDiffusion/sd_xl_base_1.0.safetensors
steps=10 cfgScale=5.0 sampler=dpmpp_2m scheduler=karras width=512 height=512 images=1 seed=856695004 refinerModel=null`
— no Swarm error. PNG opened: a full-body portrait of a woman with large curly hair and sunglasses, grey hoodie, black
leather leggings, tan lace-up boots, standing on a blue floor in an industrial interior — a real SDXL render.

**Left alone (follow-ups, not fixed):** `/reimage` answering `200 "{}"` on a failed generation (`OlioService.java:267-270`
— a client cannot tell "refused" from "ok"; a 502/409 with the SD error would be honest); the remaining
`sdXL_v10VAEFix` literals, none on a live path — `SDUtil.java:67` (`modelCheckpoint` field, getter/setter only),
`Auto1111Util.java:11` (A1111 API path), the JUnit `getProperty("test.swarm.model", "sdXL_v10VAEFix.safetensors")`
fallbacks in `TestPictureBookService:337`, `TestPictureBookPipeline:121`, `TestPictureBookUtilE2E:427`,
`TestPbChapterBookApparelRenderE2E:243`, `TestPbSeriesMergeImagePrompt:222` (masked by `SdTestGate`'s write-back,
the KI-39 fix), deprecated Ux7 `formDef.js`/`dialog.js`. The other seven `newPrimitive('olio.sd.config')` callers are
fixed by the mirror change alone (they take the default from `modelDef.js`), but only `apparelSequence` and the §2.8
`chatChainSceneReimageRoutes` live describe exercise the default end-to-end; the other spec that generates through
`/reimage` with a client-built config, `[chromium-vite] bugfix3-live.spec.js:93` (`am7imageTokens.resolve()`), was
among the §4.2 failures (it ran before the 16:19 edit, so it sent the old name) and was re-run in the §4.3 triage —
**it still fails, for an unrelated reason that never reaches SD** (next paragraph), so it neither confirms nor
contradicts the fix; `apparelSequence` and the §2.8 live describe remain the proof.

**Follow-ups found by the §4.3 triage, left for Stephen (none is in a Phase 0 task):**
1. **`OlioService.reimageWithConfig` NPE → 500** (`OlioService.java:266`): `BaseRecord oi = a1.get("profile.portrait");`
   runs outside the `if(a1 != null)` guard (`:244-265`), so when `AccessPoint.find` returns null (record absent **or**
   `AUDIT DENY` for the caller, which is what `bugfix3-live:93` hits as `e2etest_shared` reading another user's
   charPerson) the route answers 500 with an empty body instead of 404/403. One-line guard; same method as the
   `200 "{}"` item above, so they should be fixed together.
2. **Stale / never-valid Ux752 e2e specs** (dated evidence in §4.3): `charPerson.spec.js:51` and `picker.spec.js:137`
   (text-button selectors from before the 2026-03-27 icon-button picker), `charPerson:258` (schema-free `/reimage`
   body), `workflowRuntime:139` (`forms.data.reimage` removed 2026-03-28) and `:222` (number-input ordinal),
   `listNavigation:103`/`:207` (strict-mode duplicate "apps" button), `breadcrumb:133` (`.breadcrumb-expand-btn`
   never existed), `bugfix3-live:275` (`/audio/synthesize` never existed — it is `/rest/voice/{name}`) and `:509`
   (`button[title="Send"]` never existed), `pictureBookLive` C.2/C.3 (inline document names vs the 2026-03-30
   "Browse Notes"/"Browse Documents" picker buttons) and H.1 (inline blurb `textarea` vs the 2026-03-31 "Edit Book"
   wizard reopen), `sdConfigFlow:243` (`:306` demands a wizard dialog on the auto-load path that `:304-305` already
   accepted). Each needs its selector/expectation brought up to the current UI, or deletion.
   **Fixture-selection class** (the same defect three times — `bugfix3-live:93`, `pictureBookLive` L.1 `:1754`,
   `sdConfigFlow:134` `:189`): the test picks "any record" from an unfiltered org-wide list and then reads it by id;
   `AccessPoint.list` is not a per-record boundary, so the by-id read is `AUDIT DENY`. Fix is to scope the first query
   to the test user (`ownerId` / `groupId` of `setupWorkflowTestData`'s records), not to relax the assertion.
3. **Design question — `listNavigation:155`:** closing the full-screen carousel with Escape leaves the list in
   GridPreview mode (`list.js:1228` clears only `gridFullView`); the test expects the table. Your call whether
   `onClose` should also reset `gridMode`.
4. **Missing fixture** `e2e/media/faceTest.jpg` (`biometrics.spec.js:86`).
5. **`webauthn.spec.js:112`** fails under the `127.0.0.1` base URL (`SecurityError: This is an invalid domain`);
   it needs the `--host-resolver-rules=MAP localhost 127.0.0.1` chromium arg (troubleshooting.md item 1) so the page
   origin can stay `localhost` — a config-wide change I did not make.
6. **`testHarness.spec.js`** ×3 asserts a `devOnly` button the production `dist` never renders; it can only pass
   against a dev build.

## 7. Phase 1 start prompt (drafted 2026-10-10 while the §4.2 gate ran; **valid as of 22:35** — §4.2 + §4.3 show no new blocker; Ux752 quick gate `VERIFY_OK`)

Stephen's instruction: "Once complete, if there are no blocking issues, show me comprehensive prompt to start next
phase." The prompt below is grounded in `PictureBookV3Design.md` §30 row 1, §10, §11/§11.1, §21, §24, §26, §27,
§28.1, §31, §32 and in the code as it stands at the end of Phase 0 (symbols and line numbers verified
2026-10-10). Nothing in it has been started. Adjusted 22:15 for the gate/triage outcome: P1-11 now names the
e2e specs that are red for reasons outside Phase 1 (§4.3) and the standing rules carry the §6.M checkpoint rule.

---

**BEGIN PROMPT**

Implement **PictureBook V3 Phase 1 — "Graph-first rendering + review"** from `src/aiDocs/PictureBookV3Design.md`
§30 (row 1), on top of the completed Phase 0 recorded in `src/aiDocs/PictureBookV3Implementation.md`. Work task by
task; **STOP at the end of Phase 1** with the gate green and the tracking doc current, and report exactly as you
did for Phase 0 (what changed, per-task test results verbatim, deviations, what is waiting on me).

Read first, in this order: `.claude/rules/llm-conduct.md`, `.claude/rules/architecture.md`,
`.claude/rules/model-api.md`, `.claude/rules/troubleshooting.md`, `PictureBookV3Implementation.md` §1, §5, §6
(every open item), then the design sections named below, then the code each task cites. Do not re-derive Phase 0.

**Scope (design §30 row 1, verbatim):** "Rendering through graph execution (PbNodeExecutor converged with v1
behaviour, fixing the G-30 divergences), live drift detection (§11.1), review status, auto-approve toggle (§10),
async render jobs, restart recovery. V3 runs behind the `renderPath` switch (§28.1 Stage 1)." ChapBook impact:
"ChapBook pages become graph nodes with artifacts; same jobs and review." **Exit criteria:** "Leave the page →
render continues; restart → Resume; a charPerson edit shows the affected scenes."

Out of scope for Phase 1 (do not start them): `olio.pb.style`, `ModelResolver`, `RenderPlan`, compilers,
presets/estimate (Phase 2); locations (3); composition/actors/wardrobe (4); multi-character strategies (5);
variants/retention sweep (6); book workspace Ux replacing wizard steps 4–5 (7); QA (8); covers (9); any PB1 or
Kontext removal (10). Where Phase 1 needs a seam those phases will fill (a plan artifact, a composition id), use the
existing node/scene/artifact records and say so in §5.

**Decided defaults you must honour (from my brief and the ratified block in design §34):** auto-approve is
**one general switch** `olio.pb.book.autoApprove`, default **off**; Approve **pins**; auto-approve pins; PB1 and the
old ChapBook render path stay live and default during Stage 1; the switch is **boot-pinned, not per-org**; timings
are never test thresholds; no `KontextCompiler`; no graph-recording flag beside `renderPath`.

### Tasks (stable ids; one tracking-doc row each, under a new §8 "Phase 1 — tasks")

**P1-1 Schema additions (design §10, §21, §32; Objects7 `resources/models/olio/pb/`).**
`PbReviewStatusEnumType` {GENERATED, IN_REVIEW, APPROVED, REJECTED, ARCHIVED} and `olio.pb.artifact.reviewStatus`
(default GENERATED), `approvedBy` (string; `AUTO` for auto-approve) and `approvedAt`; `olio.pb.artifact.durationMs`
(§32 says it does not exist — verify in `artifactModel.json` before adding); `olio.pb.book.autoApprove` (boolean,
default false); `PbRunStatusEnumType.INTERRUPTED` (current values UNKNOWN, PENDING, RUNNING, COMPLETED, FAILED,
CANCELLED — `schema/type/PbRunStatusEnumType.java`). Column-backed, nullable, additive only — the boot patch adds
them (`objects7-reference.md` "Path 1"); existing rows read SQL NULL, so every reader treats null as the default.
Do **not** add `DONE_UNVERIFIED` yet (it belongs to the §28 migration, Phase 7) unless a Phase 1 test needs it —
flag ⚠ if so. Test: a JUnit that creates an artifact/run/book with each new field through `AccessPoint` and reads
it back; `TestPbGraph` still green.

**P1-2 Live drift detection (design §11.1).** `PbServiceFacade.listStale` (`PbServiceFacade.java:273`) and
`nodeSummary` (`:945`) today call only `PbGraphUtil.recomputeStatus` (`PbGraphUtil.java:544`), which compares
stored `refHash` values, so a direct `charPerson` edit is invisible until a run re-binds. Make them call
`PbGraphUtil.driftedRefBindings` (`:578`, compute-only; today used only by tests) for book-scope input nodes
(PORTRAIT, LANDSCAPE/STYLE_BIBLE today — the §11 renames are Phase 2+), mark each drifted node STALE, propagate with
`markStaleDownstream` (`:836`), persist fresh hashes with `persistRefHashes` (`:608`) **only** from an authorized
write path, and cache the scan per book for ~30 s. Add `PbStaleUtil.touch(record)` and call it after every
successful PB write that edits a character or its sub-records through PB endpoints. Edits outside PB are caught on
the next book open/review — state that bound in the doc; no hooks in `AccessPoint.update`. Tests (design §31):
JUnit "stale propagation after a direct `charPerson` PATCH" (PATCH the character, call `listStale`, assert the
scenes bound to that portrait are listed and the node rows read STALE); a Playwright that edits a character in the
PB Ux and sees "affected scenes: N" — this is exit criterion 3.

**P1-3 `PbNodeExecutor` converged with v1 (gap doc G-30, design §30 row 1).** The three recorded divergences
(`PictureBookImageGenerationDesign.md:280-282`): different profile load, portrait not linked, no SD-server fallback
in `testNode`. Converge each on the v1 (`PictureBookUtil.generateSceneImage`) behaviour, and make
`PbServiceFacade.requestRegenerate` (`:299`) actually submit a run for the node instead of only marking STALE
(keep the pinned → 409 rule, `:299-308`). Keep P0-8's closure semantics (`PbPipelineUtil.closeRun` `:679/:703`,
executor FAILED + `lastError`/`lastRunAt`). Note the P0-8 finding: `PbGraphUtil.startRun` (`:727`) writes PENDING
and nothing stamps RUNNING — Phase 1 must stamp RUNNING at execution start or P1-7's recovery has nothing to
recover. Tests: `TestPbGraph` cases extended; a live render through the executor on the Docker stack whose PNG you
open and describe; `TestPictureBookWorkflow.TestFreshCharacterSubRecordsAndPortraitRender` (§6.F) is adjacent —
re-run it and report, do not "fix" it unless the convergence is the cause.

**P1-4 Review state machine (design §10 rules 1–4).** In `PbArtifactUtil`: `select` (exactly one selected per
(node, role) — already enforced in `setSelected`), `approve` (selected variant → APPROVED, node `pinned=true`),
`reject` (REJECTED, kept). A new attempt's artifacts arrive GENERATED/`selected=false`; **never** change
`selected` while the current selected artifact is APPROVED; if nothing is approved, auto-select the first. A STALE
node keeps showing its approved artifact. Transport (Service7 `PictureBookService`, `@RolesAllowed({"admin","user"})`,
through `PbServiceFacade`): `PUT /olio/picture-book/artifact/{id}/select|approve|reject`, `GET /{book}/review-queue`
(every node with an unreviewed GENERATED variant or a STALE approved image), `GET /{book}/stale` now with live
drift (P1-2). Tests: JUnit for each rule including "approved selection survives a new attempt"; Service7 JUnit for
the routes incl. a cross-user 403/404; security-reviewer on the new routes.

**P1-5 Auto-approve (design §10 rule 5).** `GET/PUT /{book}/settings/auto-approve`; when an attempt finishes and
`book.autoApprove` is true, the first variant (QA ordering is Phase 8) is selected, APPROVED, pinned,
`approvedBy=AUTO`; guards: never replaces an existing APPROVED artifact; skips attempts with a recorded failure
degradation (Phase 1 has only `landscapeFailed` from P0-6 — honour it); switching off un-approves nothing;
review-queue filter "Auto-approved". Tests: JUnit for the on/off and each guard; Playwright for the toggle in book
settings and the Render dialog's "Auto-approve: ON — results will be approved and pinned" line and the header badge.

**P1-6 Async render jobs + durable runs (design §21).** Job kinds `pb.render` and `pb.renderAll` on
`AsyncJobRegistry` (`thread/AsyncJobRegistry.java`; in-memory, `MAX_CONCURRENT_JOBS=2`, 4 per principal, 30-min
TTL — do not make it durable). A render job executes one `olio.pb.run` whose `requestedNodeIds` are the requested
terminals; progress = node statuses + current stage, mirrored into the job's `SummarizeProgress` token so
`GET /rest/job/{jobId}` polling is unchanged. Routes: `POST /{book}/scene/{id}/render` → 202 `{jobId, runId}`
(Phase 1 target is the existing scene node; `/composition/{id}/render` arrives with Phase 4),
`POST /{book}/render-all` → 202, `POST /{book}/run/{runId}/cancel` (cooperative at stage boundaries:
stop scheduling, `SDUtil`/Swarm interrupt, discard in-flight output, run → CANCELLED — start writing that reserved
value; a cancelled attempt never changes `selected`). Per-SD-server render semaphore (default 1 permit) so two jobs
never stack on one GPU; LLM steps on a separate lane. The synchronous `/scene/{id}/generate` stays as a thin
wrapper. `artifact.durationMs` recorded per artifact (never asserted). Tests: JUnit cancel mid-stage; Playwright
exit criterion 1 — start a render, navigate away, come back, poll `/rest/job/{jobId}` to completion, open the PNG.
Waits: long ceilings, fail only on error or no progress (design §31 last bullet).

**P1-7 Restart recovery (design §21).** On boot (`RestServiceEventListener` / the Objects7 side it delegates to),
any run left RUNNING → INTERRUPTED and its RUNNING nodes → READY; `POST /{book}/run/{runId}/resume` submits a new
run for the unfinished requested nodes, reusing completed stages whose artifacts exist and whose input hashes match.
Tests: JUnit "restart recovery (simulate by marking a run RUNNING)" then resume and assert the completed node's
artifact is reused (same `contentHash`) and the rest render; a Docker `docker restart am7test-am7-1` e2e that
proves exit criterion 2 ("restart → Resume") through the Ux Resume button — record the container restart time
and the `[e2e-llm]`/SD lines as in §2.8.

**P1-8 The `renderPath` switch (design §28.1 Stage 1).** Boot-pinned `picturebook.renderPath` /
`chapbook.renderPath` = `v1` | `v3` (Service7 init-param + Console7 `resource.properties`, `docker/entrypoint.sh`
envsubst template + `docker-compose*.yml` env), read once at start-up, default **`v1`**. Expose both values
read-only through the feature manifest (`FeatureConfigService` `GET /rest/config/features/available`) so the Ux
branches on it. V3 code paths (P1-3/4/5/6/7 routes and executor) are only reachable when the product's switch is
`v3`; with `v1` the new routes return a clear 409/501 "render path is v1" body. ⚠ MY JUDGMENT, record in §6.C and
ask: the **test stack** (`docker-compose.test.yml`) boots with both switches `v3` so every Phase 1 e2e exercises V3,
while the production compose keeps `v1`. Tests: JUnit for the resolver with each value and with it absent;
Service7 JUnit that a V3 route is refused under `v1`.

**P1-9 ChapBook on the Phase 1 path (design §24, §30 row 1).** With `chapbook.renderPath=v3`, a ChapBook page
render produces a graph node with an artifact through the same `pb.render` job, and the page's `imageStale` maps
to node STALE; select/approve/reject and auto-approve apply. The old path (`ChapBookUtil.renderResolvedScene` /
`renderChapBookSummary`) stays the `v1` default. Tests: a JUnit ChapBook render on `v3` whose artifact row and
review status you read back, and the existing ChapBook Playwright suite green on `v3` (the two `[LLM]`
`chapBookConfigPanel` tests stay gated behind `CHAPBOOK_LLM_TESTS` until §6.G item 4 is repaired).

**P1-10 Ux752 (design §26, Phase-1 subset only).** Read `features/pictureBook.js`, `workflows/pictureBook.js`,
`workflows/pictureBookCharacters.js`, `components/SdConfigPanel.js`, `features/pictureBookWorkflow.js`,
`features/chapBook.js` and `core/am7client.js` first. Add: scene status chip (stale/review/approved) and the stale
badge that never hides the approved image; Use this / Approve / Reject on the scene's variants; the review queue
view with the "Auto-approved" filter; auto-approve toggle in book settings + Render dialog + header badge;
"affected scenes: N [Review] [Regenerate]" on a character; Render → async job with a live stage progress list from
job polling; Resume on an INTERRUPTED run; the workflow canvas A4 fix (`nodeSummary` returns the canvas geometry it
saves). `SdConfigPanel` sends sparse diffs only (G-32 — verify it already does after P0-10d before touching it).
Vitest for each component; Playwright for exit criteria 1–3 end to end.

**P1-11 Phase gate and STOP.** `export LOOP_MODULE=<module>; bash .claude/loop/verify.sh --quick` after each
backend slice; full `bash .claude/loop/verify.sh` (chromium + chromium-vite, 2 workers, `PLAYWRIGHT_BASE_URL=
https://127.0.0.1:9443`) before Phase 1 is called done — `VERIFY_OK`, or it is not done. Expected pre-existing reds
you may report as such **only if the counts are unchanged from §4.2**: Objects7 §6.A ×3 / §6.B / §6.F, Service7 §6.H.
On the e2e side the only specs you may report as pre-existing red are the ones §4.3 classifies — `apiBaseUrl` ×2,
`testHarness` ×3, `webauthn:112`, `biometrics:86`, `charPerson:51`/`:258`, `workflowRuntime:139`/`:222`,
`listNavigation:103`/`:155`/`:207`, `picker:137`, `breadcrumb:133`, `bugfix3-live:93`/`:275`/`:509`,
`pictureBookLive` C.2/C.3/H.1/L.1, `sdConfigFlow:134`/`:243`, and the three not re-run (`verifyFixes:272`,
`chatDeleteMsgDebug:12`, `worldGroupPicker.e2e:41`) — **unless I have told you I fixed or deleted them**, in which
case they must be green. Do not fix them yourself; they are not Phase 1 work. Anything else red is yours. Every
e2e fixture `pwRequest` context must read its base from `PLAYWRIGHT_BASE_URL` (§6.C item 20) — never a literal
`localhost:8899`.

### Standing rules (unchanged from Phase 0 — restated because they were violated before)

- **Service gate before work and before every test run**, recorded verbatim in §3: run `C:\tmp\am7-services-probe.sh`
  (postgres3 `:15432`; `am7test` `/rest/setup/state`; Vite `:8899` and `:8898` + container `dist` current; LLM `.42`
  lists `qwen3:8b` and `valkyriesys/eudaimonia-dryad3-vision`; Ollama container `:11435`; Swarm `.39:7801`;
  in-container reachability; `df`). Quote `[LLM-GATE] route=… tier=…` / `[e2e-llm] route=…` with every LLM result.
- **Stack facts as left by Phase 0** (§6.I/§6.J/§6.K): `am7-pg` on the named volume `am7test_am7-test-pg` (never
  the old `./docker-data/pg`); shared user `e2etest_shared` id **228** owns its content groups and is in
  `PERSISTENT_USERS` — never tear it down; Playwright config is chromium + chromium-vite at 2 workers, firefox
  opt-in (`PLAYWRIGHT_FIREFOX=1`); Vite on `:8898` proxies to the Docker stack (`AM7_BACKEND=https://127.0.0.1:9443`),
  `:8899` proxies to the local Tomcat / forbidden `am72db` — **never point a test at `:8899`**. Admin credential:
  where `local-stack-credentials-must-be-recorded.md` says; use admin only for fixture repair, never for a test.
- **Testing:** real JUnit/Vitest/Playwright against the live stack; `ensureSharedTestUser()`; no mocks of
  REST/LLM/SD; open every generated PNG with Read and describe it; record `durationMs`, never assert it; LLM/SD
  specs `--workers=1 --project=chromium` and gated; backend tests `cd src && mvn -o -pl <module> -Dtest=Class#method
  -DskipTests=false test`; install the jar and redeploy to Docker (`docker cp` + `docker restart am7test-am7-1`,
  §2.7) before any Service7/Playwright test of backend changes; `npx vite build && docker cp ./dist/. am7test-am7-1:
  /opt/ux752/dist/` before any Ux e2e.
- **Never** reset or drop the DB schema; never run `TestAuthorization` or `admin -db -patch`; never touch `am72db`;
  do not commit or push.
- **SD checkpoints (§6.M):** never introduce a checkpoint name you have not seen in `.39`'s
  `GET /API/ListModels` (ground truth: design §36). The client default is
  `OfficialStableDiffusion/sd_xl_base_1.0.safetensors` (`modelDef.js`, guarded by `src/test/sdConfigModelParity.test.js`);
  `sdXL_v10VAEFix.safetensors` is retired and must not reappear on any live path. A Swarm `Invalid model value`
  in `docker logs am7test-am7-1` means the request was wrong, never that the server is down.
- **Known Service7 sharp edge, not yours to fix:** `OlioService.reimageWithConfig` 500s (NPE at `:266`) when the
  caller cannot read the target record — pick fixtures the test user owns (`setupWorkflowTestData`), never "any
  charPerson".
- **Per task:** planner → architect → implement → test-author → verifier, plus security-reviewer for every new
  route, auth change, PBAC path or LLM prompt. Flag ✓ FOLLOWING INSTRUCTIONS / ⚠ MY JUDGMENT; on ⚠ record the
  chosen default in §6.C and ask at the phase end unless it changes a ratified decision — then stop and ask.
- **Tracking doc after every task:** §1 status, the P1-n row (stable id, status, what changed, verbatim test
  result lines, PNGs opened), §3 services block, §5 deviations (entry + dated in-place design edit), §6 blocked /
  waiting, §4 verification per phase.
- **Phase 0 items still open and how they touch Phase 1:** §6.A (three PB-series tests — the series grant model;
  do not "fix" from Phase 1), §6.B (expired fixture), §6.D (postgres3 PANIC — Stephen's box), §6.F (fresh-character
  resolver, adjacent to P1-3), §6.H (Service7 H2 org 4 keystores), §6.G item 4 (library connection 9 has no API
  key — classifier-denied, Stephen's repair), §6.L (nude `generateScene` fixtures — until answered, Phase 1 render
  tests use the PB cast from `pictureBookLive.spec.js` / `setupWorkflowTestData`, not bare `charPerson` records).

**END PROMPT**
