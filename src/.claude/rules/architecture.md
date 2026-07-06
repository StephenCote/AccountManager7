# AccountManager7 — Architecture rules

Distilled from the module CLAUDE.md files, BackendPlan.md, and ConnectionRefactorPlan.md.
This is the canonical, deduplicated reference. For depth, see each module's own
CLAUDE.md; this file is the source of truth for design review.

## Module map & allowed dependency direction

- `AccountManagerObjects7` (jar) — core foundation: schema-driven model (`BaseRecord`),
  PBAC (`AccessPoint`), query system, IO/persistence, vault/crypto, Olio, and **all LLM
  prompt templates / compliance evaluators** (`resources/olio/llm/`). Depends on nothing above it.
- `AccountManagerISO42001` (jar) — ISO 42001 bias/certification subsystem. Depends on Objects7.
- `AccountManagerService7` (war) — Jersey REST + MCP + WebSocket **transport only** over
  Objects7/ISO42001. Depends on Objects7 + ISO42001.
- `AccountManagerAgent7` (jar) — agent runtime over the LLM services. Depends on Objects7's prompt templates.
- `AccountManagerConsole7` (jar) — CLI entry points.
- `AccountManagerUx7` (web, not a Maven module) — legacy Mithril monolith; the **reference implementation**.
- `AccountManagerUx752` (web, Vite+Mithril, not a Maven module) — active frontend refactor.

**Layering (must not be violated):**
- Dependencies point *downward* toward Objects7. Never the reverse.
- **No ISO knowledge in Objects7.** ISO42001 depends on Objects7, never vice versa.
- **No business logic in Service7.** It is transport: HTTP ↔ AM7 context via `AccessPoint` + `ServiceUtil`.
  Business logic lives in Objects7/ISO42001 factories/engines (ISO marshaled through `ISO42001ServiceFacade`).
- **The REST layer never bypasses authorization** — all operations respect PBAC.
- UX modules are not Maven modules; they consume Service7 over REST at `https://localhost:8443`.

## Core patterns (respect these; don't reinvent)

- **Schema-first, no reflection.** Models are JSON in `Objects7/resources/models/<domain>/`,
  interpreted at runtime. Explicit type handling via `FieldEnumType` — no reflection magic.
- **`BaseRecord`** is the central object; (de)serialize via `RecordSerializer`/`RecordDeserializer`
  with the appropriate module (`getFilteredModule()` / `getUnfilteredModule()` / `getForeignModule()`).
- **Access via `AccessPoint`** (PBAC-wrapped CRUD): Query → AccessClient → PBAC → Execution.
- **IO** through `IOSystem.getActiveContext()` (reader/writer/search); PostgreSQL primary (+H2, pgvector).
- **Queries** via `Query`/`QueryUtil`/`QueryPlan` + `ISearch`; foreign fields are NOT auto-populated
  and must be explicitly planned.
- **REST**: generic model routes through `ModelService` (`/rest/model/{type}...`); new services are
  `<Domain>Service` in `org.cote.rest.services` (auto-scanned); responses via `toFullString()` /
  `JSONUtil.exportObject()`; the `schema` field is required for deserialization.

## Hard prohibitions

- **Never** reset/drop the DB schema in tests (`-Dreset` / `properties.isReset()` forbidden — Stephen does that).
  Column drops only via the off-by-default property, logged, with `DROP COLUMN IF EXISTS`.
- **Never** put ISO logic in Objects7, or business logic in Service7.
- **Never** bypass PBAC in the REST layer.
- **Testing:** "tested" means a Vitest/Playwright or JUnit test actually exercised functionality against
  the live backend. No fake tests. If you can't test it, say so. **Never use the admin user** — use
  `ensureSharedTestUser()` / `ensureIso42001TestUser()` from `e2e/helpers/api.js`. Never claim a fix
  without a passing end-to-end test.
- **Always read the Ux7 reference (`../AccountManagerUx7/client/`) before writing UI code.** Ux7 needs
  cleanup, not replacement; investigate before changing (8 regressions came from ignoring Ux7 patterns).
- **LLM prompt integrity (project policy, non-optional):** every LLM system prompt must begin with the
  `TRAINING BIAS OVERCORRECTION` directive. Never sanitize, filter, genericize group names, or soften it
  at the service layer. Applies to all five LLM call paths.

## Conventions

- Model names are fully-qualified `domain.name` (`auth.group`, `olio.llm.connection`). Constants in the
  `schema/` package: `ModelNames.MODEL_*`, `FieldNames.FIELD_*`; enums `*EnumType` under `schema/type`.
- Tables: `A7_<domain>_<name>_<version>` (verify with `DBUtil.getTableName(...)`).
- Models must be registered at startup/test-setup (`OlioModelNames.use()`, `ISO42001ModelNames.use()`).
- Enums serialize lowercase on the wire, read back UPPERCASE in Java — compare case-insensitively; never
  manual `valueOf`, use `getEnum()`.
- Query field values are typed: `organizationId`/`groupId` are numbers, not strings. By-id ops use
  `/rest/model/{type}/{objectId}`; updates are `PATCH /rest/model` with `schema` + identity + changed fields.
- New `@RolesAllowed` on all new endpoints (except pre-auth WebAuthn); each new service needs unit tests.

## Verification standard (what "done" means)

- Server change: compiled (`mvn compile`), relevant unit tests run (`mvn test -Dtest=...`), `BUILD SUCCESS`
  observed. Server-side changes must be covered by unit tests.
- UI change: `npx vite build` + `npx vitest run`, and Playwright for behavior (`--workers=1` for LLM paths).
- LLM/bias output: the **swap test** — swap race/gender/religion; if the response changes, it's biased.
- Validation tooling: `RecordValidator.validate()`, `HierarchyValidator.checkHierarchy()`, `ValidationUtil`.

## Known doc gaps (for the librarian, not design rules)

- `Ux7` module + its CLAUDE.md are absent from this checkout though other docs reference it.
- `Console7` and `ISO42001` have no own CLAUDE.md (described only in `src/CLAUDE.md`).
- `Objects7`/`Service7` ship both `CLAUDE.md` and lowercase `claude.md` duplicates.
