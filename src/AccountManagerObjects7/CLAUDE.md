# AccountManagerObjects7 — Core Object Model

## Read first

- **Working discipline** (NO LYING, honesty, testing): `../.claude/rules/llm-conduct.md`
- **Architecture & layering**: `../.claude/rules/architecture.md`
- **Deep technical reference** (schema system, providers, vault, PBAC, Olio map, validation,
  journaling, utilities): `../.claude/rules/objects7-reference.md`
- **Cross-layer query/serialization/PATCH/foreign-model patterns**: `../.claude/rules/model-api.md`

## Role

Core foundation (jar). Schema-driven object model (`BaseRecord`), PBAC (`AccessPoint`), the query
system, IO/persistence, vault/crypto, and Olio (universe/world simulation). Also owns all runtime LLM
prompt templates under `src/main/resources/olio/llm/`. Depends on nothing above it — no ISO knowledge
here.

## Must-know gotchas (details in the reference docs)

- **Schema-first, no reflection.** Models are JSON in `src/main/resources/models/<domain>/`, interpreted
  at runtime; explicit typing via `FieldEnumType`. Model names must be registered (`OlioModelNames.use()`)
  at startup/test-setup.
- **Check field data types in the model JSON before reading them** — a field typed `double` read as
  `int` throws `ClassCastException`. Use `record.getEnum(...)` for enums, never manual `valueOf`.
- **Enums** serialize lowercase on the wire, read back UPPERCASE in Java — compare case-insensitively.
- **Foreign fields are NOT auto-populated** — plan for them (`planMost`, `setRequest`, or a custom
  `QueryPlan`). See `model-api.md`.
- **Access via `AccessPoint`** (PBAC-wrapped): Query → AccessClient → PBAC → Execution. Utilities may
  bypass AccessClient for performance.
- **PATCH / partial updates** (identity + changed fields only) are the safe way to update records that
  reference groupless models. See `model-api.md`.
- **Olio** expects fully, deeply populated records — use `OlioUtil.planMost()` / `getFullRecord()`;
  don't double-load after `GameUtil.findCharacter()`. See `model-api.md`.

## Testing

Server-side changes must be covered by unit tests (`src/test/java/org/cote/accountmanager/objects/tests/`).
Compile (`mvn -o compile`), run relevant tests (`mvn -o -Dtest=ClassName#method test`), confirm
`BUILD SUCCESS`. **Never reset/drop the DB schema** (no `-Dreset` — Stephen does that). Full verification
standard: `../.claude/rules/architecture.md`.
