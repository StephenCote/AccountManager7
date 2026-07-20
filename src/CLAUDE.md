# AccountManager7 — Root Instructions

## MANDATORY: working discipline

The mandatory working-discipline rules for this repo — the **NO LYING** rules, honesty, real
testing, following instructions, and owning mistakes — live in canonical rules files:

> **`.claude/rules/llm-conduct.md`** — honesty, no-lying rules, testing discipline, conduct
> **`.claude/rules/architecture.md`** — architecture, layering, and hard prohibitions

Read `llm-conduct.md` before doing anything. These rules override default behavior.

Quick reminders (full text in `llm-conduct.md`):
- "Tested" = a Playwright/Vitest/JUnit test actually exercised the functionality against the
  live backend. No fake tests. If you can't test it, say so.
- NEVER use the admin user — use `ensureSharedTestUser()` / `ensureIso42001TestUser()`.
- Read the reference UI (`AccountManagerUx752/` primary; `deprecated/AccountManagerUx7/client/`
  legacy) BEFORE writing UI code.

---

# Repository Architecture & Commands

> The behavioral rules in `.claude/rules/llm-conduct.md` are mandatory. This section is the
> practical orientation for working in the repo.
> Each module has its own `CLAUDE.md` with deep detail — read the relevant one before working in that module;
> don't duplicate those here.

## Module map (this directory is the repo root; `pom.xml` is the Maven aggregator)

| Module | Kind | Role | Deep docs |
|---|---|---|---|
| `AccountManagerObjects7` | jar | Core: schema-driven object model (`BaseRecord`), PBAC (`AccessPoint`), query system, groups/orgs, Olio (universe/world), vault/crypto | `AccountManagerObjects7/CLAUDE.md` |
| `AccountManagerISO42001` | jar | ISO 42001 bias-testing + certification subsystem (engine, scoring, reporting, certification factories, `ISO42001ServiceFacade`). Registers its models via `ISO42001ModelNames.use()`; **no ISO knowledge is allowed in Objects7** | `AccountManagerISO42001/CLAUDE.md` |
| `AccountManagerService7` | war | Jersey REST + MCP + WebSocket transport over Objects7/ISO42001. Deployed to Tomcat at **`https://localhost:8443`** | `AccountManagerService7/CLAUDE.md` |
| `AccountManagerAgent7` | jar | Agent runtime | `AccountManagerAgent7/CLAUDE.md` |
| `AccountManagerConsole7` | jar | CLI/console entry points | `AccountManagerConsole7/CLAUDE.md` |
| `AccountManagerUx752` | web (Vite+Mithril, not a Maven module) | Active frontend refactor — **primary/canonical UI reference; read before writing UI** (`AccountManagerUx752/`) | `AccountManagerUx752/CLAUDE.md` |
| `AccountManagerUx7` | web (not a Maven module) | **Deprecated** legacy Mithril monolith — legacy reference only, at `deprecated/AccountManagerUx7/client/` (Ux752 supersedes it) | `deprecated/AccountManagerUx7/CLAUDE.md` |

`ISO42001Service` (REST) and the ISO MCP tool provider are pure transport — business logic lives in the ISO module's factories/engine, marshaled through `ISO42001ServiceFacade` so REST and MCP share one resolution layer.

## Build & test

**Backend (Maven, multi-module).** The corporate TLS proxy breaks normal dependency downloads, so build **offline** once deps are cached:
```
mvn -o -q -pl AccountManagerISO42001 install -DskipTests   # rebuild+install a jar so dependents pick it up
mvn -o -pl AccountManagerService7 compile                   # compile the WAR against installed jars
```
Editing an ISO model JSON or facade means: `install` the ISO jar, then **rebuild+redeploy the Service7 WAR to Tomcat**. Run a single backend test with `mvn -o -pl <module> -Dtest=ClassName#method test` — but backend tests are integration tests that hit the live DB/LLM; **never reset the DB schema** (no `-Dreset`/drop — Stephen does that himself). Pure Objects7 JUnit tests (talk to DB/Ollama directly via `IOSystem`) do **not** need Service7/Tomcat running at all.

**Service7/Tomcat for testing: use the Docker setup, not a manually-run local Tomcat.** A verified
working `docker-compose.yml` + `Dockerfile` at the repo root packages Service7 (Tomcat) + Ux752
behind nginx on `:8443` — see `aiDocs/DockerComposeDesign.md` for what's verified, the storage map
(`am7-data`/`am7-certs` volumes), and known follow-ups. Any task that needs a live Service7/Ux752
stack for testing (Playwright E2E, manual REST checks, etc.) should bring this up via
`docker-compose up` rather than assuming/depending on an ad hoc locally-managed Tomcat instance.

**Frontend (`AccountManagerUx752/`).**
```
npx vite build              # build (fastest correctness check for JS changes)
npx vitest run              # unit tests
npx playwright test         # e2e (needs the Docker stack up — see above — plus the Vite dev server on :8899, which proxies to :8443)
npx playwright test e2e/foo.spec.js -g "name" --workers=1 --project=chromium   # single e2e, serial
```
E2E needs both live: the Service7/Tomcat stack (via Docker, at `:8443`) and the Vite dev server at
`:8899` (proxy). **Never test as `admin`** — use `ensureSharedTestUser()` / `ensureIso42001TestUser()`
from `e2e/helpers/api.js`. LLM-touching tests use the DGX Spark at `192.168.1.42` and must run
single-threaded (`--workers=1`); gate them behind an env flag so the default 4-worker suite never
fires parallel runs at it.

## Cross-cutting model/PBAC gotchas (bite across layers; learned the hard way)

- **Schema-driven records.** Everything is a `BaseRecord` shaped by JSON model defs; model names must be registered (`OlioModelNames.use()`, `ISO42001ModelNames.use()`) at startup/test-setup. Enums serialize **lowercase** on the wire, read back **UPPERCASE** in Java (but list projections may return the raw lowercase — compare case-insensitively in the UI).
- **No group ⇒ field-level role checks.** A record carrying a `groupId` gets the group-only access shortcut; a record **without** one (anything referencing a groupless `system.user`, or an org-wide list) forces the dynamic auth checker onto field/role checks. Consequences: (a) list queries of `data.directory`-derived types must include an explicit `organizationId` (optionally `groupId`) condition or PBAC logs "Group could not be found" and denies; (b) updating a record that re-persists a foreign ref to a groupless model needs role grants on that foreign field — or, cleaner, **update only the changed fields** (identity + changed) instead of a full planMost graph.
- **Query field values are typed.** `organizationId`/`groupId` are `long` — send **numbers**, not strings (`{value: 2}`, not `{value: "2"}`), or the condition silently matches nothing.
- **`/rest/model/search` is cached by query key.** Set `cache:false` for views that must see just-created/edited/deleted records.
- **Generic model routes** accept digit-bearing `{type}` (e.g. `iso42001.testConfig`); by-id `GET`/`/full`/`DELETE` use `/rest/model/{type}/{objectId}`, and update is `PATCH /rest/model` with schema + identity + changed fields.
- **ISO 42001 vocabulary:** a "campaign" = a persisted `iso42001.testConfig`; runs launch against it and are synchronous (no cancel endpoint). Gap analysis + backend backlog: `AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`.
