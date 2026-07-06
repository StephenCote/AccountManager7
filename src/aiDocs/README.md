# aiDocs — Documentation Index

Design, plan, and reference docs for AccountManager7. Behavioral/working-discipline rules live in
`../.claude/rules/llm-conduct.md`; architecture in `../.claude/rules/architecture.md`.

Status legend: **active** (current, in use) · **reference** (living design/domain reference) ·
**uncertain** (status unclear — needs Stephen's call before archiving). Superseded plans, completed
implementation plans, dated issue logs, and session handoffs live in `archive/`.

## Frontend (Ux752)

| File | Purpose | Status |
|---|---|---|
| `Ux752Plan.md` | Ux752 focused-refactor standing orders / guiding principles (parent doc) | active |
| `Ux752ImplementationPlan.md` | Ux752 file-by-file implementation plan (4 files) | active |
| `ConnectionRefactorPlan.md` | Connection model refactor + Ux752 list-view bug fixes (2026-06-17) | active |

## Backend / platform

| File | Purpose | Status |
|---|---|---|
| `KnownIssues.md` | **Current** known-issues / backlog (2026-06-26) | active |
| `SCIM.md` | SCIM 2.0 → AccountManager model mapping reference | reference |

## Games / RPG

| File | Purpose | Status |
|---|---|---|
| `RPG.md` | Turn-based RPG design & build plan (living design document) | reference |
| `PictureBookDesign.md` | Picture Book feature — surviving design/architecture reference | reference |

## Chat / prompt / conversation quality

| File | Purpose | Status |
|---|---|---|
| `chatRefactor.md` | Chat & prompt template system — primary design reference (incl. NO-CENSORSHIP directive) | reference |
| `ConversationQualityPlan.md` | Conversation-quality backend plan (Phases 0-6 shipped; 5.1 deferred) | reference |
| `ConversationQualityBaseline.md` | Conversation-quality metric baseline (regression reference) | reference |

## Memory subsystem

| File | Purpose | Status |
|---|---|---|
| `MEMORY.md` | Project memory — detailed API/domain reference (see note below) | active |
| `MEMORY_INTEGRATION_DESIGN.md` | Automatic memory injection in conversations — design reference | reference |
| `MemoryKeyframeDecouplingPlan.md` | Memory / keyframe decoupling plan (2026-05-30, draft) | active |

## ISO 42001

See `ISO42001/README.md` for the ISO doc set. (The old Ux75-parented `ISO42001Plan.md` has been
archived — superseded by the `ISO42001/` subdir and `AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`.)

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

---

### Note on `MEMORY.md`
`aiDocs/MEMORY.md` is a detailed API/domain reference (apparel/store participation, enum
serialization, LLM library system, etc.), distinct from the small auto-loaded user memory index at
`~/.claude/projects/<project>/memory/MEMORY.md`. Two of its cross-references now point into `archive/`
(`Ux75ImplementationPlan.md` line 97; `Ux7Redesign.md` line 96) — left as-is for Stephen to reconcile,
not silently edited.
