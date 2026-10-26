# aiDocs — Documentation Index

**Where the truth is:** current PictureBook design = `PictureBookV3Design.md`; current build state =
`PictureBookV3Implementation.md` (§1 status, §6.C rulings, §13 current phase); current defects/backlog =
`KnownIssues.md`.

Design, plan, and reference docs for AccountManager7. Behavioral/working-discipline rules live in
`../../.claude/rules/llm-conduct.md`; architecture in `../../.claude/rules/architecture.md`.

Status legend: **active** (current, in use) · **reference** (living design/domain reference) ·
**uncertain** (status unclear — needs Stephen's call before archiving). Superseded plans, completed
implementation plans, dated issue logs, and session handoffs live in `archive/` (moved, never deleted;
each archived file opens with a `> Archived <date>: …` line saying what supersedes it).

## Frontend (Ux752)

| File | Purpose | Status |
|---|---|---|
| `Ux752Plan.md` | Ux752 focused-refactor standing orders / guiding principles (parent doc) | active |
| `Ux752ImplementationPlan.md` | Ux752 file-by-file implementation plan (4 files) | active |
| `ConnectionRefactorPlan.md` | Connection model refactor (`system.connection`; backend complete; Ux752 client migration off inline `serverUrl`/`apiKey`/`requestTimeout` — caller list in its "Status 2026-10-07" section) + Ux752 list-view bug fixes (2026-06-17) | active |
| `UxFeatureFlagDesign.md` | Feature profiles / flags design; D1 is the reference for the "read paths must not create" rule (`../../.claude/rules/architecture.md`, Ux752 `CLAUDE.md`, `core/featureProfile.js`) | reference |
| `../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md` | ISO 42001 UI gap analysis + backend backlog (status refreshed 2026-10-07); cited by root `CLAUDE.md` and `ISO42001/README.md` | active |

## Backend / platform

| File | Purpose | Status |
|---|---|---|
| `KnownIssues.md` | **Current** known-issues / backlog. Runs to **KI-91** (next free **KI-92**); numbering is global and never reused. KI-83..87 carried from `archive/IssueLog-2026-09-22.md`, KI-88 from the P4-14 gate (2026-10-23); KI-89/KI-90 carried from `archive/IssueLog-2026-08-25.md` and KI-91 from `archive/PictureBookWorkflowOverhaul.md` + `archive/PictureBookSdConfigRefactor.md` (2026-10-25). The 16 closed entries with no inbound citations live in `archive/KnownIssues-closed.md`; one-line stubs stay here so `KI-NN` greps still hit | active |
| `PageIndexDesign.md` | PageIndex hierarchical TOC index for Objects7 — core engine design (implemented & verified, 2026-07-07) | reference |
| `PageIndexIntegrationPlan.md` | PageIndex REST/chat/MCP/Ux752 integration — phased plan mirroring the vector pattern (prerequisite + Tiers 1-3 all complete and live-verified 2026-07-09) | reference |
| `SCIM.md` | SCIM 2.0 → AccountManager model mapping reference | reference |
| `Objects7DeepReference.md` | Explanatory tour of Objects7 (design philosophy, providers, vault, query API, PBAC, Olio map system, validation, bulk ops, attributes, credentials, journaling, file layout) — moved out of `.claude/rules/objects7-reference.md` 2026-09-29; the rules file keeps only the gotchas | reference |
| `Service7Reference.md` | Explanatory tour of the REST layer (Jersey/JAAS/JWT, per-service route tables, WebSocket, web.xml, pagination, integration testing) — moved out of `.claude/rules/service7-reference.md` 2026-09-29 | reference |
| `DockerComposeDesign.md` | Single-container Docker Compose (Service7 + Ux752 + nginx) — design rationale, what is verified, storage map (`am7-data`/`am7-certs`), known follow-ups; config/doc accuracy audit 2026-09-01 | active |
| `dockerDevSetup.md` | **Runbook:** build & start a fresh Docker AM7 install end-to-end (build → first boot → setup token → initial setup → verify), day-2 ops, config/storage map, E2E, troubleshooting. §12 is the LiteLLM + Langfuse `llmproxy` runbook; §13 the in-stack Ollama (`--ollama`) profile. Companion to `DockerComposeDesign.md` and `LiteLLMLangfuseIntegrationDesign.md` (which hold the *why*) | active |
| `../AccountManagerService7/futures.md` | Service7 futures (Tomcat → application container, etc.); ideas only, nothing scheduled | reference |
| `../AccountManagerAgent7/CHAIN_PLAN.md` | Agent chain plan — implemented (see `ChainExecutor`); kept for the design rationale | reference |

## PictureBook / ChapBook / Games

| File | Purpose | Status |
|---|---|---|
| `PictureBookV3Design.md` | **PictureBook V3 — canonical design** (composition model, Swarm/Comfy graph compilation, migration §28, phasing §30, decisions §34, traceability §35, Swarm inventory §36, Kontext §37, spike contract §38) | active |
| `PictureBookV3Implementation.md` | **PictureBook V3 — live build ledger** (status §1, service probes §3 — P5+ probes append there, Objects7 gate-run table §4.4, Phase 4/5 gates §4.9–§4.12, deviations §5, open asks §6.A–§6.N, rulings ledger §6.C, Phase 4 §12, Phase 5 §13). Phases 0–3 history is in `archive/PictureBookV3Implementation-P0-P3.md` with the original section numbers; the live file keeps one-line stubs at each moved section | active |
| `PictureBookImageGenerationDesign.md` | G-numbered image-generation gap analysis (§2, with `[Fixed …]` annotations) + decisions (§3); V3 docs and source cite its G-ids, so it stays a live cross-reference target | reference |
| `PictureBook2Plan.md` | **PictureBook 2.0 design of record** for what V3 is built on: workflow graph model (§2), Books universe / one world per book (§3), the three blockers (§4), per-book PBAC role model (§5). Its questions are closed (Appendix D; §0 corrected 2026-10-25); V3 supersedes §6 (Comfy client) and §6b (canvas). Build state: `archive/PictureBook2ImplementationState.md` | reference |
| `PictureBookDesign.md` | **PB1** (legacy group+meta book) storage model, `resolveImageUrl`, reader/export behaviour — the only description of it. PB1 is still the default render path until V3 Phase 10 / `PictureBookV3Design.md` §28.1 Stage 4 removes it; archive then. "PB1/PB2" names two axes (book/world model vs workflow graph) — see `archive/PictureBookWorkflowOverhaul.md` §3 | reference |
| `PictureBookSeriesChaptersPlan.md` | **Series / chapters (N-series) implementation plan** (authored 2026-09-20, IN PROGRESS 2026-09-23) — one Olio world per series with baseline + per-chapter shadow cast, `sourceRange` chapters, heading detection, series/chapter canvas views. Re-derived against the post-W-series code; its §1 table lists the overhaul doc's stale `file:line` refs. KI-83..87 are its leftovers | active |
| `PictureBookAsyncJobDesign.md` | **Async background jobs** for the long PictureBook/ChapBook operations — shared `AsyncJobRegistry` + `JobService` polling, extraction checkpoint/resume across a restart, ChapBook progress & cancel. Closes KI-49 (the "504 with no server-side error" was nginx's `proxy_read_timeout`, not an app exception) | active |
| `RPG.md` | Turn-based RPG design & build plan (living design document) | reference |

## Chat / prompt / conversation quality

| File | Purpose | Status |
|---|---|---|
| `chatRefactor.md` | Chat & prompt template system — primary design reference | reference |
| `ConversationQualityPlan.md` | Conversation-quality backend plan (Phases 0-6 shipped; 5.1 deferred) | reference |
| `ConversationQualityBaseline.md` | Conversation-quality metric baseline (regression reference) | reference |
| `LiteLLMLangfuseIntegrationDesign.md` | LiteLLM (OpenAI-compatible proxy) chat dialect + Langfuse observability — design **and as-built record**. B1/B2/B3/B5 done, B4 partial (verified 2026-09-14); §6 "As-built" carries the measured concurrency-cap, timeout-ladder and behavioural-divergence findings, plus the tracked Objects7 follow-up that blocks repointing existing OLLAMA chatConfigs at the proxy | active |

## Memory subsystem

| File | Purpose | Status |
|---|---|---|
| `MEMORY_INTEGRATION_DESIGN.md` | Automatic memory injection in conversations — design reference | reference |
| `MemoryKeyframeDecouplingPlan.md` | Memory / keyframe decoupling (2026-05-30) — implemented; remaining test/doc items audited 2026-10-07 (its §8) | reference |

The Ux7-era API/domain notes that used to live here as `MEMORY.md` are now
`archive/MEMORY-ux7-api-reference.md`; its content-policy block moved to the root `CLAUDE.md`
("Project content policy"). The small auto-loaded user memory index at
`~/.claude/projects/<project>/memory/MEMORY.md` is a different file and is not part of this tree.

## ISO 42001

See `ISO42001/README.md` for the ISO doc set (8 docs + its own README; the old Ux75-parented
`ISO42001Plan.md` is archived). The UX gap analysis is listed under Frontend above.

## Archive (`archive/` — superseded/completed, moved never deleted)

| File | Why archived |
|---|---|
| `BackendPlan.md` | Ux75-era backend plan; Status "Priorities 2-6 COMPLETE, 1 separated" — work done |
| `ISO42001Plan.md` | Ux75-parented dashboard plan; superseded by `ISO42001/` subdir + Ux752 gap analysis |
| `Ux7Redesign.md` | Ux7→Ux75 redesign/port status doc; Ux75 line superseded by Ux752 |
| `Ux75ImplementationPlan.md` | Ux75 implementation plan/status; Ux75 superseded by Ux752 |
| `OpenIssues.md` | March 2026 bug-fix sprint list; superseded by current `KnownIssues.md` |
| `MemoryOllamaInvestigation.md` | Issue #14 diagnostic plan; overtaken by later memory refactor work |
| `PictureBookImplementationPlan.md` | Picture Book impl steps; feature built (per Design "backend COMPLETE") |
| `PictureBookRevisions.md` | Picture Book revision plan; superseded by post-impl issue closeout |
| `PictureBookTestPlan.md` | One-off Picture Book test-execution plan (session artifact) |
| `PictureBookIssues-2026-03-31.md` | Dated Picture Book post-implementation issue log |
| `PictureBookNextSession.md` | One-off session handoff prompt |
| `MCP.md` | MCP integration plan; MCP transport now exists in Service7 — work landed (Stephen's call) |
| `chatRefactor2.md` | Phase-2 chat plan targeting the now-deprecated `AccountManagerUx7/client/` (Stephen's call) |
| `MemoryRefactor2.md` | Feb-2026 draft superseded by the May memory decoupling/integration docs (Stephen's call) |
| `PictureBook2ChapBook-Handoff-2026-09-01.md` | PB2/ChapBook session handoff; superseded by `PictureBookV3Design.md` §28/§30 + `PictureBookV3Implementation.md` §1 (archived 2026-10-23) |
| `PictureBook2ChapBookGapAnalysis-2026-08-31.md` | PB2/ChapBook gap analysis; superseded by `PictureBookV3Design.md` §30 + `PictureBookImageGenerationDesign.md` §2 (archived 2026-10-23) |
| `PictureBook2ChapBookRemediationPlan.md` | PB2/ChapBook remediation plan (14-issue closeout 2026-08-28); history only (archived 2026-10-23) |
| `IssueLog-2026-09-22.md` | N-series UAT issue log; superseded by `PictureBookSeriesChaptersPlan.md` + V3 §7/§28; its five leftover items carried to `KnownIssues.md` KI-83..87 (archived 2026-10-23) |
| `CanvasLibraryResearchPrompt.md` | Research prompt that answered `PictureBook2Plan.md` Appendix D Q16; history only (archived 2026-10-23) |
| `cardGameCorrection.md` | Ux752 card-game gap analysis/correction prompt (was `AccountManagerUx752/aiDocs/`); correction applied — `cardGame.js:18`, `e2e/cardGame.spec.js:124-127` (archived 2026-10-23) |
| `PictureBookV3Implementation-P0-P3.md` | Phases 0–3 of the V3 build ledger (§2, §3 probes, §4.1–4.3 and §4.5–4.8 gate runs, §7–§11 task tables), original section numbers (archived 2026-10-25, superseded by `PictureBookV3Implementation.md` §1/§6.C) |
| `KnownIssues-closed.md` | The 16 closed KnownIssues entries with no inbound citations (KI-5, 6, 26, 41, 43a, 46, 50, 51, 52, 54, 55, 57, 58, 73, 75, 76); numbering global with `KnownIssues.md`, which keeps one-line stubs (archived 2026-10-25) |
| `PictureBook2ImplementationState.md` | PB2/ChapBook build-state log through 2026-09-01 (archived 2026-10-25, superseded by `PictureBookV3Implementation.md` §1) |
| `PictureBookUxAndOlioModelPlan.md` | Ux + Olio-model plan, Parts A–E; nothing in B–C built, Part D never ratified (archived 2026-10-25, superseded by `PictureBookV3Design.md` §7/§8/§34) |
| `imageComposite.md` | Raw two-person Flux Kontext composite prompt; still cited by comments in `SDUtil.java`, `SWTxt2Img.java`, `SWUtil.java`, `flux2Defaults.json` (archived 2026-10-25, superseded by `PictureBookV3Design.md` §37/§38) |
| `PictureBookV3Notes.md` | V3 pre-design notes; design goals G1–G10 (`## 3.1 Primary goals`, L82–149 there) are still cited by bare id in the design (archived 2026-10-25, superseded by `PictureBookV3Design.md` §35 traceability) |
| `PictureBookWorkflowOverhaul.md` | Workflow graph overhaul (2026-09-19); W1–W5 done and verified; PB1/PB2 two-axes explanation in §3; open Q1/Q3/Q4/Q5/Q10 carried to KI-91 (archived 2026-10-25, superseded by `PictureBookV3Design.md` §7/§28/§34 + `PictureBookSeriesChaptersPlan.md`) |
| `PictureBookSdConfigRefactor.md` | One common `olio.sd.config` + per-scene delta; implemented 2026-08-03, KI-38; its open tidy-ups are KI-91 (archived 2026-10-25, superseded by `PictureBookV3Design.md` §4/§4.1 + `TestPbConfigPrecedence`) |
| `IssueLog-2026-08-25.md` | UAT issue log 2026-08-25; Issues 1–10 fixed/verified; leftovers are KI-89/KI-90 (archived 2026-10-25, superseded by `KnownIssues.md`) |
| `MEMORY-ux7-api-reference.md` | Ux7-era API/domain notes (was `MEMORY.md`); the content-policy block moved to root `CLAUDE.md` (archived 2026-10-25, superseded by `.claude/rules/model-api.md` + `troubleshooting.md`) |
| `ChapBookReviewRedesign.md` | ChapBook review redesign draft (was `AccountManagerUx752/aiDocs/`); mostly built, reader now owned by V3 (archived 2026-10-25, superseded by `PictureBookV3Design.md` §24/§26) |
