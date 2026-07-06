# AccountManagerISO42001 — ISO 42001 Bias-Testing & Certification Subsystem

## MANDATORY rules — read first

Working-discipline rules (NO LYING, honesty, testing) live in **`../.claude/rules/llm-conduct.md`**.
Architecture and layering rules live in **`../.claude/rules/architecture.md`**.

## Role

`AccountManagerISO42001` (jar) is the ISO 42001 bias-testing and certification subsystem: the test
engine, scoring, reporting, certification factories, and `ISO42001ServiceFacade`. It **depends on
Objects7, never the reverse** — and **no ISO knowledge is allowed in Objects7**. It registers its
models via `ISO42001ModelNames.use()` at startup/test-setup.

Business logic lives here in the factories/engine and is marshaled through `ISO42001ServiceFacade` so
that the REST layer (`ISO42001Service`) and the ISO MCP tool provider share one resolution layer.
Service7 is pure transport over this facade.

## Vocabulary

- A "campaign" = a persisted `iso42001.testConfig`. Runs launch against it and are synchronous
  (no cancel endpoint).

## Design & reference docs

- `../aiDocs/ISO42001/` — full ISO 42001 design/bias/implementation doc set (see its `README.md` index).
- `../aiDocs/ISO42001Plan.md` — compliance dashboard design & implementation plan.
- `../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md` — UX gap analysis + backend backlog.
