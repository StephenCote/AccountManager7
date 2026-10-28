# PictureBook V3 — Implementation State

**As of:** 2026-10-28 local · **Phase 5 IN PROGRESS.** P5-0 (Swarm spike), P5-1 (graph builder) and P5-2 (executor wiring; live leg
run #4 `TestP52SequentialInpaintLive` 5 / 0 / 0 / 0, `p52-t11d.log:2873`) are run and reported — §4.13, §13.4, §6.C 208–209; Stephen
accepted every open default on 2026-10-28 (§13.4 "Yours" (1)–(21); §6.C 210); nothing is DONE by me (archive §6.C 159 b). **Next: P5-3
implementation cleanup** (§13 P5-3 paragraph; keep design §28.1 staging — `PbRenderPath.PICTUREBOOK_DEFAULT` stays `V1`, PB1 stays;
results → §13.5). Phases 0–4 are closed (Phase 3 exit confirmed 2026-10-18, §6.C 182; Phase 4 exit confirmed 2026-10-26, §6.C 207);
their history, the closed Phase 5 slices (§4.11, §4.12, §13.1–§13.3) and §6.C entries 2–206 are in
`archive/PictureBookV3Implementation-P0-P4.md` with the original section numbers — a "§n" reference that is a one-line stub here
resolves there (the former header paragraphs are its §0). Still open and Stephen's, unchanged since Phase 0–1: archive §6.A, §6.D,
§6.E, §6.G, §6.L (roll-up in §6).

**Design of record:** `PictureBookV3Design.md` (ratified 2026-10-09). Where this doc and the design
disagree, the design wins; every deviation is recorded in §5 here **and** as a dated in-place edit in
the design. Format follows `PictureBook2ImplementationState.md`.

**Working rules for this doc (from the brief):** updated after every task; test results are pasted
verbatim (`Tests run: n, Failures: 0 … BUILD SUCCESS`, Playwright `n passed`, the `[LLM-GATE]` /
`[e2e-llm]` line, measured `durationMs` as a trend, never a threshold); services re-probed before every
test run; phase is DONE only on `VERIFY_OK` plus verifier / architect / security-reviewer verdicts.

**Terseness (Stephen, 2026-10-28 — "you are incredibly verbose"):** a results section is ≤ ~60 lines and a §6.C entry ≤ ~25 lines;
evidence is a log path plus line numbers (`p52-t11d.log:2873`), never a quoted paragraph; counts are `run / fail / err / skip`; a
closed section (slice reported, defaults accepted) moves verbatim to the archive with a one-line `> Moved …` stub in the doc pass
that follows its close, and the §1 row of a closed phase is one line.

**Open-item defaults in force (§34):** auto-approve default **off**; default preset **DRAFT**; verify
bar **two weeks + sign-off**; estimate source **(b) seeded by (a)**.

---

## 1. Status at a glance

| Phase | State | Tests |
|---|---|---|
| **0 — Hygiene + Kontext Deprecate** | **AT PHASE END** 2026-10-10 (gate red only on Stephen's §6.A / §6.B / §6.F / §6.H items; P0-1…P0-11 DONE with live tests; Phase 1 started 2026-10-10) — full row archive §1 | 136 green in the task runs — archive §1, §2 |
| 1 — Graph-first rendering + review | Row last written 2026-10-13 (**IN PROGRESS**, "NOT declared done" by me; the §9 follow-ups PF-1 / PF-9 / PF-15b DONE); Phase 2 started on it 2026-10-13 — full row archive §1 | archive §1, §8–§9 |
| 2 — Style, model resolution, compiler core; ChapBook goes V3; Kontext Disable | **GREEN PASS COMPLETE** 2026-10-15 19:45 (P2-10 gate; phase exit Stephen's, 159 b) — full row archive §1 | archive §1, §10 |
| 3 — Locations + POI link | **DONE 2026-10-18** — Stephen confirmed the §11.8 report and answered every open ask (§6.C 182) — full row archive §1 | archive §11 |
| 4 — Composition + wardrobe | **DONE 2026-10-26** — Stephen confirmed the Phase 4 exit (§6.C 207, "will catch any issues in next") — full row archive §1 | archive §12 |
| 5 — Multi-character strategies (spike first) | **IN PROGRESS** — P5-0 spike (archive §13.1, §6.C 204), P5-1 graph builder (archive §13.2 / §4.12, §6.C 205) and P5-2 executor wiring (§13.4 / §4.13, §6.C 208–209) run and reported; 2026-10-25 went to the Part A doc consolidation (archive §13.3, §6.C 206); P5-2 hand-back defaults (1)–(21) accepted 2026-10-28 (210); nothing DONE (159 b); **next P5-3 implementation cleanup** (§13 P5-3 paragraph; results → §13.5) — former row archive §1 | `TestP50SwarmSpike` 6 (gated); live on `.39`: `TestP51GraphLive` 5 / 5, `TestP52SequentialInpaintLive` 5 / 5 (`p52-t11d.log:2873`), `TestPbRenderPlanExecutor` 8 / 8, `TestP51GraphLive#t00` 1 / 1; offline `olio.illustration.*Test*` 187 / 187 (`p51-unit5.log`), P5-2 offline set 133 / 133 (`p52-run-final3.log`), fixture consumers 66 / 66; 12-class regression set 116 / 0 / 0 / 1 (`p52-regression2.log`) |
| 6 — Variants | TODO | — |
| 7 — PictureBook goes V3 | TODO | — |
| 8 — Visual QA | TODO | — |
| 9 — Cover & book art | TODO | — |
| 10 — Removal (PB1, old ChapBook path, Kontext Remove) | TODO | — |

---

## 2. Phase 0 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §2 (section numbering unchanged there).

## 3. Services checked

> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §3 (the 118 P0–P4 service probes, 2026-10-09 → 2026-10-23; new P5+ probes append here) (section numbering unchanged there).

**P5-2 probes, 2026-10-26** (`host` = this Windows box, `ctr` = inside `am7test-am7-1`; `curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5`):
SwarmUI `.39` (`http://192.168.1.39:7801`) **did not answer at any probe before 16:44** (later probes: next paragraph) — 09:27:48 host `http=000` exit 28 (connect timeout), `ping`
100 % loss; 10:10:07 host `000` / exit 28; 13:12:50 host `000` / exit 28 **and** ctr `000` / exit 28; 13:59:43 host `000` / exit 28 and ctr
`000`; 14:33:01 (after the verifier fixes) host `000` / exit 28 and ctr `000`. `.42:7801` answered `302` at every probe (SwarmUI `0.9.8.2.GIT-d7637957`, 19 checkpoints incl. `flux2Klein_9b.safetensors`,
`flux1Kontext_flux1KontextDev.safetensors`, `juggernautXL_ragnarokBy.safetensors`, `sdXL_v10VAEFix.safetensors` — a different catalogue
from `.39`'s `0.9.8.4`, so T12 fixtures captured there would describe `.42`'s generator). LLM: `[LLM-GATE] route=litellm tier=local
analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000` at 13:14:18 (`p52-regress.log:55`) — in words, the local Docker
Ollama container through LiteLLM; not Azure, not `.42`. These are measurements, not a verdict that `.39` is "down"; per §6.C 159 (c) the
live leg waits for `.39` unless you pin `test.swarm.server` to `.42` (§13.4 "Yours" (1)).

**P5-2 live-leg probes, 2026-10-26 (evening)** (same command, host): `.39` answered `302` at **16:44:48** and at every later probe — 17:54:42,
18:57:54, 19:10:59, 19:33:09, ≈19:50, 20:09:23 (`GetNewSession` OK; SwarmUI **`0.9.8.4.GIT-d74f8ce4`**, the version `TestP51GraphLive.t00`
recorded at 20:00:06, `p52-t00capture.log:59` — the P5-0 spike measured `GIT-5dc94afa`); `postgres3` `pg_isready` accepting at 20:09. Stephen's
message that `.39` was back — "I didn't know .39 was down, it's up now — I didn't see you stop to ask (you should have) … continue with
incomplete dev/testing" — preceded the live leg; I had not stopped when it went down (§6.C 209 process note). LLM at every live run:
`[LLM-GATE] route=litellm tier=local analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000` — in words, the local Docker
Ollama container through LiteLLM, not Azure, not `.42`; **no LLM call was made** by `TestP51GraphLive`, `TestP52SequentialInpaintLive` or
`TestPbRenderPlanExecutor` (pure SD against `http://192.168.1.39:7801`, as each class logs at start).

## 4. Verification per phase

| Phase | `verify.sh` | verifier | architect | security-reviewer |
|---|---|---|---|---|
| 0 | Two full gates `VERIFY_FAILED` on Stephen's items only (2026-10-10; archive §4.1–§4.3: Objects7 `902/5/0/15` = §6.A ×3 / §6.B / §6.F, Service7 `108/0/1` = §6.H, 75 e2e triaged) — former row archive §4 | Slices A/B CHANGES-NEEDED (resolved); P0-10d, P0-11 and the phase-level pass PASS-WITH-NOTES (2026-10-09/10) | plan reviews 2026-10-09 (A/B 7 APPROVED / 4 CHANGES-NEEDED, C/D CHANGES-NEEDED on P0-10a / P0-11 — all resolved, §6.C); code reviews A/B and C/D PASS | P0-10c PASS, Slices A/B PASS-WITH-NOTES, P0-10d PASS-WITH-NOTES, P0-11 PASS (2026-10-09) — former row archive §4 |

> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §4.1–§4.3 (Phase 0 gate runs + the 75-e2e triage) (section numbering unchanged there).

### 4.4 Phase 1 module-gate runs (2026-10-10 → in progress)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.4 (Objects7 module-gate runs 1–30, 2026-10-10 → 2026-10-23: the cross-phase run table and per-run notes) (section numbering unchanged there). Phase 5 has run no full Objects7 gate yet (P5-2 ran a 12-class regression set, §4.13); run 31+ append here in the same table.

> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §4.5–§4.8 (Phase 1–3 full Ux752 gates: runs 1–5); §4.9/§4.10 (Phase 4) moved 2026-10-26, next stub (section numbering unchanged there).

> Moved 2026-10-26 to `archive/PictureBookV3Implementation-P0-P4.md` §4.9–§4.10 (Phase 4 full Ux752 gates: runs 6–7, P4-11 / P4-14; Phase 4 exit confirmed by Stephen 2026-10-26, §6.C 207) (section numbering unchanged there).

### 4.11 P5-0 Phase 5 spike — `TestP50SwarmSpike` (Objects7, gated, no DB, no LLM) against SwarmUI `http://192.168.1.39:7801` — run 2 (2026-10-23 23:02:08 → 2026-10-24 00:12:00 local, `Total time: 01:09 h`): `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4192 s`, `BUILD SUCCESS` — 6 / 6 ran, 29 render requests: 26 images, 1 server-side error (FACEID), 1 unmeasured (client ceiling) → re-measured alone in run 3 (00:34:13 → 00:50:05), 1 WebSocket render (frames only); run 1 (22:55) stopped and archived; **27 PNGs opened and described** (26 from run 2 + the run-3 re-measure)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.11 (the `TestP50SwarmSpike` runs 1–3, 2026-10-23 → 2026-10-24; ratified 2026-10-24, §6.C 204) (section numbering unchanged there).

---

### 4.12 P5-1 graph builder — `TestP51GraphLive` (Objects7, gated, `am7db`, pure SD, no LLM call) against SwarmUI `http://192.168.1.39:7801` (`0.9.8.4.GIT-d74f8ce4`) — run 5 (2026-10-24 19:29:58 → 19:38:39 local, `Total time: 08:46 min`): `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 522.0 s`, `BUILD SUCCESS` — 5 / 5 ran, 9 render requests: 7 images (1 BASE FLUX.2 with book 58's three real portraits, 4 FLUX.2 ACTOR_REGION variants, 1 BASE SDXL, 1 ACTOR_REGION SDXL) + 2 deliberate rejections (t04a uninstalled checkpoint → `RENDERER_REJECTED`, t04b unknown node type → `GRAPH_INVALID` before any request); runs 1–4 red (below); **the 7 PNGs of run 5 opened and described** (and the 7 of each of runs 3 and 4 before them)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §4.12 (`TestP51GraphLive` runs 1–5 and the regression set after run 5, 2026-10-24; defaults accepted 2026-10-26, §6.C 207) (section numbering unchanged there).

### 4.13 P5-2 executor wiring — offline set, regression set, then the live leg (Objects7, `am7db`, one surefire JVM each; **first half with no SD reachable — `.39` unreachable until 16:44, §3 — live leg after that**) — 2026-10-26

**Offline set, post-review (`C:/tmp/p52-run-final3.log`, 14 classes, one JVM, 14:27 → 14:32, `Total time: 05:08 min`): `[INFO] Tests run:
133, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS`.** Per class, each `Failures: 0, Errors: 0, Skipped: 0`: `TestOperationUtil` 1
(new, offline); `TestFlux2Compiler` 16; `TestGraphRequestShape` 4; `TestIllustrationResources` 21; `TestInpaintRegionCompiler` 4 (new);
`TestNodeCatalogue` 13; `TestRenderEstimate` 6; `TestRenderPlan` 15; `TestStrategySelector` 8; `TestSwarmGraphClientOffline` 6;
`TestSwarmRendererAdapterErrors` 4 (new); `TestWorkflowCompiler` 24; `TestPbRenderPlanActors` 5 (291.0 s, `am7db`, cases 04 / 05 new);
`TestSdCalibrationRecord` 6 (8.2 s; new, `am7db`, as `p52CalibrationUser`). Evidence of the review fixes in that log: `:637` case04's sdxl
plan `strategy=SEQUENTIAL_INPAINT reason=… 4 ACTOR_REGION passes; HARMONIZE deferred (unmeasured); region passes carry no portrait (family
takes no references)` (208 (as)); `:1187` `cleanup ~/p52ghost: true` (208 (av)). Log counts: `Index collision` 0, `Column does not exist` 0,
`[ERROR` 54 — all fixture noise (case05's intentionally unreadable reads, the `.invalid` / loopback connection refusals the error tests
provoke, `OlioContext` root-location misses, policy-audit description > 2048); 7 `AUDIT DENY`, all expected. The same 14-class set before the
fixes: `C:/tmp/p52-run-final2.log` 133 / 0 / 0 / 0 (the run the verifier was handed) and the verifier's own re-run `C:/tmp/p52-verify-run.log`
133 / 0 / 0 / 0 `BUILD SUCCESS` (`:2306`, `:2309`). **The run before those (`C:/tmp/p52-run-final.log`): `Tests run: 132, Failures: 1`** —
`TestPbRenderPlanActors.case05:595` "portrait data no longer readable by testUser1 (precondition)", red only in the multi-class JVM and green
solo; root cause `OperationUtil.getOperationInstance`'s JVM-static `IOperation` cache pinned to the first `IOContext`'s closed `CacheDBSearch`
(§6.C 208 (al)); `TestOperationUtil` is red on HEAD (`Tests run: 1, Failures: 1` at `:49`) and green with the fix.

**Regression set (`C:/tmp/p52-regress.log`, the 12 classes of §4.12's set, one JVM, 13:13:51 → 13:53:41, `Total time: 39:48 min`):
`[ERROR] Tests run: 115, Failures: 0, Errors: 4, Skipped: 23` / `[INFO] BUILD FAILURE`.** `[LLM-GATE] route=litellm tier=local
analysis=qwen3:8b-ctr pb=qwen3:8b-jos-ctr server=http://127.0.0.1:4000` (`:55`) — the local Docker Ollama container through LiteLLM; not
Azure, not `.42`. Per class:

| Class | Result | Note |
|---|---|---|
| `TestModelResolverLive` | 16 run / **16 skipped** (41.3 s) | SD-gated (`SdTestGate.requireSwarmReachable`, "This is NOT a pass") — `.39` unreachable |
| `TestPbGraph` | 20 / 20 (212.5 s) | DB only |
| `TestPictureBookWorkflow` | 5 run / **4 errors** (1015 s) | ungated on SD; every call to `http://192.168.1.39:7801` timed out — see below |
| `TestModelResolver` | 41 / 41 | offline |
| `TestPbHealthStaleGraph` | 2 / 2 (214.4 s) | DB only |
| `TestPbOrphanCompositions` | 2 / 2 (243.6 s) | DB only |
| `TestPbOrphanScan` | 1 / 1 (151.9 s) | DB only |
| `TestPbRenderPlanActors` | 5 / 5 (259.0 s) | DB only; cases 04 / 05 new this slice |
| `TestPbRenderPlanExecutor` | 7 run / **5 skipped** (98.2 s) | the 5 live cases SD-gated; the 2 offline cases green |
| `TestPbRenderPlanLocationText` | 7 / 7 | offline |
| `TestPbRenderPlanSubject` | 4 / 4 | offline |
| `TestRenderPlanLive` | 5 run / **2 skipped** (146.8 s) | the 2 SD cases gated on `SdTestGate.isReachable(server)`; 3 DB cases green |

**The 4 errors, all `TestPictureBookWorkflow`** (`:188738-188741`): `TestFreshCharacterSubRecordsAndPortraitRender:677`,
`TestLandscapeReferenceChangesTheComposite:425`, `TestSceneGraphIsRecordedAndStructurallySound:217`, `TestStepBudgetComparison:788`, each
`» PictureBook java.net.ConnectException: Connection timed out: getsockopt`. The class is not SD-gated; `SDUtil - Could not list models from SD
server http://192.168.1.39:7801: … Connection timed out` at `:95291`, `:95625`, `:95886`, `:96122` and the stacks end in
`SWUtil.getNewSession(SWUtil.java:611)` / `SDUtil.getOrCreateSession(SDUtil.java:163)`. It passed 5 / 5 in the P5-1 regression (§4.12,
2026-10-24) when `.39` answered. **Carried as `.39`-outage reds to re-run when `.39` answers — not closed as "pre-existing"** (§6.C 208 (ao)).
The 23 skips are exactly the three SD-gated classes above — **visible skips, not passes**. No SD render was made in either run; there is no
PNG to open. **Not run:** `TestP51GraphLive` (gated, `PB_SD_TESTS=1`), the planned `TestP52SequentialInpaintLive` (T11 — not written, §13.4),
the T12 fixture refresh, the 5 live `TestPbRenderPlanExecutor` cases, the 2 live `TestRenderPlanLive` cases, `TestModelResolverLive`.

**Live leg, 2026-10-26 evening (`.39` `0.9.8.4.GIT-d74f8ce4` answering, §3; every run `PB_SD_TESTS=1` + `-Dpb.sd.tests=1`, one JVM, `am7db`,
as `pbCustomTestUser` id 186 / org 21; LLM gate `route=litellm tier=local` = the local Docker Ollama container through LiteLLM, and no LLM
call made by the pure-SD classes; timings are observations, never thresholds):**

| Run | Log | Result |
|---|---|---|
| `TestP51GraphLive` re-run after T1 (plan §7 step 3) | `C:/tmp/p52-p51live.log` | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 599.4 s` (`:341`), `BUILD SUCCESS` (`:348`), `Total time: 10:04 min` |
| T11 `TestP52SequentialInpaintLive` run #1 | `p52-t11.log` | **`Tests run: 5, Failures: 0, Errors: 4`** (`:2438`), 288.6 s — t01–t04 each `IllegalArgumentException: Input comfyrawworkflowinputimageswarminputimageactor2 collides with …actor1: Swarm reduces both to …actor` (`:2551-2554`): the product defect §6.C 209 (a) |
| T11 run #2 (after `referenceName`) | `p52-t11b.log` | **`Tests run: 5, Failures: 2`** (`:2842`), 1024 s — `t02:264` / `t03:369` `stagesPlanned expected:<[BASE, ACTOR_REGION#<actorId>]> but was:<[BASE, ACTOR_REGION]>` (`:2881-2882`): my assertion, 209 (d) |
| T11 run #3 | `p52-t11c.log` | **`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 998.4 s`** (`:2873`), `BUILD SUCCESS` (`:2880`), `Total time: 16:42 min` — t01 219655 ms (`:606`), t02 294173 ms (`:1342`), t03 219939 ms (`:2060`), t04 refused in 4272 ms (`:2551`), t05 estimate-only; 4 PNGs under `target/p52/` (§13.4) |
| `TestPbRenderPlanExecutor` with its 5 live cases (plan §7 step 5) | `p52-executor-live.log` | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 396.6 s` (`:2198`), `BUILD SUCCESS`, `Total time: 06:40 min`; five `Stage BASE rendered 1 image(s)` lines (`:749` 22370 ms, `:975`, `:1563`, `:1743`, `:2051`) |
| T12 capture `TestP51GraphLive#t00_catalogueAndDriftProbe` | `p52-t00capture.log` | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.760 s` (`:79`), `BUILD SUCCESS`; drift lines `:70-72`; wrote `target/p51/{base_flux2,inpaint_A_flux2,base_sdxl,inpaint_A_sdxl}.workflow(.request).json` (`:74-77`) |
| T12 offline consumers, run 1 (6 classes) | `p52-offline1.log` | **`Tests run: 66, Failures: 1`** (`:236`) — `TestWorkflowCompiler.missingSeedMarkerIsRejected:426` (`:191`): my `FixtureGraphClient.dropSeed` no-op against a fixture whose `noise_seed` is already the marker, 209 (k) |
| T12 offline consumers, run 2 | `p52-offline2.log` (`mvn -q`, so the log carries no surefire summary; `exit=0`) | `target/surefire-reports/*.txt` stamped 20:08:57 → 20:09:00: `TestWorkflowCompiler` 24, `TestComfyGraph` 15, `TestNodeCatalogue` 13, `TestSwarmGraphClientOffline` 6, `TestGraphRequestShape` 4, `TestSwarmRendererAdapterErrors` 4 — **66, each `Failures: 0, Errors: 0, Skipped: 0`** |
| 12-class regression set re-run with `PB_SD_TESTS=1` (plan §7 step 7; the set 208 (ao) / (az) owed) | `p52-regression2.log` (190,805 lines; 20:09 → 21:23) | **`Tests run: 116, Failures: 0, Errors: 0, Skipped: 1`** (`:190797`), `BUILD SUCCESS` (`:190800`), `Total time: 01:14 h` (`:190802`), `exit=0` (`:190805`); gate line `:54-55`; per class: `TestModelResolverLive` 16 / 0 / 0 / **1** 25.59 s (`:191`; the skip is `case07_anyInstalledKontextIsFamilyDisabledEvenAsAnOverride` — `SKIPPED: http://192.168.1.39:7801 lists no Kontext checkpoint. This is NOT a pass.`, surefire XML), `TestPbGraph` 20 257.2 s (`:95763`), `TestPictureBookWorkflow` **5 / 0 / 0 / 0** 2582 s (`:99326`; 4 red on `.39` timeouts in the 208-era run), `TestModelResolver` 41 (`:99504`), `TestPbHealthStaleGraph` 2 232.7 s (`:188336`), `TestPbOrphanCompositions` 2 242.6 s (`:188411`), `TestPbOrphanScan` 1 178.0 s (`:188476`), `TestPbRenderPlanActors` 5 255.3 s (`:189407`), `TestPbRenderPlanExecutor` 8 425.1 s (`:190372`), `TestPbRenderPlanLocationText` 7 (`:190374`), `TestPbRenderPlanSubject` 4 (`:190376`), `TestRenderPlanLive` 5 265.4 s (`:190793`) |
| Verifier's independent offline run (10 classes, no executor class) | `p52-verifier-offline.log` | **`Tests run: 123, Failures: 0, Errors: 0, Skipped: 0`** (`:2158`), `BUILD SUCCESS` (`:2161`), `Total time: 04:28 min` (`:2163`); gate `:417`; `TestComfyGraph` 15 (`:48`), `TestGraphRequestShape` 4 (`:169`), `TestModelResolver` 41 (`:347`), `TestNodeCatalogue` 13 (`:367`), `TestSwarmGraphClientOffline` 6 (`:385`), `TestSwarmRendererAdapterErrors` 4 (`:388`), `TestWorkflowCompiler` 24 (`:409`), `TestPbRenderPlanActors` 5 260.5 s (`:2150`), `TestPbRenderPlanLocationText` 7 (`:2152`), `TestPbRenderPlanSubject` 4 (`:2154`) |
| `TestPbRenderPlanExecutor` re-run after the verifier's LOW-1 fix (loop into the try, `MAX_REFERENCE_NAMES`), 5 live cases | `p52-refname2.log` | **`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 402.2 s`** (`:2198`), `BUILD SUCCESS` (`:2205`), `Total time: 06:45 min` (`:2207`); gate `:54-55`; `MEASURED` BASE renders `:829` 18349 ms, `:1055` 14835 ms, `:1643` 16882 ms, `:1823` 14571 ms, `:2131` 14569 ms (1024 × 1024, `sd_xl_base_1.0`); 5 PNGs `target/p25-render-plan-{p26c84ad7313-p26-defaults,p26c84ad7313-p26-override,p25hbeb4246a-first,p25hbeb4246a-second,p25d8bc25996-degraded}.png` opened (§13.4) |
| T11 run #4 after the verifier's LOW-2 / LOW-3 fixes (t03 / t04 attribution asserts, dropout = fail) | `p52-t11d.log` | **5 / 0 / 0 / 0**, `BUILD SUCCESS`, 1026 s (`:2873`, `:2880`); both attribution asserts held live (t03 `:2065`, t04 `:2633`); four PNGs rewritten under `target/p52/` and described in §13.4 |

**Regression set re-run, 2026-10-26 20:09 → 21:23 (`p52-regression2.log`): 116 / 0 / 0 / 1, `BUILD SUCCESS`, 1 h 14 min** — the
set 208 (ao) / (az) owed, run after every review fix and after the `referenceName` change (209 a / c), with `.39` answering so the SD-gated
classes rendered instead of timing out: `TestPictureBookWorkflow` 5 / 5 (2582 s; 4 red on `.39` timeouts in the 208-era run),
`TestRenderPlanLive` 5 / 5 with its two SD cases (camera BASE 512 × 512, 12 steps, 5581 ms, `:190453`; TEXT_ONLY BASE 5219 ms, `:190698`),
the executor's five live cases again (14.6–30.6 s per SDXL BASE, `:189733`–`:190362`). The one skip is `TestModelResolverLive.case07`
refusing to pass on an inventory with no Kontext checkpoint (its own message: "This is NOT a pass") — carried as a gated skip, not a pass.
Every `[ERROR]` / `Connection refused` line read and classified: every refusal is against `http://127.0.0.1:1`, the deliberate
unreachable-renderer negatives (`case16 executeNode … threw ProcessingException` `:64491`; `INVENTORY_UNAVAILABLE` degradations `:64488`,
`:188973`, `:188976`, `:190778-190781`; "unreachable stage failed in 36 ms" `:190783-190784`); the `generalChat` `AUDIT DENY` deny-as-miss
(`:190067`, `:190164`, `:190293`) → `No chat config available` → `PbComposeUtil … LLM unavailable; the MIGRATED composition … stands` is the
executor cases' expected no-LLM fallback; `SD server API type 'nonsense' is not recognised` (`:190506`) and the `olio.pb.book` `AUDIT DENY`
on `503d460b…` (`:190496`) are negative cases; `Failed to find root location` / `Root Epoch is null` is test-world setup noise. 16 PNGs
written by this run — 9 `export/pb2_*` / `ki68_*` from `TestPictureBookWorkflow` (`:96386`–`:99323`), 5 `target/p25-render-plan-*`,
`target/p410-camera-render-*`, `target/p24-live-render-*` — plus the 5 `target/p25-render-plan-*` from `p52-executor-live.log` and the 5
from the post-verifier executor re-run `p52-refname2.log`: all 26 opened and described in §13.4 (after T11's four).

Log noise in the live runs, all read: 4 × `RecordUtil - WriterException: Record failed validation` per T11 run (`p52-t11c.log:511`, `:1202`,
`:1951`, `:2678`) — the `system.audit.description` 2048-char overflow on the fixture's `olio.store` full-plan read, an audit row dropped,
nothing else affected (209 f, pre-existing); `Index collision` 0, `Column does not exist` 0.


---

## 5. Deviations from design

Each entry here is mirrored by a dated in-place edit in `PictureBookV3Design.md` (or the gap doc
`PictureBookImageGenerationDesign.md` where the error originated).

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §5.1–§5.7 (Phase 0–4 deviations and recorded facts, 2026-10-09 → 2026-10-22) (section numbering unchanged there). Phase 5 deviations are recorded in each slice's §6.C entry (204, 205, 208, 209) and as dated in-place edits in the design; a §5.8 is opened here only when one needs more than an entry.

## 6. Blocked / waiting on Stephen

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §6.A, §6.B, §6.D, §6.E, §6.F (Phase 0–1 blockers; §6.B and §6.F RESOLVED 2026-10-12) (section numbering unchanged there). Still open there and Stephen's: §6.A (three PB-series tests red — policy cache vs the series grant model), §6.D (`postgres3` PANIC 2026-10-09), §6.E (architect flags from the Slice A/B review).

### 6.C Decisions already made under auto mode

Entries 2–206 (2026-10-09 → 2026-10-25, Phase 0 through the Part A doc consolidation) moved 2026-10-28 to
`archive/PictureBookV3Implementation-P0-P4.md` §6.C (numbering unchanged there; 159 b = "nothing is DONE by me" lives there).
Every entry is a ⚠ MY JUDGMENT call under auto mode with its default; Stephen accepted all open defaults on 2026-10-28 (210).

207. ⚠ **2026-10-26 (Stephen's rulings on §13.3 "Yours" and the §13.2 defaults; Phase 4 exit; the STEP 0 doc pass that records
     them, before P5-2).** Nothing here is DONE by me (159 b); Phase 4 is DONE by his word. His answers, verbatim, and what each closes:
     (1) "Phase 4 exit CONFIRMED — will catch any issues in next" → closes 206 (b): §12 and §4.9 / §4.10 moved to the archive this
     pass, file renamed `archive/PictureBookV3Implementation-P0-P4.md`; §1 Phase 4 row → DONE 2026-10-26 (Phase 3 row pattern).
     (2) "keep `PictureBookDesign.md` / `PictureBook2Plan.md` live" → closes 206 (f) at its default (reference banners stay).
     (3) "KI-91 stays home of the leftovers" → closes 206 (c) at its default (no design §34 rows for them).
     (4) "keep pre-images + P5-1 scratch" → closes 206 (k) at its default (nothing deleted; `C:/tmp/p51-*`, `C:/tmp/p51_*`,
     `C:/tmp/*.pre-split`, `AccountManagerObjects7/target/p51/` stay until he says).
     (5) "keep `## Project content policy` in root `CLAUDE.md` for now" → closes 206 (e) at its default.
     (6) "keep the three pre-existing re-points (206 l)" → closes 206 (l) at its default.
     (7) "proceed with P5-2" → the §13.2 plain questions (1)–(9) were not answered individually, so their defaults stand: (1)
     `capabilities.json` flux2 inpaint `4 / 1.0` → `8 / 0.6` in P5-2 together with the calibration record; (2) three real
     portraits (book 58) is the real cast; (3) `isGraphRejection` stays broad until the executor needs the split; (4)
     `TestP51GraphLive`'s portrait read → a planned `profile.portrait` on the `AccessPoint.find` query in P5-2 (live re-run);
     (5) `TestPictureBookWorkflow`'s mutation of book 58 is left; (6) the p50 fixtures are refreshed from a live capture after
     P5-2; (7) the 205 (w) / (x) one-liners are P5-2's; (8) the `gpt-oss:120b` key migration is its own task, not P5-2's; (9) the
     `TestModelResolver` update and the INPAINT inventory answer stand. 206 (a) / (d) / (g)–(j) were not raised and stay at their
     defaults.
     (8) NEW: "add implementation cleanup to remove/redact/deprecate all the older/replaced picturebook features" → new slice
     **P5-3** (scope paragraph in §13; results → §13.5; its rulings → 209), sequenced after P5-2 — see (a) / (b).
     Judgments taken in this pass:
     (a) ⚠ **(8) read as an inventory-first, stage-aware cleanup slice that honours design §28.1 (disable → verify → remove;
     Stage 4 = Phase 10) and `PbRenderPath.PICTUREBOOK_DEFAULT = V1` until Phase 7** — three verbs: REMOVE what has no remaining
     caller (verified by grep, not assumed), REDACT replaced surfaces where V3 parity exists (the PB1 entry points of §28.1 Stage 2
     stay reachable under `v1`, gated only behind `PbRenderPath`; Ux752 hides replaced menu / wizard surfaces behind
     `GET /rest/config/render-path`), DEPRECATE what Phase 7 / 10 still needs (`@Deprecated` + WARN on use + a §28.1
     removal-inventory row: file:line, replacement, removing phase). The alternative — read (8) as "pull Phase 10 forward: flip the
     default to V3 and delete PB1 now" — was not taken; it is the first plain question of the P5-3 plan. Default: keep §28.1
     staging; the default is not flipped and PB1 is not deleted unless he answers yes.
     (b) ⚠ **P5-3 sequenced after P5-2**, not before or interleaved: P5-2 is the slice (7) says to proceed with, and the cleanup
     inventory is more accurate once the executor wiring shows which PB1 / Kontext paths V3 actually replaces. The alternative —
     cleanup first so P5-2 lands on a smaller code base — was not taken. Default: P5-2, then P5-3.
     (c) ⚠ **§12 moved as `## 12. Phase 4 — tasks` through its closing `next §6.C number 204 (P5-0).` line (2,185 lines, §12.1–§12.15
     verbatim); the blank / `---` / blank that separate it from `## 13.` stay live as §13's separator**; the archive's §11 block
     already ended with one blank line, so `## 12.` was appended after it with nothing added. The live keeps the `## 12.`
     heading plus a one-line `> Moved 2026-10-26 …` stub, as §7–§11 do. The alternative — move the separator too and re-add one
     — was not taken. Default: stands.
     (d) ⚠ **§4.9 + §4.10 moved as one block (108 lines, `### 4.9 …` through the blank before `### 4.11`) into the archive between
     §4.8's last paragraph and the `---` before `## 7.`**, so the archive's §4 now runs §4.1–§4.3, §4.5–§4.10 in order; the
     2026-10-25 stub above them ("§4.9/§4.10 (Phase 4) stay below") re-worded to "moved 2026-10-26, next stub", and a second
     `> Moved 2026-10-26 …` stub placed where they stood. §4.11 / §4.12 (Phase 5) stay live. Default: stands.
     (e) ⚠ **Archive renamed with `os.rename` (Phases 0–3 suffix → `-P0-P4.md`; no `git mv`), banner rewritten for Phases 0–4** (it now
     says §4.9 / §4.10 / §12 arrived 2026-10-26 under 207 and lists §8–§12); the 12 old-suffix citations in this file (L3, the
     `> Moved` stubs, 206 (b), the §13.3 table) and the 2 in `README.md` re-pointed by filename substitution only — no
     ledger text rewritten except the README's own two row descriptions (archive row: "Phases 0–4 … §4.5–4.10 … §7–§12"; live
     row: "Phase 5 runs §4.11–§4.12 … Phases 0–4 history"). a `grep -rn` for the old suffix over `src/aiDocs`, `CLAUDE.md` and `.claude` is empty afterwards (this ledger avoids the literal too).
     The alternative — a fresh `-P4` archive file — was not taken (one file, original numbering, 206 b). Default: stands.
     (f) ⚠ **Counts and conservation (`wc -l` / `tr -cd '\r' | wc -c`, one script `C:/tmp/p52_step0.py`, same byte rules as
     206):** this file 11,370 → 9,081 after the moves (−108 −2,185 +4: two stubs, one blank, one re-used heading) → 9,139
     after this entry, the §13 P5-3 paragraph, the §1 / L3 / footer edits (LF, 0 CR); archive 12,151 → 14,444 (+108 +2,185;
     LF, 0 CR); 11,370 + 12,151 = 9,081 + 14,444 − 4 = 23,521. `README.md` 113 → 113 (CRLF). Nothing committed,
     staged or pushed. Default: stands.
     (g) ⚠ **The "As of" line (L3) got a 2026-10-26 lead-in** (the file's own convention for every pass) and the footer's 205 line a
     trailing "§13.2 (1)–(9) defaults stand (207)" so the open-items roll-up matches (7). Not asked for by name; the alternative —
     footer 206 line only — was not taken. Default: stands.

208. ⚠ **2026-10-26 (P5-2 — executor wiring: `DIRECT_REFERENCE` / `SEQUENTIAL_INPAINT` through `PbNodeExecutor.renderStages`, the
     `InpaintRegionCompiler`, the `olio.sd.calibration` record + `PbCalibrationUtil`, node-level `GRAPH_INVALID` / `RENDERER_REJECTED`
     codes, flux2 inpaint `8 / 0.6`; planner spec `C:/tmp/p52-plan.md` §0–§9 + architect addendum §10 (17 findings), implemented by the
     backend-specialist, reviewed by me and the verifier; results §13.4, runs §4.13).** Nothing here is DONE (159 b); the live leg is not run
     (`.39` unreachable all day, §3) — **superseded the same evening by 209: `.39` answered from 16:44 and the live leg ran**. ✓ Rulings implemented at their defaults: §13.2 (1) flux2 inpaint `4 / 1.0` → `8 / 0.6` (T2,
     `capabilities.json:85-86`); (3) `isGraphRejection` kept broad (T9, `SwarmRendererAdapter.java:247` unchanged); (4) `TestP51GraphLive`'s
     portrait FK read via the planned `profile.portrait` (T1, the old `:615` `populate` deleted); (7) `SDUtil.invalidateSession` also clears
     `versionCache` (T0, `SDUtil.java:178`; 205 x) and the `cast.artifactIds()` NON_EMPTY assertion reads `Optional.ofNullable(…).orElse(List.of())`
     (T1, `:294` / `:297`; 205 w); 205 (l) error mapping split into catalogue `GRAPH_INVALID` vs server `RENDERER_REJECTED` with the node text
     (T9). Planner §9 Q1–Q12 implemented at their defaults, re-asked as §13.4 "Yours" (4)–(15). Architect addendum: no default changed;
     its non-optional additions 1 / 4 / 7 / 8 / 11 / 15 taken (plan-time readability probe → `ACTOR_TEXT_ONLY "(portrait not readable)"`;
     `ACTOR_REGION#<actorId>` keys, no bare key; one `MODEL_SUBSTITUTED` reused for the INPAINT-pick fallback; …). ⚠ sub-letters (a)–(ad) and
     (ae)–(am) are the implementer's calls, each reviewed by me; (an)–(az) are mine — (ar)–(az) record the verifier's findings and what I did
     about each. Alternatives not taken and defaults:
     (a) ⚠ **`SwarmRendererAdapter.executeGraph` no longer folds `RenderCalibration`** — the executor records each graph stage through
     `PbCalibrationUtil.recordSample(user, …)` (`PbNodeExecutor.java:1167`, `:1236`) so the sample is written as the caller; the T2I path
     (`:120`) still folds in-process. Alternative: keep the adapter fold too (double-counts, and runs with no user). Default: stands.
     (b) ⚠ **`StageResult.errorDetail`** (`:35-39`) carries the server's node text beside the coded `error`. Alternative: append it to the
     `error` string. Default: stands.
     (c) ⚠ **The final artifact's node role comes from the placeholder tags** (`actora`, `actorb`, … / `location` — corrected by 209 (a):
     the original `actor1..N` collided live under Swarm's letters-only key reduction), not a fixed per-stage role. Default: stands.
     (d) ⚠ **`CalibrationSample` is a top-level class** in `olio/illustration/` (seeded / measured / persisted sources) rather than nested in
     `RenderCalibration`, since `RenderEstimate` and `PbCalibrationUtil` both consume it. Default: stands.
     (e) ⚠ **`RenderEstimate` map keys `references` / `regionPasses` / `regionEffectiveSteps` / `perStage` / `sources` / `complete`; `SOURCE_SCOPE`
     removed**, `SOURCE_NONE` added (`RenderEstimate.java:17`, `:23`); the one caller `PbServiceFacade.java:2770` and `TestRenderEstimate` 6
     updated. Alternative: keep `SOURCE_SCOPE` as a deprecated alias. Default: stands.
     (f) ⚠ **An in-JVM fold over a primed (persisted) entry restarts the in-JVM count at 1**; the row keeps its own `sampleCount`.
     Alternative: continue the persisted count in the JVM. Default: stands.
     (g) ⚠ **`RENDERER_REJECTED` is covered live-only** (needs a server answering with a node failure, T11); offline
     `TestSwarmRendererAdapterErrors` 4 covers `parseNodeFailure`, the `\(Node (\d+): ([^)]+)\)` regex and catalogue `GRAPH_INVALID`.
     Alternative: a fake HTTP server in the test. Default: stands.
     (h) ⚠ **`calibrationModel.json`'s `query` array includes the figure fields** (`server`, `family`, `stage`, `secondsPerStepPerMegapixel`,
     `sampleCount`, `seeded`, `lastSampleAt`) so a list read returns them without a projection. Default: stands.
     (i) ⚠ `PbCalibrationUtil.java` written LF (its `olio/sd/` neighbours are mixed). Default: stands.
     (j) ⚠ **`recordSample` carries `references` and `swarmVersion` onto the row** (`lastReferences`, `swarmVersion`) beyond the planner's
     columns. Default: stands.
     (k) ⚠ **A seeded row is replaced by the first real sample** (no EWMA against the seed; "measured" is keyed on `seeded` / `sampleCount`,
     never on `lastSampleAt` — the timestamp column has a DDL `now()` default). Alternative: fold the first sample into the seed. Default: stands.
     (l) ⚠ **In-JVM keys use the raw server URL; rows use `hostPort`** (`PbCalibrationUtil.hostPort :53`, row name
     `calibration:<family>:<stage>:<host:port>` `:71`). Alternative: normalise both. Default: stands (c06 asserts the row side).
     (m) ⚠ `findRow` public (tests and the facade read it); `findPath`-only, `setCache(false)`, `AccessPoint.find` — never creates. Default: stands.
     (n) ⚠ **`sample()` returns the held in-JVM entry when the row is unreadable** (PBAC-denied or absent), then the family seed. Default: stands.
     (o) ⚠ `RenderEstimate.estimate` tolerates a null samples map / null entries → `SOURCE_NONE`. Default: stands.
     (p) ⚠ `PbPipelineUtil.ROLE_STAGE` javadoc wording (`:108`). Default: stands.
     (q) ⚠ **`TestSdCalibrationRecord` runs as a dedicated `p52CalibrationUser`** (`getCreateUser`), so its `~/Illustration/Calibration`
     group and rows are its own and c01's "reads create nothing" is provable. Alternative: `pbCustomTestUser`. Default: stands.
     (r) ⚠ Test package `olio/sd/` (new directory) next to the util. Default: stands.
     (s) ⚠ `c06_keysNormaliseTheServer` added beyond the planner's c01–c05. Default: stands.
     (t) ⚠ **`PbRenderPlanUtil.persistGraphArtifact` unchanged**; `InpaintRegionCompiler.OVER_BUDGET_UNCUT` (one-actor prompt sent uncut
     over budget, `:22-27`) and `StrategySelector.REASON_NO_SCENE_DIRECT_PICK` (`:25`) added. Default: stands.
     (u) ⚠ **`RendererAdapter.CODE_UNREACHABLE` / `CODE_NO_IMAGES` / `CODE_CANCELLED` / `CODE_RENDERER_ERROR` + `errorCode(String)`**
     (`:24-32`) — string-prefix codes on the existing `StageResult.error` string. Alternative: an enum field. Default: stands.
     (v) ⚠ `Flux2Compiler.usesReferences(Strategy)` public (`:401`; the executor reads it). Default: stands.
     (w) ⚠ **`PbStageFailedException`** (`PbNodeExecutor.java:834`, package-private `PictureBookException` with the HTTP status — 503 for
     `UNREACHABLE`, else 500 — and `stageErrors`). Alternative: a plain `PictureBookException`. Default: stands.
     (x) ⚠ **Stage artifacts and persisted calibration only on the graph path** (addendum row 6): T2I / `TEXT_ONLY` renders write no
     `stage`-role artifact and calibrate in-process only. Default: stands.
     (y) ⚠ **The final image bytes are a separate `data.data`** from the last stage artifact's. Alternative: share the row. Default: stands.
     (z) ⚠ **One `MODEL_SUBSTITUTED` per sequence** (`:1177`, when the INPAINT pick is unresolved and the BASE pick is used; addendum row 8),
     not one per pass. Default: stands.
     (aa) ⚠ **Degradations patched onto the plan artifact are warn-only** — a failed patch does not fail the node (architecture.md: reported,
     never thrown). Default: stands.
     (ab) ⚠ The job DTO carries `stageErrors` (`PbComposeJobUtil.draftRenderDto :795`). Default: stands.
     (ac) ⚠ Region passes gated on `stages.contains(ACTOR_REGION)`, not on the strategy name. Default: stands.
     (ad) ⚠ **Region precondition failures (no box, unreadable reference, no mask) → `ACTOR_FAILED` and continue on the last good image**
     (`:1202`, `:1264`), not a node failure — planner §9 Q5 "degrade". Default: stands.
     (ae) ⚠ `TestPbRenderPlanActors.case04` uses 4 characters (3 BASE + 1 overflow → `ACTOR_REGION`). Default: stands.
     (af) ⚠ **case04 / case05 use a BOOK-scope `olio.pb.style` and in-JVM seeded `.invalid` inventories**, so no live server is contacted and
     the plan is deterministic. Default: stands.
     (ag) ⚠ Explicit priority / box patches on the composition rows (identity + changed fields). Default: stands.
     (ah) ⚠ R4 "no box → `ACTOR_TEXT_ONLY`, never a region pass" as a sub-assertion of case04. Default: stands.
     (ai) ⚠ The kontext case asserts the existing behaviour (no per-actor `ACTOR_TEXT_ONLY` when the family is wholly unresolved). Default: stands.
     (aj) ⚠ **case05 moves the portrait's `data.data` row (`groupId` + `ownerId`) via `RecordUtil.updateRecord` test-side** to make it unreadable
     by `testUser1`, restored before `deleteBook` (`:793`, `:630`). Alternative: build a second user and grant-revoke. Default: stands — it is
     test-side only; no main code bypasses PBAC.
     (ak) ⚠ Measured facts recorded as `///` comments in the tests. Default: stands.
     (al) ⚠ **Out-of-scope core fix kept: `policy/OperationUtil.java` (+7 / −1, CRLF kept)** — `getOperationInstance` rebuilds the cached
     `IOperation` when the reader or search identity differs (`operationIo` map). Without it `OwnerOperation` answers from the first
     `IOContext`'s closed, never-invalidated `CacheDBSearch` in any JVM that resets `IOSystem` (`BaseTest` does per test), which is the whole
     cause of case05's multi-class red. Service7 (one context per JVM) is unaffected. Alternative: revert and mark case05 `@Ignore`-in-set.
     Default: keep — "Yours" (2). The verifier read the fix and recommends keeping it (identity-keyed rebuild, no behaviour change for a
     JVM with one `IOContext`); its own re-run of the set with the fix in place is `C:/tmp/p52-verify-run.log` 133 / 0 / 0 / 0.
     (am) ⚠ `TestP51GraphLive.java:430` prose → "V3 (8 / 0.6) ratified and shipped as the capabilities.json default". Default: stands.
     (an) ⚠ **`PbPipelineUtil.java` restored to LF** after the implementer's save turned it CRLF (HEAD is LF): `C:/tmp/p52_lf_pipeline.py`,
     "1231 CRLF → LF; CR now = 0"; `git diff --stat` 20 insertions / 1 deletion, content only. Every other modified Objects7 file keeps its
     HEAD ending (`OlioFieldNames` / `OlioModelNames` / `OperationUtil` CRLF; the rest LF); new files LF except `calibrationModel.json` (CRLF,
     matching its `olio/sd/` siblings). Default: stands.
     (ao) ⚠ **The regression set was run with `.39` unreachable** (13:13:51, after three failed probes) rather than waiting (159 c), so the
     DB-only classes have evidence today; the 4 `TestPictureBookWorkflow` errors are carried as `.39`-outage reds to re-run, not closed, and the
     23 skips are reported as skips. Alternative: wait for `.39` and run everything once. Default: stands; re-run the full set when `.39`
     answers.
     (ap) ⚠ **`TestP52SequentialInpaintLive` (T11) not written** — a live test authored blind against an unreachable server would be red on its
     first run by construction; it is written and run together when `.39` answers (or `.42`, if you pin it — "Yours" (1)). Alternative: draft
     it now, `test-compile` only. Default: stands.
     (aq) ⚠ **The architect's "rules gap" row** (an `architecture.md` line under "Config: DB-backed vs boot-pinned" distinguishing measured
     from configured values) is not applied — `.claude/rules/*.md` is out of this slice's scope. Default: for your next rules pass — "Yours" (3).
     (ar) ⚠ **Verifier verdict CHANGES-NEEDED** (independent build + 14-class re-run `C:/tmp/p52-verify-run.log` 133 / 0 / 0 / 0 `BUILD
     SUCCESS`, then a diff read): MEDIUM-1 the SDXL plan said `SEQUENTIAL_INPAINT` but `renderStages` would have sent the portrait and the
     location render as references to a family whose compiled graph has no node for them — `patchImages` throws `GraphCompileException`, so a
     3-plus-1-actor SDXL node that rendered TEXT_ONLY on HEAD would fail `GRAPH_INVALID` (a regression); MEDIUM-2 `renderStages` (now
     `:1100-1271`) has zero executed coverage — every offline test stops at the plan or the compiler; LOW-1 the final image was persisted twice
     (as the last `stage`-role artifact and again under the node role); LOW-2 the `stageErrors` detail is lost at the three catch sites (now
     `:215` / `:299` / `:347`), which stamped `e.getMessage()` only; LOW-3 case05 leaves a `~/p52ghost` group behind as `u2`. I took MEDIUM-1, LOW-1, LOW-2, LOW-3
     ((as)–(av)) and re-ran the set (`p52-run-final3.log` 133 / 0 / 0 / 0); MEDIUM-2 stands ((ax)). Alternative: hand the findings back to the
     implementer. Default: stands — the fixes are mine and reviewed by me.
     (as) ⚠ **MEDIUM-1 fixed executor-side, plan-side kept: SDXL still plans `SEQUENTIAL_INPAINT`, and its region passes are text-only
     inpaints** — `renderStages` now gates every reference on `RendererCapabilities.isSupportsReferences()`: BASE sends the location render
     only when the BASE family takes references (`PbNodeExecutor.java:1131-1132` `baseTakesReferences` / `locationRef`), a region pass reads
     and sends the portrait only when the pass family does (`:1203` `regionTakesReferences`; `passRefs` / `passRefIds` are empty lists otherwise, which
     `TestP51GraphLive.t03` proved live on SDXL for BASE and ACTOR_REGION with `Collections.emptyList()`), and the calibration sample records
     the real reference count. `StrategySelector` appends `REASON_REGION_TEXT_ONLY_SUFFIX` (`:28`, `:92`) so the plan's reason says the passes
     carry no portrait; `TestStrategySelector` (`:188` / `:191`: precondition `assertFalse(xlCaps.isSupportsReferences())`, suffix present on
     xl, absent on flux2) and `TestPbRenderPlanActors.case04` (`:507` / `:509`, the same two assertions on the DB-planned sdxl / flux2 plans)
     assert it. This follows design
     §14 L913 (`SdxlCompiler | TEXT_ONLY / BASE / ACTOR_REGION on SDXL`) over the planner's §2.2 wording ("`!supportsReferences` → TEXT_ONLY +
     ACTOR_TEXT_ONLY per actor") — `PbRenderPlanUtil.regionCapable` (`:864`) never checked `isSupportsReferences`, by design. Alternative (B):
     on a no-reference family route overflow actors to `ACTOR_TEXT_ONLY` and the strategy to TEXT_ONLY (plan §2.2 literal; loses the SDXL
     masked pass). Default: (A) as implemented — "Yours" (17).
     (at) ⚠ **LOW-1: the last stage output is no longer written as a `stage`-role image artifact** — `renderPlanned`'s persist loop writes a
     renderGraph artifact for every output and a `stage` image artifact for every output but the last (`:1003`, `if(i == seq.outputs.size() - 1)
     break;` before the image write); the final bytes land once, under the node role. §5.3 ("one `stage` image per intermediate pass, never a copy of
     the final") taken over plan §2.4 (5) ("every output as stage"), which contradicted it. Alternative: keep the duplicate. Default: stands.
     (au) ⚠ **LOW-2: `PbNodeExecutor.errorMessage(e)` (`:498`) appends `[<stage>: <detail>]` per entry of a `PbStageFailedException`'s
     `stageErrors`** so the node's stamped `errorMessage` carries the server's node text (`RENDERER_REJECTED`) or the catalogue reason
     (`GRAPH_INVALID`), not only the summary; the three catch sites are unchanged otherwise. Alternative: a structured `stageErrors` list on the error response DTO
     (a transport shape — Service7's). Default: stands.
     (av) ⚠ **LOW-3: case05 deletes `~/p52ghost` as `u2` in its inner `finally`** (hoisted `ghostGroup`, `AccessPoint.delete(u2, ghostGroup)`
     after the style delete, failure logged not thrown): `p52-run-final3.log:1187` `cleanup ~/p52ghost: true`. Default: stands.
     (aw) ⚠ **Eclipse stub class in `target/classes`, fixed by binding first:** the IDE flagged
     `logger.warn("… {}", planArtifact.get(FieldNames.FIELD_OBJECT_ID))` in `recordExecutorDegradations` (`PbNodeExecutor.java:1310`) as
     ambiguous between `warn(String, Object)` and `warn(CharSequence, Throwable)` (the generic `<T> T get()` — the objects7-reference.md
     overload trap; javac binds it fine) and wrote a `PbNodeExecutor.class` carrying three "Unresolved compilation" markers — a runtime `Error`
     in any Eclipse-built artifact (`docker cp`, the Eclipse Tomcat). Now `String planOid = planArtifact.get(FieldNames.FIELD_OBJECT_ID);` and
     both `warn` calls concatenate. The rest of the P5-2 diff and the four new main files were scanned for the same shape: none. Maven
     `test-compile` was and is exit 0. Alternative: leave it (javac-correct) and rely on a Maven build. Default: stands.
     (ax) ⚠ **MEDIUM-2 not fixed in this slice: `renderStages` has no executed coverage.** The (as) gate, the (at) persist loop and the (au)
     message are verified by reading, by the compiler, and by `TestP51GraphLive.t03`'s earlier live proof of the empty-reference SDXL graphs —
     not by a test that runs `renderStages`. The architect's finding 17 (a package-private adapter / client-factory seam on `PbNodeExecutor`
     so BASE / ACTOR_REGION / cancel / `ACTOR_FAILED` / `MODEL_SUBSTITUTED` run offline against a fake adapter) stays queued for P5-3, and the
     live leg (T11) waits for `.39`. Alternative: pull the seam forward now, before the hand-back. Default: P5-3 — "Yours" (18).
     (ay) ⚠ `ActorTarget.baseArtifactObjectId` / `lastGood.artifactOid` are always null while `renderStages` runs (nothing is persisted until
     every stage is done, §2.4 step 5), so the parameter is dead during rendering — noted (verifier + my read), not changed. Default: stands;
     a P5-3 cleanup candidate.
     (az) ⚠ **The 12-class regression set was not re-run after the review fixes** (`p52-regress.log` predates them). The main-code changes
     are in `renderStages` / `renderPlanned`'s persist loop / `errorMessage` / `recordExecutorDegradations` — all reached only after an SD
     render, which no green class of that set performs with `.39` unreachable — and in `StrategySelector`'s reason text, which only
     `TestPbRenderPlanActors` asserts (re-run green, `p52-run-final3.log`); `TestStrategySelector` is in the offline set. Alternative: re-run
     the 40-minute set now with `.39` still down. Default: stands; the full set is re-run when `.39` answers, where it is owed anyway (ao).

209. ⚠ **2026-10-26 evening (P5-2 live leg — after Stephen's "Oops - I didn't know .39 was down, it's up now - I didn't see you stop to ask
     (you should have) - it's up now. Go ahead and continue with incomplete dev/testing": `TestP51GraphLive` re-run, T11
     `TestP52SequentialInpaintLive` written and run three times, the 5 live `TestPbRenderPlanExecutor` cases, T12 fixture refresh from a live
     capture, the 12-class regression set re-run; results §13.4 "Live leg", runs §4.13).** Nothing DONE (159 b). ✓ Process, owned: when
     `.39` stopped answering I did not stop and say so — I ran the offline set, the regression set and the 208 doc pass around an outage
     Stephen did not know about, and reported it as a working condition. The rule (a live dependency not answering = stop and tell him
     before anything else) is saved as a harness memory; §3's "did not answer at any probe" is corrected to "before 16:44". Sub-letters,
     alternatives not taken, defaults:
     (a) ⚠ **Product defect, found only live: `actor1` / `actor2` reference tags collide under Swarm's letters-only key reduction** (design
     §38.1, which already said `ref0` / `ref1` are rejected) — `GraphInputs.put` refused the second key (`IllegalArgumentException: Input
     …actor2 collides with …actor1: Swarm reduces both to …actor`) before the generator was asked, so every multi-actor BASE failed at
     compile time in T11 run #1 (`p52-t11.log:2551-2554`, 4 errors). Fix: `PbNodeExecutor.referenceName(i)` → `actora`, `actorb`, …
     (`PbNodeExecutor.java:1279`), used for the BASE references (`:1147`) and the ACTOR_REGION pass's single reference (`:1230`).
     Alternative: the actor's name slug as the tag (`duna_de_rosa`), as the P5-0 spike did — distinct for the book 58 cast, not for two
     actors whose names reduce to the same letters, and it leaks the name into the request key. Default: letter suffixes stand. Design §38.1
     gets one sentence naming them (p).
     (b) ⚠ **Why offline never caught it:** no offline test builds two actor references *through the executor* — the `FixtureGraphClient`
     consumers call the compiler with distinct names, and `renderStages` has no offline seam (208 ax, verifier MEDIUM-2 — the right call).
     Default: the P5-3 seam ("Yours" (16)); T11 is the executed coverage until then.
     (c) ⚠ **BASE catch widened** from `GraphCompileException` to `GraphCompileException | IllegalArgumentException` (`:1160`; the
     ACTOR_REGION pass `:1264` likewise) so a compile-time rejection surfaces as a coded `GRAPH_INVALID` stage error with the node text
     instead of escaping raw. Before the widening the raw escape already left the node and run FAILED (the executor's outer catch), so
     this changes the code and message, not the status. Alternative: have `GraphInputs` throw `GraphCompileException`. Default: stands.
     (d) ⚠ **My test defect, T11 run #2** (`p52-t11b.log:2881-2882`, 2 failures): t02 / t03 asserted `stagesPlanned == [BASE,
     ACTOR_REGION#<actorId>]`; the contract (208, the `PbNodeExecutor` DTO) is `stagesPlanned = [BASE, ACTOR_REGION]` with the per-actor key
     only in `stagesRendered`. Fixed in the test (`TestP52SequentialInpaintLive.java:264-265`, `:370`), not the product. Owned: a red test I
     wrote. Run #3 green, 5 / 0 / 0 / 0.
     (e) ⚠ **Observed `RENDERER_REJECTED` texts** — t03 (`p52-t11c.log:2065`): `RENDERER_REJECTED: node 108 SwarmLoadImageB64 (actora):
     ComfyUI execution error … cannot identify image file`; t04 (`:2551`): `500 Render of scene 0 failed: RENDERER_REJECTED: node 102 …`. So a
     non-PNG reference is rejected by the server at execution, not by the catalogue at compile time — `GRAPH_INVALID` stays reserved for
     structure. t03 / t04 assert the coded prefix and the node id, never the server's free text. 208 (g) ("covered live-only") is now
     covered live. Default: stands.
     (f) ⚠ **`system.audit.description` (maxLength 2048) overflow** — T11's fixture setup reads the cast's `olio.store` with a full plan and
     the audit description exceeds 2048 → `RecordUtil - WriterException: Record failed validation` four times per run
     (`p52-t11c.log:511`, `:1202`, `:1951`, `:2678`), the audit row is dropped, nothing else fails. Pre-existing (already in the §4.13
     offline-set `[ERROR` tally as "policy-audit description > 2048"), out of scope, not changed. Default: leave as is; a KnownIssues entry
     on the next sweep — "Yours" (3).
     (g) ⚠ **T12 premise corrected:** the plan read as if T11 would produce generator captures to refresh from. T11 writes PNGs, not
     captures, and the generator drift is a node-multiset change, not a per-literal edit — `TestP51GraphLive.t00`'s drift lines
     (`p52-t00capture.log:70-72`): flux2 prompts moved from `SwarmTextEncodeAdvanced.prompt` to plain `CLIPTextEncode.text`, the sampler
     carries `model_negative: null` instead of `lora_hooks`; SDXL gained `ModelSamplingDiscrete` (16 → 17 nodes, mask loader `100` → `101`)
     and prompts ride `CLIPTextEncodeSDXL.text_g` + `text_l`. So the refresh is capture-and-swap, not edit.
     (h) ⚠ **Capture added to `TestP51GraphLive.t00`** — a nested `RecordingGraphClient extends SwarmGraphClient` records the four
     generator exchanges (base / inpaint × flux2 / sdxl; the inpaint probe sends three grey references so every loader slot is
     exercised) and writes `target/p51/<label>.workflow.json` + `.workflow.request.json`; every `image_base64` is redacted to
     `<N chars base64>` unconditionally; the compiler's markers (`V3SCENEMARKER7731` / `V3NEGATIVEMARKER7732`) and the marker seed 1234567
     are kept in the fixtures because that is what the capturing request sent. Alternative: rewrite the markers to literal text (hides what
     the generator returned and would need a second convention). Default: markers stand.
     (i) ⚠ **Fixtures swapped by hand** — `tr -d '\r' <` from `target/p51/` (Jackson emits CRLF on Windows) into
     `src/test/resources/olio/illustration/p50/`, CR = 0 on all five, never `git add`: `base_flux2.workflow.json` 3061 B + request 431,
     `inpaint_A_flux2.workflow.json` 4364 + request 618, `inpaint_A_sdxl.workflow.json` 3433. `object_info.subset.json` is byte-identical to
     the capture (22 types, already carries `CLIPTextEncodeSDXL` and `ModelSamplingDiscrete`); `placements.json` / `raw_flux2.request.json`
     untouched. The pre-refresh set is backed up at `C:/tmp/p52-p50-fixtures-before/`. Default: stands.
     (j) ⚠ **`base_sdxl` capture not promoted** to a fourth fixture (no consumer; P5-0 captured three) and **no
     `inpaint_A_sdxl.workflow.request.json` added** (none existed). Alternatives: add either. Default: no / no — "Yours" (4) / (5).
     (k) ⚠ **`FixtureGraphClient` made marker-based** — the generator echo is a literal replacement of `MARKER_SCENE` / `MARKER_NEGATIVE` in
     every string input of every node (encoder-class agnostic; a request without a negative gets `""`); `dropSeed` writes
     `MARKER_SEED + 1` because the fixture's `noise_seed` *is* the marker, so "not writing it" is a no-op — **my first offline run was red
     exactly there** (`TestWorkflowCompiler.missingSeedMarkerIsRejected:426`, `p52-offline1.log:191`; owned, fixed); `dropPrompt` writes
     literal text. The `SwarmTextEncodeAdvanced` / first-`CLIPTextEncode` branches are gone. Alternative: keep the class-specific branches
     and add the SDXL pair (three branches tracking a generator that has drifted once already). Default: marker-based stands.
     (l) ⚠ **Literals re-derived** — `TestWorkflowCompiler`: node `6` is `CLIPTextEncode` and its tag rides `text` (was `prompt`); the
     null round-trip asserts `"model_negative":null` (was `lora_hooks`); SDXL 17 nodes (was 16), mask loader `101` (was `100`),
     `CLIPTextEncodeSDXL` with the scene tag on `6`.`text_g` + `text_l` and the negative on `7`. `TestComfyGraph`: `model_negative` on node
     `10`; `INPAINT_SDXL` size 17. Every other literal (18 / 27 nodes, `9` / `8`, loaders `102` / `103` / `104`, `102` mask / `15` init /
     `108` actorA, `(4, 1.0, 16, 8)`, `(4, 0.6)` → 2, `(25, 0.6)` → 10) still held. `TestNodeCatalogue`'s fixture-backed validation needed no
     change. Offline consumers 66 / 0 / 0 / 0 (§4.13 live table). Default: stands.
     (m) ⚠ **`WorkflowCompiler.CLASS_TEXT_ENCODE_ADVANCED` is now referenced by no test** (its definition `WorkflowCompiler.java:52` and a
     `NodeCatalogue.java:24` comment remain; `patchPrompts` is class-agnostic since P5-1). P5-3 REMOVE candidate. Default: remove in P5-3 —
     "Yours" (6).
     (n) ⚠ **T11 design calls**, each the smallest thing that worked: t02's location takes a reference slot (3 actors + location = 4
     references; the third actor overflows to ACTOR_REGION — `box px [696, 154, 287, 819]`, `untouchedMargin [16, 50, 0, 0]`, observation
     only); t05's reader owns its own fresh book (the estimate read must not create that user's calibration group, so the reader cannot
     borrow the owner's); the location reference is the checked-in `./media/flux/landscape1.png` (1024 × 768) copied in as the fixture's
     approved LANDSCAPE, because book 58 scene 0 has no LANDSCAPE render — the loader tries that first (owner, then olio user) and logs the
     fallback (`p52-t11c.log:101-102`, every case) — see (s); the run is looked up through
     `latestRun`; `readGraphEnvelope` projects `artifactText`; the INPAINT pick name is the inventory's listed name; `RendererAdapter.errorCode`
     made package-private for the test's classification assert. Default: stand.
     (o) ⚠ **208 (c) corrected in place** (`actor1..N` → `actora`, `actorb`, …) and **208's header** marked superseded by this entry rather
     than rewritten. Default: stands.
     (p) ⚠ **Design §38.1** gets one sentence naming the executor's tags (`PbNodeExecutor.referenceName`) under the rule that was already
     there. Default: stands.
     (q) ⚠ **The 12-class regression set re-run with `PB_SD_TESTS=1`: 116 / 0 / 0 / 1, `BUILD SUCCESS`, 1 h 14 min**
     (`p52-regression2.log`; §4.13 live table) — the set 208 (ao) / (az) owed, run after every review fix and after (a) / (c), so both are
     closed by it. `TestPictureBookWorkflow` 5 / 5 where the 208-era run had 4 red on `.39` timeouts (its `gpt-oss:120b` key migration and its
     mutation of book 58 stay their own task, untouched here). The one skip is `TestModelResolverLive.case07`'s inventory gate (no Kontext
     checkpoint on `.39`; "This is NOT a pass" by its own message) — carried as a gated skip, not a pass. Every `[ERROR]` line classified in
     §4.13 (deliberate `127.0.0.1:1` negatives, `generalChat` deny-as-miss, test-world setup noise); 16 PNGs from this run plus the 5 from the
     executor-live run opened and described in §13.4. The `TestRenderPlanLive` camera render not producing a close-up at 12 steps is a Phase 8
     observation, not a threshold. Default: stands; nothing DONE.
     (r) ⚠ **Verifier pass on the live leg (its own offline run `p52-verifier-offline.log`, 123 / 0 / 0 / 0, §4.13): PASS-WITH-NOTES, five
     LOW findings, all closed here.** LOW-1 — `referenceName(26)` would have yielded `actor{`, which `ComfyGraph.requireName` rejects with a
     raw `IllegalArgumentException` from *outside* the BASE try (the reference loop sat before it): the loop now runs inside the try, so a
     `Reference` ctor rejection is a coded `GRAPH_INVALID` stage error like any other compile rejection, and `referenceName` refuses an index
     outside `0..25` with a message naming the limit (`PbNodeExecutor.MAX_REFERENCE_NAMES` = 26, `PbNodeExecutor.java:1277-1284`);
     `TestPbRenderPlanExecutor.baseReferenceNamesSurviveSwarmReduction` now builds all 26 (`actora` … `actorz` + `location` / `init` / `mask`
     = 29 image inputs through `GraphInputs`) and asserts `-1` / `26` throw with "letter-suffixed". ⚠ The guard is MY JUDGMENT on top of the
     move-into-try the finding asked for — alternative: move-into-try only (the tag regex would still catch `actor{`, with a less readable
     message). Default: keep the guard. LOW-2 — t03's `KNOWN_CODES` admitted `UNREACHABLE` / `CANCELLED`, so a renderer dropout mid-run
     would have passed as the corrupt-reference lever: t03 now fails on either and asserts the message matches `NODE_ATTRIBUTION`
     (`node \d+ SwarmLoadImageB64 (…actora…)` — the attribution `SwarmGraphClient.parseNodeFailure` builds from *our* graph's placeholder
     tags, a product contract, not the server's free text). LOW-3 — t04 asserts the same attribution, fails on `CANCELLED` as well as
     `UNREACHABLE`, and its "plain `PictureBookException`, no stage errors attached" branch is `fail(...)` instead of a log line. LOW-4 →
     (s). LOW-5 (`CLASS_TEXT_ENCODE_ADVANCED` unreferenced) = (m), P5-3. Re-runs after the fixes: `TestPbRenderPlanExecutor` with its 5 live
     cases **8 / 0 / 0 / 0** (`p52-refname2.log:2198`, `BUILD SUCCESS` `:2205`, 402.2 s; five more lighthouse PNGs opened, §13.4) and T11
     run #4 **5 / 0 / 0 / 0** (`p52-t11d.log:2873`, `BUILD SUCCESS` `:2880`, 1026 s / `Total time: 17:09 min` `:2882`; t03 `OBSERVED …
     node 108 SwarmLoadImageB64 (actora)` `:2065`, t04 `OBSERVED … node 102 SwarmLoadImageB64 (actora)` `:2633` — the two new asserts
     held with no dropout; four PNGs rewritten under `target/p52/`, opened and described in §13.4). Default: stands; nothing DONE.
     (s) ⚠ **Owned: my first draft of §13.4 "Live leg" and of (n) said T11's landscape was "copied from book 58's approved LANDSCAPE
     artifact"** (verifier LOW-4). The log says otherwise in every case: `[p52] no landscape render on book 58 scene 0; using
     ./media/flux/landscape1.png` (`p52-t11c.log:101-102`, `:751-752`, `:1499-1500`, `:2210-2211`, `:2772-2773`). I wrote the sentence from the
     test's intended first choice, not from the run. Both sentences were corrected before this pass was applied; no code change.
     (t) ⚠ **Coverage note following (s):** the `location` reference tag (`PbNodeExecutor.java:1155`) has been exercised live only with the
     checked-in `landscape1.png` stand-in, never with a landscape the pipeline itself rendered. The plumbing is the same either way (a PNG
     into a `SwarmLoadImageB64` loader); what is unexercised is a book's own LANDSCAPE artifact paired with its scene. Alternative: render a
     LANDSCAPE on book 58 scene 0 first — that mutates book 58, which is read-only here. Default: leave; a T11 follow-up when a book-58
     landscape render exists — "Yours" (21).

210. ⚠ **2026-10-28 (Stephen: "I'm okay with the defaults" + "thoroughly review picturebook documentation … completed sections can be
     moved off to archive with a pointer"; this doc pass).** Accepted at their defaults: §13.4 "Yours" (1)–(21), 208 (a)–(az), 209 (a)–(t);
     P5-3 therefore keeps design §28.1 staging (`PICTUREBOOK_DEFAULT` stays `V1`, PB1 stays), removes `CLASS_TEXT_ENCODE_ADVANCED`
     (209 m) and carries the `renderStages` seam (209 b) + `ActorTarget.baseArtifactObjectId` cleanup (208 ay). Moved verbatim to the
     archive (counts in the hand-back): the former "As of" header (→ §0), §1 rows 0–5 (→ §1), the §4 Phase 0 row, §4.4, §4.11, §4.12,
     §5.1–§5.7, §6.A / B / D / E / F, §6.C 2–206, §6.G–§6.N, §13.1–§13.3; live keeps every heading with a stub. Nothing DONE (159 b).
     (a) ⚠ §4.12 and §13.2 (P5-1) moved although §13.4 cites them — P5-1's defaults were accepted 2026-10-26 (207), so it is closed
     under the new terseness rule; alternative: keep them live one more pass. Default: moved.
     (b) ⚠ §4.4 (gate-run table, runs 1–30) moved, heading kept for runs 31+ — cross-phase table, but every row is Phase 1–4;
     alternative: keep live. Default: moved.
     (c) ⚠ Open-but-Stephen's §6.A / §6.D / §6.E / §6.G / §6.L / §6.N moved with a one-line roll-up left live — none changed since
     Phase 0–1, none blocks Phase 5; alternative: keep them live. Default: moved, roll-up stays.
     (d) ⚠ The "As of" paragraph, §1 rows 0–5 and the §4 Phase 0 row rewritten as one-liners (originals archived verbatim); each new
     row restates its own closing state and adds no status. Default: stands.
     (e) ⚠ Terseness rule added to "Working rules for this doc" (≤ ~60-line results, ≤ ~25-line entries, log path + line evidence,
     move on close). Default: stands.
     (f) ⚠ `PictureBookV3Design.md` untouched this pass; archive candidates reported in the hand-back only (§34 ratified decisions →
     a table; §36 / §38 are ground truth and stay). Default: no design edits.

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §6.G, §6.L, §6.H–§6.N (Slice C findings, the nude-fixture question, Phase 0–1 infrastructure items; §6.H / §6.I / §6.J / §6.K RESOLVED, §6.M FIXED) (section numbering unchanged there). Still open there and Stephen's: §6.G (Slice C transport findings 1–12), §6.L (the `generateScene` e2e fixtures render nude), §6.N (`.39` outage log 2026-10-11 → 2026-10-14).

## 7. Phase 1 start prompt (drafted 2026-10-10 while the §4.2 gate ran; **valid as of 22:35** — §4.2 + §4.3 show no new blocker; Ux752 quick gate `VERIFY_OK`)
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §7 (section numbering unchanged there).

## 8. Phase 1 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §8 (section numbering unchanged there).

## 9. Phase 1 follow-ups — product defects (2026-10-12, from 14:40 local)
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §9 (section numbering unchanged there).

## 10. Phase 2 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §10 (section numbering unchanged there).

## 11. Phase 3 — tasks
> Moved 2026-10-25 to `archive/PictureBookV3Implementation-P0-P4.md` §11 (section numbering unchanged there).

## 12. Phase 4 — tasks
> Moved 2026-10-26 to `archive/PictureBookV3Implementation-P0-P4.md` §12 (Phase 4 tasks, §12.1–§12.15; Phase 4 exit confirmed by Stephen 2026-10-26, §6.C 207) (section numbering unchanged there).

---

## 13. Phase 5 — tasks

Scope: design §30 row 5 and §38.4 / §38.5 as corrected (architect F3, 2026-10-23): **the spike measures the contract and captures
Swarm's generated graphs against the live backend; the graph compiler (`WorkflowCompiler` / `ComfyGraph`) is P5-1 and later, product
code in `olio/illustration/`, built from the captures.** Nothing in this section is DONE (159 b); P5-1 starts only after P5-0 is
ratified. **Ratified 2026-10-24 (Stephen, in answer to §13.1 "Yours"):** (9) P5-0 as measured; (7) design §34 Q14 → **(b)** a
per-server calibration record seeded by (a) the spike's numbers; (3) the FLUX.2 inpaint pass — **measure both** (more steps vs
creativity 1.0 inside the mask) in P5-1 before deciding; (1) **real cast if one exists** (an `am7db` book with ≥ 4 real portraits;
AIME-derived only if none, and say so). (2) FACEID out, (4) `useinpaintingencode` excluded, (5) 90-min spike ceiling, (6) no
re-measure of `4actor_inpaint_flux2`, (8) the `architecture.md` spike sentence in his next rules pass — all at their defaults.

**Backlog (found on the way, not in any task yet):** the `composition.book`-FK branch of `PbOrphanUtil` (`:558-560`) — the page /
element rows whose composition names a deleted book — is now exercised by the second case of `TestPbOrphanCompositions` (added in
P5-1 as the first Phase 5 test slice, §6.C 204 y / 205 y); its run evidence is §4.12 "Regression set after run 5". Nothing else
is parked here from P5-1 — the out-of-scope list is §6.C 205 (aa).
**P5-3 — implementation cleanup (added 2026-10-26; Stephen's item 8, §6.C 207; runs after P5-2; results → §13.5, rulings → 209):** "add implementation cleanup to remove/redact/deprecate all the older/replaced picturebook features." Planned against design §28.1 (disable → verify → remove; Stage 4 = Phase 10) and `PbRenderPath.PICTUREBOOK_DEFAULT = V1` until Phase 7, not around them — an inventory-first, stage-aware slice with three verbs. **REMOVE**: code / tests / resources with no remaining caller, each verified by grep, never assumed (candidates: `SceneCompositeUtil` and the Kontext composite path per design §37.4 — Kontext Disable landed in Phase 2; the pre-V3 scene-copy path retired in P4-10; dead PB2-plan artefacts; `imageComposite`-era `SWTxt2Img` paths; anything the archived docs describe that nothing references). **REDACT**: replaced surfaces that must stop being reachable where V3 parity exists — ChapBook is already v3-only at the switch; the PB1 entry points of §28.1 Stage 2 (`/scene/{id}/generate`, `prepare-images`, PB1 settings writes, wizard steps 4–5) stay reachable under `v1` until Phase 7 and are gated only behind `PbRenderPath` as designed; in Ux752, replaced menu / wizard surfaces hide behind `GET /rest/config/render-path`. **DEPRECATE**: what Phase 7 / 10 still needs — `@Deprecated` + WARN on use + a row in a §28.1 removal inventory (file:line, replacement, removing phase). Flow: planner (inventory with grep evidence per item: callers, tests, REST routes, Ux routes, docs) → architect (layering, PBAC untouched, no column drops except via the off-by-default logged property) → implement → Objects7 compile + affected tests, `mvn -o -pl AccountManagerService7 compile`, Ux752 `npx vite build` + `npx vitest run`, Playwright for any Ux surface changed (Docker stack, `ensureSharedTestUser()`) → the P5-2 regression set in one JVM → verifier → doc (§6.C 209, §13.5, design §28.1 / §37.4 inventory updates, LF). First plain question of its plan, with default: pull Phase 10 forward (flip `PICTUREBOOK_DEFAULT` to V3 and remove PB1 now) or keep §28.1 staging (remove only dead code, redact / deprecate the rest; PB1 removal waits for the Phase 7 verify)? Default: keep §28.1 staging — the default is not flipped and PB1 is not deleted unless Stephen answers yes.


### 13.1 P5-0 results — the Phase 5 Swarm spike: `TestP50SwarmSpike` (6 methods, gated, Objects7 test tree, no DB, no LLM) + `P50Swarm` against SwarmUI `http://192.168.1.39:7801` (`0.9.8.4.GIT-5dc94afa`) with `flux2Klein_9b` + `juggernautXL_ragnarokBy` — 2026-10-23 22:40 → 2026-10-24 00:50:05 local (run 1 22:55 stopped; run 2 23:02:08 → 00:12:00, 6 / 6 ran, 29 requests = 26 images + 1 FACEID error + 1 client-cut + 1 WebSocket frames-only; run 3 00:34 → 00:50 re-measure of the cut render: 937 s, 3 of 4 actors)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.1 (P5-0 results; ratified by Stephen 2026-10-24 — the answers are in the §13 scope paragraph above and §6.C 204) (section numbering unchanged there).

### 13.2 P5-1 results — the graph builder: `ComfyGraph` / `GraphInputs` / `WorkflowCompiler` / `NodeCatalogue` / `SwarmGraphClient` / `MaskImage` (Objects7 `olio/illustration/`, product), the raw-graph path of `SwarmRendererAdapter`, the `renderGraph` artifact (`PbRenderPlanUtil.persistGraphArtifact`), `SDUtil` session / version, and the gated live class `TestP51GraphLive` (5 methods, Objects7 test tree, `am7db`, pure SD, no LLM call) against SwarmUI `http://192.168.1.39:7801` (`0.9.8.4.GIT-d74f8ce4`) with `flux2Klein_9b` + `OfficialStableDiffusion/sd_xl_base_1.0` and book 58's three real portraits — 2026-10-24 ~15:30 → 20:46:48 local (live runs 1–4 red at 18:43 / 18:47 / 19:17 / 19:28; run 5 green 19:29:58 → 19:38:39, 5 / 5, 9 requests = 7 images + 2 deliberate rejections; closing regression set 19:41:47 → 20:43:37 **red**, 243 run / 2 failures in the P4 `TestModelResolver` — my regression — fixed and the illustration wildcard re-run green 187 / 187 at 20:46:48)

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.2 (P5-1 results, "Yours" (1)–(9) at their defaults per §6.C 207; its out-of-scope items (5) / (8) — `TestPictureBookWorkflow`'s `gpt-oss:120b` key and its mutation of book 58 — are still open, their own tasks) (section numbering unchanged there).

### 13.3 Documentation consolidation (Part A, 2026-10-25) — results; rulings §6.C 206

> Moved 2026-10-28 to `archive/PictureBookV3Implementation-P0-P4.md` §13.3 (Part A documentation consolidation results; rulings §6.C 206 → 207) (section numbering unchanged there).

### 13.4 P5-2 results — executor wiring: `PbNodeExecutor.renderStages` (BASE, then one ACTOR_REGION inpaint pass per overflow actor), `StrategySelector` → `DIRECT_REFERENCE` / `SEQUENTIAL_INPAINT`, `InpaintRegionCompiler`, the `olio.sd.calibration` record + `PbCalibrationUtil`, node-level `GRAPH_INVALID` / `RENDERER_REJECTED` codes, flux2 inpaint `8 / 0.6` (Objects7 only) — 2026-10-26; rulings §6.C 208 + 209; runs §4.13. **Offline green after the verifier's CHANGES-NEEDED (4 of 5 findings fixed); live leg RUN the same evening once `.39` answered ("Live leg" below): T11 5 / 0 / 0 / 0 on run #3 after one product defect (209 a) and one test defect of mine (209 d), 5 / 0 / 0 / 0 on run #4 after the verifier's five LOW findings (209 r), `TestP51GraphLive` 5 / 5, executor 8 / 8 twice, T12 refreshed, the 12-class regression set re-run with `PB_SD_TESTS=1` **116 / 0 / 0 / 1** in 1 h 14 min (`p52-regression2.log`; the one skip is `TestModelResolverLive.case07`'s inventory gate, no Kontext checkpoint on `.39`; `TestPictureBookWorkflow` 5 / 5 where it was 4 red on `.39` timeouts; 26 more PNGs opened and described); nothing DONE.**

**What was done (per task of `C:/tmp/p52-plan.md` §4; paths under `AccountManagerObjects7/src/`):** T0 `SDUtil.invalidateSession` clears
`versionCache` (`main/.../olio/sd/SDUtil.java:178`). T1 `TestP51GraphLive` — the `cast.artifactIds()` assertion survives the NON_EMPTY
mapper (`:294` / `:297`), the `:615` `populate` deleted (the planned `profile.portrait` read is already there), `:430` prose. T2
`capabilities.json` flux2 `inpaint { 8, 0.6, 16, 8 }` and per-family `calibrationSeed { BASE, ACTOR_REGION }` (flux2 9.2 / 13.8, xl 1.5 / 1.0,
kontext none), `_comment` rewritten with the t02 measure-both result; `RendererCapabilities.getCalibrationSeed(stage)` (`:108-168`, `:313`).
T3 `RenderPlan.Actor.referenceStage` (BASE | ACTOR_REGION), `pickForStage(ACTOR_REGION)` → the INPAINT pick (`:206`); `PbRenderPlanUtil.scaffold`
resolves the INPAINT pick in the same family (`:316-338`), `regionCapable` = `supportsMasks && inpaint != null && inpaintResolved` (`:864`),
overflow → `ACTOR_REGION` only with a box (`:878`), else `ACTOR_TEXT_ONLY` (`:886-889`); a plan-time unreadable portrait → `ACTOR_TEXT_ONLY
"(portrait not readable)"`. T4 `StrategySelector` (`:12-64`): `DIRECT_REFERENCE` `[BASE]` when every referenced actor is BASE,
`SEQUENTIAL_INPAINT` `[BASE, ACTOR_REGION]` when any is ACTOR_REGION; `REASON_REFERENCES_NOT_IMPLEMENTED` deleted (no other consumer).
T5 new `InpaintRegionCompiler` keyed `ACTOR_REGION#<actorId>`; `RenderPlan.compiledForActor(stage, actorId)` (`:158`); `Flux2Compiler` reference
ordinals for BASE-stage actors only (`:92`). T6 `PbPipelineUtil.ROLE_STAGE` (`:108`) and `recordImage(…, backendGraph)` (`:714-722`). T7
`RenderCalibration` keyed `server|family|stage` (`:61`); new `olio/sd/PbCalibrationUtil` (`recordSample :80` — in-JVM fold, then
`makePath(user, "~/Illustration/Calibration")` + `findRow` + patch via the field-name `newInstance` or create via `AccessPoint.create`, failures
WARN + `false`; `sample :176` — in-JVM measured → persisted row → held → family seed; `findRow :234` — `findPath` only, never creates); new
model `olio.sd.calibration` (`models/olio/sd/calibrationModel.json`, inherits `data.directory`, table `A7_olio_sd_calibration_0_1`, row
`calibration:<family>:<stage>:<host:port>`, unit seconds per step per megapixel); `OlioModelNames.MODEL_SD_CALIBRATION`, `OlioFieldNames` +16.
T8 `PbNodeExecutor.renderPlanned` (`:926`) → `renderStages` (`:1100-1271`; line numbers are post-review): BASE with the plan's reference
bytes read as the caller (unreadable → `GRAPH_INVALID: reference <oid> not readable`, node FAILED `:1144` / `:1152`), references sent only
when the BASE family takes them (`:1131-1132`, 208 (as)); then, only when the strategy's stages contain ACTOR_REGION, one inpaint pass per
region actor over the last good image, the portrait sent only when the pass family takes references (`:1203`) — precondition or render
failure → `ACTOR_FAILED` degradation, continue (`actorFailed` `:1227` / `:1244` / `:1252` / `:1265`); INPAINT pick unresolved → BASE pick +
one `MODEL_SUBSTITUTED` (`:1198`); each graph stage calibrated through `PbCalibrationUtil.recordSample` as the user (`:1188`, `:1260`; a
refused write → `calibrationPersisted:false`, never thrown); every stage output persisted as a renderGraph artifact and every intermediate
one as a `stage`-role image artifact, the final once under the node role (`:1003`, 208 (at)); cancel anywhere discards everything; BASE
failure → `PbStageFailedException` (`:845`; 503 for `UNREACHABLE`, else 500) carrying `stageErrors` (`KEY_STAGE_ERRORS :835`,
`KEY_CALIBRATION_PERSISTED :838`), whose per-stage detail the catch sites now stamp into the node's `errorMessage` (`:498`, 208 (au));
`PbComposeJobUtil.draftRenderDto` copies `stageErrors` (`:795`). T9 `RendererAdapter.CODE_*` + `errorCode()` (`:24-32`); `SwarmGraphClient.parseNodeFailure` (`:272`, `:295`,
regex `:45`); `SwarmRendererAdapter` `RENDERER_REJECTED` with the node text (`:178-180`), catalogue → `GRAPH_INVALID` (`:211`);
`StageResult.getErrorDetail` / `getErrorCode` (`:35-39`). T10 `RenderEstimate.estimate(plan, Map<stage, CalibrationSample>)` with per-stage
`sources` and `complete` (`:23`); `PbServiceFacade.java:2770`. **Post-review fixes (mine, §6.C 208 (as)–(aw)):** `renderStages` sends a
reference only to a family whose `RendererCapabilities.isSupportsReferences()` is true, so SDXL `SEQUENTIAL_INPAINT` renders text-only
masked passes instead of failing `GRAPH_INVALID`, and `StrategySelector.REASON_REGION_TEXT_ONLY_SUFFIX` (`:28`) says so on the plan; the
final image is persisted once (no `stage` copy); the node `errorMessage` carries each stage's detail; case05 cleans up `~/p52ghost`; the
Eclipse-ambiguous `logger.warn(String, Object)` in `recordExecutorDegradations` bound to a `String` first (the IDE had written an
"Unresolved compilation" stub into `target/classes`). Diff: 30 files, +1,895 / −162, plus 8 new files (4 main, 4 test). `git diff --cached`
empty; nothing staged, committed or pushed.

**Tests — what is real and what is not covered.** Every assertion in the new and extended tests reads back through `AccessPoint` or the
compiler / selector output (no `typeof`-style or null-only checks — I read each of them, and the verifier read the diff independently):
new `TestInpaintRegionCompiler` 4, `TestSwarmRendererAdapterErrors` 4, `TestSdCalibrationRecord` c01–c06 (`am7db`, as `p52CalibrationUser`:
c01 reads return the 9.2 / 13.8 seeds and create no group and no row; c02 the first sample creates `~/Illustration/Calibration` owned by the
user and a row named `calibration:flux2*:BASE:<host:port>` with every column; c03 the second folds with `ALPHA` 0.3; c04 ACTOR_REGION lands
on its own row; c05 non-positive inputs refused; c06 key normalisation), `TestPbRenderPlanActors.case04_overflowBecomesActorRegionOnMaskFamily`
(`:420`; now also asserts the sdxl reason suffix `:507` and its absence on flux2 `:509`) and `case05_unreadablePortraitIsTextOnlyAtPlanTime`
(`:555`), `TestOperationUtil` 1; extended `TestFlux2Compiler` 16, `TestIllustrationResources` 21, `TestRenderEstimate` 6, `TestRenderPlan` 15,
`TestStrategySelector` 8 (xl precondition + suffix `:188` / `:191`), `TestSwarmGraphClientOffline` 6. **What none of them executes:
`PbNodeExecutor.renderStages` itself** — the reference gate (as), the persist loop (at) and `errorMessage` (au) are verified by reading, by
`test-compile`, and by `TestP51GraphLive.t03`'s earlier live proof that SDXL BASE and ACTOR_REGION compile and render with an empty
reference list; they are not exercised by any test that ran today (verifier MEDIUM-2, 208 (ax)). Runs: §4.13 — offline set after the fixes
**133 / 0 / 0 / 0 `BUILD SUCCESS`** (`C:/tmp/p52-run-final3.log`, 14:27:48 → 14:32:51; before the fixes `p52-run-final2.log` and the
verifier's `p52-verify-run.log`, both 133 / 0 / 0 / 0); regression set **115 run / 0 failures / 4 errors / 23 skipped `BUILD FAILURE`**
(`C:/tmp/p52-regress.log`, 39:48 min, run **before** the review fixes and not re-run after them — 208 (az): the fixed executor paths run
only after an SD render, which none of the set's green classes reaches, and the one class that asserts the changed plan reason,
`TestPbRenderPlanActors`, is re-run green in `p52-run-final3.log`) — the 4 errors `TestPictureBookWorkflow`'s `.39` connect timeouts, the 23 skips
the three SD-gated classes. LLM tier for that run, in words: the local Docker Ollama container through LiteLLM (`qwen3:8b` analysis,
JOSIEFIED-Qwen3 PB); not Azure, not `.42`. The offline set makes no LLM call. No SD call succeeded today; **no PNG was rendered, so none is
described here.**

**The one red I own and fixed:** `TestPbRenderPlanActors.case05:595` red in the multi-class JVM, green solo (`p52-run-final.log` 132 / 1) —
`OperationUtil.getOperationInstance`'s JVM-static cache (§6.C 208 (al)); `TestOperationUtil` red on HEAD, green with the fix; the set re-run
133 / 133.

**Verifier (independent; §6.C 208 (ar)):** CHANGES-NEEDED. Its own build + re-run of the 14-class set: `C:/tmp/p52-verify-run.log` 133 / 0 /
0 / 0 `BUILD SUCCESS`. Findings: MEDIUM-1 SDXL `SEQUENTIAL_INPAINT` would have sent references the family's graph has no node for
(`GRAPH_INVALID` on a node that rendered TEXT_ONLY on HEAD) — **fixed** (as); MEDIUM-2 `renderStages` has no executed coverage — **not fixed**,
seam queued for P5-3 (ax), "Yours" (18); LOW-1 final image persisted twice — **fixed** (at); LOW-2 `stageErrors` detail lost at the catch sites
— **fixed** (au); LOW-3 case05 leaves `~/p52ghost` — **fixed** (av). It also read the `OperationUtil` fix and recommends keeping it (al). The
fixes were not re-verified by the verifier; they were re-run by me (`p52-run-final3.log`) and are reviewed by reading only where no test
reaches them (above).

**Not done at the time of the paragraphs above (all needed an SD server; superseded the same evening):** T11 not written (208 ap), no
`TestP51GraphLive` re-run, no T12, the 5 live executor cases / 2 live `TestRenderPlanLive` cases / `TestModelResolverLive` skipped,
`TestPictureBookWorkflow` 4 red on `.39` timeouts, the regression set not re-run after the review fixes (208 az) — so `renderStages` had no
executed coverage (verifier MEDIUM-2, 208 ax).

**Live leg — 2026-10-26 evening, after Stephen's "`.39` is up now, continue" (§6.C 209; every log line cited in §4.13's live table).**
`.39` answered from 16:44:48 (§3; SwarmUI `0.9.8.4.GIT-d74f8ce4`). LLM gate at every run `route=litellm tier=local` — the local Docker Ollama
container through LiteLLM; **no LLM call was made** by any of the three SD classes. Everything ran as `pbCustomTestUser` (id 186, org 21);
book 58 `catatone-custom-book-6` was read (Duña de Rosa 444, Jideon de Rosa 442, Touvier 443 portraits) and never mutated — each case
builds and deletes its own fixture book (`cleanup … deleted=true` ×5, `p52-t11c.log:703`, `:1451`, `:2162`, `:2724`, `:2870`). The location
reference in every case was the checked-in `./media/flux/landscape1.png` stand-in (1024 × 768, 1,558,104 B): book 58 scene 0 has no
LANDSCAPE render, so the loader's first choice (that scene's selected LANDSCAPE, as owner then olio user) found nothing and fell back
(`[p52] no landscape render on book 58 scene 0; using ./media/flux/landscape1.png`, `p52-t11c.log:101-102`, `:751-752`, `:1499-1500`,
`:2210-2211`, `:2772-2773`) — the earlier draft of this paragraph said "book 58's approved LANDSCAPE"; that was wrong (209 s).
1. **`TestP51GraphLive` re-run after T1: 5 / 0 / 0 / 0** (`p52-p51live.log`, 599.4 s).
2. **T11 `TestP52SequentialInpaintLive` (new, 1128 lines; gated like P51; five cases, each its own fresh V3 book):** run #1 **4 errors** —
   the live-only product defect (209 a: `actor1` / `actor2` tags reduce to one Swarm key; `GraphInputs` refused the second before the
   generator was asked); fixed with `PbNodeExecutor.referenceName` letter suffixes and the BASE catch widened to `IllegalArgumentException`
   (209 c). Run #2 **2 failures** — my `stagesPlanned` assertion against a per-actor key the contract never promised (209 d); fixed in the
   test. Run #3 **`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`, 998.4 s, `BUILD SUCCESS`** (`p52-t11c.log:2873`, `:2880`):
   t01 two actors + location → `DIRECT_REFERENCE`, one BASE, three references, calibration row (219655 ms, `:606`; `wrote` `:620`;
   calibration `:622`); t02 three actors + location → `SEQUENTIAL_INPAINT`, BASE then one ACTOR_REGION pass on the third actor
   (294173 ms, `:1342`; picks `:1346`; base + final written `:1359`; `box px [696, 154, 287, 819] untouchedMargin [16, 50, 0, 0]` `:1360`,
   observation only); t03 the ACTOR_REGION actor's portrait is not a PNG → `RENDERER_REJECTED: node 108 SwarmLoadImageB64 (actora): ComfyUI
   execution error … cannot identify image file` (`:2065`), node degrades to the BASE output (DONE_UNVERIFIED, `ACTOR_FAILED`, base kept
   `:2077`; 219939 ms); t04 a BASE reference is not a PNG → `500 Render of scene 0 failed: RENDERER_REJECTED: node 102 …` in 4272 ms
   (`:2551`), node FAILED with `lastError` naming the plan artifact (`:2567`), run FAILED, no ROLE_BASE; t05 a second user's estimate read
   (`estimate BASE source: measured in-process (3 samples since JVM start)`, `secondsPerStep=53.27`, `:2813`) never creates that user's
   calibration group. **Run #4**, after the verifier's LOW-2 / LOW-3 fixes (t03 / t04 now fail on `UNREACHABLE` / `CANCELLED` and assert
   the `node <id> SwarmLoadImageB64 (actora)` attribution; t04's no-stage-errors branch fails, 209 r): **`Tests run: 5, Failures: 0,
   Errors: 0, Skipped: 0, Time elapsed: 1026 s`** (`p52-t11d.log:2873`), `BUILD SUCCESS` (`:2880`), `Total time: 17:09 min` (`:2882`);
   `[LLM-GATE]` `:55` (same route; T11 makes no LLM call). t01 `strategy=DIRECT_REFERENCE, stagesPlanned=[BASE], stagesRendered=[BASE]`
   in 241509 ms (`:606`; BASE 234321 ms `:514`; `flux2*/BASE sampleCount=7 lastSteps=4 lastReferences=3` `:622`); t02 `SEQUENTIAL_INPAINT,
   stagesPlanned=[BASE, ACTOR_REGION], stagesRendered=[BASE, ACTOR_REGION#3ced0f12-…]` in 292542 ms (`:1342`; BASE 209269 ms `:1206`,
   ACTOR_REGION 77041 ms `:1214`, `inpaint steps 8 creativity 0.6 effective 5` `:1346`, `untouchedMargin [16, 41, 0, 0]` `:1360` — run #3
   was `[16, 50, 0, 0]`, both inside the box-plus-margin contract; ACTOR_REGION calibration `sampleCount=3 lastSteps=5 lastReferences=1`
   `:1363`); t03 Touvier `CORRUPT, 54 non-PNG bytes stamped 64x96` (`:1679`), BASE 214279 ms (`:1954`), ACTOR_REGION rejected
   `RENDERER_REJECTED: node 108 SwarmLoadImageB64 (actora): ComfyUI execution error (Node 108: SwarmLoadImageB64) … cannot identify
   image file` (`:1962`), render returned in 219197 ms with `stagesRendered=[BASE]`, one `stageErrors` entry and
   `degradations=[ACTOR_FAILED: ACTOR_REGION pass for Touvier failed …]` (`:2060-2061`), `t03 OBSERVED ACTOR_REGION failure
   code=RENDERER_REJECTED` with the node attribution (`:2065`) — the LOW-2 assert held; t04 Duña CORRUPT (`:2327`), `render refused in
   4898 ms: 500 Render of scene 0 failed: RENDERER_REJECTED: node 102 SwarmLoadImageB64 (actora)` (`:2631`), `t04 OBSERVED BASE failure`
   (`:2633`), node `lastError` + run 2907 `error` carry the same text (`:2647-2648`) — the LOW-3 assert held, no dropout; t05
   `estimate BASE source: measured in-process (3 samples since JVM start)`, `secondsPerStep=55.76`, `perStage={BASE=223.05}`,
   `seconds=446.09, measured=true, complete=true` (`:2813`). All five cleanups `deleted=true` (`:703`, `:1451`, `:2162`, `:2724`,
   `:2870`); book 58 read only; landscape stand-in `./media/flux/landscape1.png` again in every case (`:101`, `:751`, `:1499`, `:2210`,
   `:2772`). Log noise identical in kind to run #3: 4 × `WriterException: Record failed validation` (`:511`, `:1205`, `:1953`, `:2628`),
   13 deny-as-miss `AccessPoint - One or more query fields were not or could not be authorized` lines (5 of them the `000-data.data`
   landscape lookup, the rest the `olio.llm.chat` / `olio.llm.prompt` `generalChat` lookups that precede `No chat config available`),
   `Index collision` 0, `Column does not exist` 0.
3. **The 5 live `TestPbRenderPlanExecutor` cases: 8 / 0 / 0 / 0** (`p52-executor-live.log`, 396.6 s; five BASE renders 14.7–22.4 s each);
   **again 8 / 0 / 0 / 0** after the verifier's LOW-1 fix (`p52-refname2.log`, 402.2 s; BASE renders 14.6–18.3 s).
4. **T12 fixture refresh:** capture `TestP51GraphLive#t00` 1 / 0 / 0 / 0 (`p52-t00capture.log`; drift confirmed, 209 g); three p50
   fixtures swapped (209 i); `FixtureGraphClient` marker-based (209 k); literals re-derived (209 l); offline consumers run 1 **66 / 1** (my
   `dropSeed` no-op, owned) → run 2 **66 / 0 / 0 / 0** (surefire reports 20:08:57 → 20:09:00).
5. **Regression set re-run, 2026-10-26 20:09 → 21:23 (`p52-regression2.log`): 116 / 0 / 0 / 1, `BUILD SUCCESS`, 1 h 14 min** — the
set 208 (ao) / (az) owed, run after every review fix and after the `referenceName` change (209 a / c), with `.39` answering so the SD-gated
classes rendered instead of timing out: `TestPictureBookWorkflow` 5 / 5 (2582 s; 4 red on `.39` timeouts in the 208-era run),
`TestRenderPlanLive` 5 / 5 with its two SD cases (camera BASE 512 × 512, 12 steps, 5581 ms, `:190453`; TEXT_ONLY BASE 5219 ms, `:190698`),
the executor's five live cases again (14.6–30.6 s per SDXL BASE, `:189733`–`:190362`). The one skip is `TestModelResolverLive.case07`
refusing to pass on an inventory with no Kontext checkpoint (its own message: "This is NOT a pass") — carried as a gated skip, not a pass.
Every `[ERROR]` / `Connection refused` line read and classified: every refusal is against `http://127.0.0.1:1`, the deliberate
unreachable-renderer negatives (`case16 executeNode … threw ProcessingException` `:64491`; `INVENTORY_UNAVAILABLE` degradations `:64488`,
`:188973`, `:188976`, `:190778-190781`; "unreachable stage failed in 36 ms" `:190783-190784`); the `generalChat` `AUDIT DENY` deny-as-miss
(`:190067`, `:190164`, `:190293`) → `No chat config available` → `PbComposeUtil … LLM unavailable; the MIGRATED composition … stands` is the
executor cases' expected no-LLM fallback; `SD server API type 'nonsense' is not recognised` (`:190506`) and the `olio.pb.book` `AUDIT DENY`
on `503d460b…` (`:190496`) are negative cases; `Failed to find root location` / `Root Epoch is null` is test-world setup noise. 16 PNGs
written by this run — 9 `export/pb2_*` / `ki68_*` from `TestPictureBookWorkflow` (`:96386`–`:99323`), 5 `target/p25-render-plan-*`,
`target/p410-camera-render-*`, `target/p24-live-render-*` — plus the 5 `target/p25-render-plan-*` from `p52-executor-live.log` and the 5
from the post-verifier executor re-run `p52-refname2.log`: all 26 opened and described in §13.4 (after T11's four).

**T11 run #3's four PNGs, opened and described (all 1024 × 1024, flux2 Klein 4-step BASE; `target/p52/` — since overwritten by run #4's
four, described below after the regression PNGs):** `p52-t01-direct.png` (1,648,172 B) —
two full-length figures in a narrow wet stone alley between mossy walls under a bright overcast sky: left, a young woman with voluminous
dark curly hair and glasses in a pale pink / white cropped shirt and bikini bottoms, barefoot with an ankle chain; right, a tall bearded man
in a maroon leather jacket, light-blue cargo jeans and red sneakers; heavy posterised grain and halo artefacts, faces blurred (the 4-step
Klein look, as in P5-1). `p52-t02-base.png` (1,522,837 B) — the same alley with three figures, the third at right in a long shiny purple /
violet coat over a blue shirt. `p52-t02-final.png` (1,537,804 B) — visually indistinguishable from the base at thumbnail scale; the inpaint
over box `[696, 154, 287, 819]` at creativity 0.6 / effective 5 steps changed pixels only inside the box plus its 16 px margin (the
`untouchedMargin` measurement), so the pass is confined but, at this step count, not visibly stronger than the base. `p52-t03-base-kept.png`
(1,655,862 B) — the same three-figure composition rendered fresh (the centre man's hair wilder, the right man's coat more lilac), kept as the
node's final because the ACTOR_REGION pass was rejected. Whether an ACTOR_REGION pass at `8 / 0.6` is *visibly* doing work is a Phase 8
(Visual QA) question, noted, not a threshold.

**The 26 further PNGs from the executor-live, regression and executor re-run runs, opened and described** (all SDXL
`sd_xl_base_1.0`; the executor cases render one LOCATION scene — a lighthouse with gulls at dusk — at 1024 × 1024, so those fifteen are
variations on it).
Executor-live run (`p52-executor-live.log`, `target/`): `p25-render-plan-p26c5d193355-p26-defaults.png` — photoreal white lighthouse with a
pale-blue lit lantern on a stone base, a dense gull flock, dark angular basalt blocks in the foreground, pink / grey sky;
`…p26c5d193355-p26-override.png` — painterly, a brown stone tower with a red cap, oversized gulls, a wave crashing over rocks;
`…p25heb6b8612-first.png` — white lighthouse with a black lantern on a pier, misty horizon with a second distant lighthouse, a large blurred
flock; `…p25heb6b8612-second.png` — square brown stone tower with a red cap on a breakwater, pink dusk, gulls; `…p25d21babbd3-degraded.png` —
white lighthouse on a sea wall, grassy land to the right, gulls over a calm sea. Regression run: `…p26c98db8376-p26-defaults.png` (30555 ms,
1,690,099 B, `:189733`) — white lighthouse on a pier wall with wave spray, gulls perched on rocks, photoreal; `…p26c98db8376-p26-override.png`
— a stone blockhouse topped by a small white tower, gulls, pink / mauve sky; `…p25h11ee9856-first.png` — white lighthouse atop a grassy
clifftop wall, gulls standing on the grass, long-exposure calm sea; `…p25h11ee9856-second.png` — tall white / dark tower on a grassy headland,
many gulls standing in shallow water in the foreground; `…p25db653cc57-degraded.png` — distant lit lighthouse on a headland, gulls on dark
rocks in the foreground, surf. Executor re-run after the verifier's LOW-1 fix (`p52-refname2.log`, `target/`):
`…p26c84ad7313-p26-defaults.png` — photographic, a white lit lighthouse on a dark basalt breakwater, a dense gull flock, peach / grey dusk;
`…p26c84ad7313-p26-override.png` — painterly, a dark stone tower on a green headland with two houses, heavy surf on a stone quay,
yellow-orange sky; `…p25hbeb4246a-first.png` — photographic, a red-capped white / black-banded lighthouse on a low breakwater, rough
grey-blue sea, violet-pink sky; `…p25hbeb4246a-second.png` — a dark brick tower with a green lantern on a pier, three tiny figures on it,
a dark rocky shore; `…p25d8bc25996-degraded.png` — painterly, a dark cylindrical tower on a fenced concrete pier, amber-lit cloud, calm
reflective water. `TestRenderPlanLive` (512 × 512, 12 steps): `p410-camera-render-p410c9639a63b.png` — flat vector / poster
style, a striped orange-and-white lighthouse against a navy sky, three white gulls, stylised wave lines, a bordered frame — the camera text
`close up, over shoulder, 35mm lens, shallow depth of field` did not produce a close-up or shallow focus at 12 steps (a Phase 8 observation,
not a threshold); `p24-live-render-p24rb00ad4bb.png` — flat graphic, peach sky, two black birds, a black cliff silhouette with a striped tower
on top, stylised waves. `TestPictureBookWorkflow` exports (`export/`; composites 1024 × 768, portraits 1024 × 1024):
`pb2_level1_composite_00e3b6ea….png` — two men crouched in a wet alley over scattered colourful tiles / cards: left an older bearded man with
wild brown hair in a maroon leather jacket, light-blue jeans, pink socks and shoes; right a young East-Asian man in a shiny lilac trench coat
over a white shirt, dark trousers; polaroid-style white border; `pb2_level2_withLandscapeRef_c0c76ffa….png` — the same two figures and pose,
a wider alley with building façades behind, no border; `pb2_level2_withoutLandscapeRef_3d69dad9….png` — the same two, a plain concrete alley
with a sky gap behind; `pb2_fresh_composite_64a0e175….png` — a different cast: left a young Black man in a glossy red jacket, red boots and
houndstooth shorts, right a muscular man in a yellow shirt over a green top, blue jeans, purple polka-dot gloves, the same alley / tiles
composition; `pb2_fresh_portrait_Jideon_0594d12c….png` — a young Black man, short hair, cream T-shirt with an "H" logo, houndstooth shorts,
on an industrial rooftop; `pb2_fresh_portrait_Touvier_97be9993….png` — a smiling man with a buzz cut in a yellow suit, blue polka-dot tie,
blue gloves, seated in front of vintage cars; `ki68_step4_…` / `ki68_step20_…` / `ki68_step40_….png` — the maroon-jacket + lilac-coat alley
scene at 4 / 20 / 40 steps: step 4 clean and soft, step 20 sharper with more detail (a scratch on the older man's face, more tiles), step 40
similar with a tin can being poured; composition stable across step counts, detail increasing. All 26 coherent; no blank or garbage frame.

**T11 run #4's four PNGs (`target/p52/`, overwriting run #3's; all 1024 × 1024, flux2 Klein 4-step BASE), opened and described:**
`p52-t01-direct.png` (1,433,452 B) — two full-length figures in a narrow wet cobbled alley between stone walls under a bright overcast
sky: left a young woman with voluminous dark curly hair and pink-rimmed glasses in a pale floral cropped shirt and matching floral bikini
bottoms, barefoot with a yellow-green anklet; right a tall bearded man with dark tousled hair in a maroon leather jacket over a dark top,
light-blue cargo jeans and pink / red sneakers; heavy posterised grain and halo artefacts, faces soft (the 4-step Klein look).
`p52-t02-base.png` (1,476,397 B) — the same alley, three figures: the curly-haired woman at left in a pale crop top and patterned bikini
bottoms, barefoot; the bearded maroon-jacket man centre in light-blue jeans and red sneakers; at right a man in a long shiny purple /
violet coat over a blue shirt and dark trousers; blurred passers-by behind; faces blurred. `p52-t02-final.png` (1,489,642 B) — visually
indistinguishable from the base at thumbnail scale; the inpaint stayed inside the box plus margin (`untouchedMargin [16, 41, 0, 0]`).
`p52-t03-base-kept.png` (1,629,873 B) — the same three-figure composition rendered fresh: the woman at left in a white / pink crop top
and patterned bottoms, a more muscular build; centre man in a maroon jacket over a black tee, blue jeans, red / pink shoes; right man in
a shiny purple coat; a brighter washed-out overcast sky and stronger posterisation; kept as the node's final because the ACTOR_REGION
pass was rejected. All four coherent; no blank or garbage frame. Totals for the live leg: 4 (run #3) + 26 + 4 (run #4) = 34 PNGs opened.

**Still not run / queued:** the `renderStages` offline seam (P5-3, 209 b); `TestPictureBookWorkflow`'s `gpt-oss:120b` key migration (its own
task).

**Out of scope, noted, not changed** (implementer + verifier + my review): `ActorTarget.baseArtifactObjectId` / `lastGood.artifactOid` are
always null while rendering (208 ay); the spec's "3 approved portraits" vs case04's 4 actors (208 ae); `CachePolicyUtil.evaluateResourcePolicy` discards the urn read (`recU` is always
`resource.hash()`); ownership alone still reads a `data.data` row moved into another user's private group (the case05 fixture relies on it);
`PbArtifactUtil.listChain` returns an artifact whose `data` FK is unreadable while a per-record `find` denies; `TestPictureBookWorkflow`'s
`gpt-oss:120b` key and its mutation of book 58 (§13.2 (5) / (8), their own tasks); **from the live leg:** the `system.audit.description`
2048-char overflow on full-plan `olio.store` reads (4 dropped audit rows per T11 run, 209 f); `WorkflowCompiler.CLASS_TEXT_ENCODE_ADVANCED`
now unreferenced by any test (209 m, P5-3 REMOVE candidate).

**Yours (plain questions, defaults; the 208-era list re-numbered — (1) "wait for `.39` or pin `.42`" is moot, `.39` answered):**
(1) Keep the `OperationUtil` identity-keyed rebuild + `TestOperationUtil` (208 al), or revert them? Default: keep. (2) Add the architect's
one-line `architecture.md` rule (measured values such as calibration are per-server data, not configuration — they live in a record, never
in `web.xml` / init-params) in your next rules pass? Default: yes, your pass, not mine. (3) The `system.audit.description` 2048 overflow
(209 f): leave as is and add a KnownIssues entry on the next sweep, or widen / truncate the audit description now? Default: leave, note
in KnownIssues. (4) Promote the captured `base_sdxl` graph as a fourth p50 fixture (209 j)? Default: no. (5) Add an
`inpaint_A_sdxl.workflow.request.json` fixture (209 j)? Default: no. (6) Remove `WorkflowCompiler.CLASS_TEXT_ENCODE_ADVANCED` in P5-3
(209 m)? Default: yes. (7) `referenceStage` as a field on `RenderPlan.Actor` (planner Q1)? Default: yes. (8) `DIRECT_REFERENCE` enabled in
this slice (Q2)? Default: yes. (9) `SEQUENTIAL_INPAINT` stages `[BASE, ACTOR_REGION]`, HARMONIZE off (Q3)? Default: yes. (10) BASE under
both reference strategies is a reference-image render, not init-image-from-location (Q4)? Default: yes. (11) A failed ACTOR_REGION pass
degrades (`ACTOR_FAILED`, last good image kept, node `DONE_UNVERIFIED`, auto-approve vetoed) rather than failing the node (Q5) — now
exercised live by t03? Default: degrade. (12) BASE and each pass persisted as `stage`-role artifacts plus the final under the node role
(Q6)? Default: yes. (13) Calibration rows per user at `~/Illustration/Calibration`, written as the caller (Q7 (A)) — not per-org olio
principal (B) nor deployment-global (C)? Default: (A). (14) Model name `olio.sd.calibration`, inherits `data.directory` (Q8); unit seconds
per step per megapixel with `references` on the row (Q9); seeds flux2 BASE 9.2 / ACTOR_REGION 13.8, sdxl 1.5 / 1.0, kontext none (Q10)?
Default: yes to all three. (15) `RENDERER_REJECTED` → HTTP 500, not 502 (Q11) — t04 observed the 500? Default: 500. (16) The
`renderStages` offline adapter-factory seam (architect 17; verifier MEDIUM-2; 209 b) in P5-3, or before the hand-back closes? Default: P5-3.
(17) SDXL with an overflow actor: keep (A) `SEQUENTIAL_INPAINT` whose region passes are masked text-only inpaints (no portrait sent; design
§14 L913), or (B) overflow actors become `ACTOR_TEXT_ONLY` on a family with no reference support (208 as)? Default: (A). (18) Calibration
persisted only on the graph path; T2I / `TEXT_ONLY` in-process only (208 x)? Default: stands. (19) The letter-suffix reference tags
`actora` / `actorb` / … (209 a) — keep, or a name-slug scheme? Default: keep. (20) The fixture markers kept verbatim in the p50 fixtures
(209 h) — keep, or rewrite to literal text? Default: keep. (21) T11's location reference has only ever been the checked-in
`landscape1.png` stand-in because book 58 scene 0 has no LANDSCAPE render (209 s / t). Render one there (mutates book 58) or point T11 at
another book of yours that has one, so the slot is exercised with a pipeline-rendered landscape? Default: no — leave T11 on the stand-in
and revisit when such a render exists.

**Answered 2026-10-28:** (1)–(21) accepted at their defaults (Stephen: "I'm okay with the defaults"; §6.C 210).

Next: **P5-3 implementation cleanup** (§13 P5-3 paragraph; results → §13.5, rulings → 211): inventory first, then REMOVE / REDACT /
DEPRECATE; keep design §28.1 staging (`PICTUREBOOK_DEFAULT` stays `V1`, PB1 stays — "Yours" (1)–(21) defaults, 210); queued inside it:
the `renderStages` offline adapter-factory seam (209 b), `ActorTarget.baseArtifactObjectId` (208 ay), `CLASS_TEXT_ENCODE_ADVANCED`
removal (209 m). §4.13 / §13.4 / 208 / 209 move to the archive in the P5-3 doc pass (terseness rule).

Open: nothing awaiting an answer — every default through 209 accepted 2026-10-28 (210); archive §6.A / §6.D / §6.E / §6.G / §6.L / §6.N
remain Stephen's, unchanged since Phase 0–1.
next §6.C number 211.
