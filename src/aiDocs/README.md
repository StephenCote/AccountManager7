# aiDocs — Documentation Index

Design, plan, and reference docs for AccountManager7. Behavioral/working-discipline rules live in
`../.claude/rules/llm-conduct.md`; architecture in `../.claude/rules/architecture.md`.

Status legend: **active** (current, in use) · **design** (design/reference, likely still relevant) ·
**plan** (implementation plan) · **historical** (superseded/completed, kept for record) ·
**uncertain** (status unclear — needs Stephen's call before archiving).

## Frontend (Ux752 / Ux7)

| File | Purpose | Status |
|---|---|---|
| `Ux752Plan.md` | Ux752 focused-refactor plan, standing orders, guiding principles | active |
| `Ux752ImplementationPlan.md` | Ux752 5-phase implementation plan + change register | active |
| `ConnectionRefactorPlan.md` | Connection model refactor + Ux752 list-view bug fixes | active |
| `Ux7Redesign.md` | Original Ux7 redesign document (led to Ux75/Ux752) | uncertain |

## Backend / platform

| File | Purpose | Status |
|---|---|---|
| `BackendPlan.md` | Backend implementation plan (cited source for `architecture.md`) | uncertain |
| `SCIM.md` | SCIM REST service implementation plan | design |
| `MCP.md` | MCP (Model Context Protocol) integration plan | design |
| `KnownIssues.md` | **Current** known-issues / backlog | active |

## Chat / prompt system

| File | Purpose | Status |
|---|---|---|
| `chatRefactor.md` | Chat & prompt template system refactor design | uncertain |
| `chatRefactor2.md` | Chat refactor phase 2 (image drop, Agent7 bridge, memory sharing) | uncertain |
| `ConversationQualityPlan.md` | Conversation quality backend plan | design |
| `ConversationQualityBaseline.md` | Conversation quality baseline (Phase 0) | design |

## Memory subsystem

| File | Purpose | Status |
|---|---|---|
| `MEMORY.md` | Project memory — detailed API/domain reference (see note below) | active |
| `MEMORY_INTEGRATION_DESIGN.md` | Automatic memory integration in conversations — design | design |
| `MemoryRefactor2.md` | Memory system refactor v2 — design | design |
| `MemoryKeyframeDecouplingPlan.md` | Memory / keyframe decoupling plan | design |
| `MemoryOllamaInvestigation.md` | Issue #14 memory/Ollama infinite-loop investigation | uncertain |

## Games / content features

| File | Purpose | Status |
|---|---|---|
| `RPG.md` | Turn-based RPG design & build plan | design |
| `PictureBookDesign.md` | Picture Book completion design | design |
| `PictureBookImplementationPlan.md` | Picture Book implementation plan | design |
| `PictureBookRevisions.md` | Picture Book revision plan | design |
| `PictureBookTestPlan.md` | Picture Book comprehensive test plan | design |
| `PictureBookIssues-2026-03-31.md` | Picture Book post-implementation issue list (dated) | uncertain |

## ISO 42001

See `ISO42001/README.md` for the ISO doc set. Top-level: `ISO42001Plan.md` (compliance dashboard
design & implementation plan — plan).

## Archive

`archive/` holds superseded/completed docs, kept for record (moved, never deleted):
- `archive/OpenIssues.md` — March 2026 bug-fix sprint list (superseded by `KnownIssues.md`)
- `archive/Ux75ImplementationPlan.md` — Ux75 implementation plan/status (Ux75 superseded by Ux752)
- `archive/PictureBookNextSession.md` — one-off session handoff prompt

---

### Note on `MEMORY.md`
`aiDocs/MEMORY.md` is a detailed API/domain reference (apparel/store participation, enum
serialization, LLM library system, etc.). It is **distinct** from — and does not conflict with — the
auto-loaded user memory index at `~/.claude/projects/<project>/memory/MEMORY.md`, which is a short
operational index (never-reset-DB, LLM test endpoint, Maven TLS workaround, AM6.5 PKI). One stale
cross-reference: `MEMORY.md` line 97 points to `aiDocs/Ux75ImplementationPlan.md`, now at
`aiDocs/archive/Ux75ImplementationPlan.md`. Left as-is for Stephen to reconcile (not silently edited).
