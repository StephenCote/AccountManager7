# AccountManager7 — root instructions

> **Two different "project roots".** Sessions and the harness open at the **git root**
> (`C:\Projects\GitHub\AccountManager7`) — that is where `.claude/`, `.mcp.json` and this file
> live, and what `${CLAUDE_PROJECT_DIR}` resolves to. The **Maven aggregator and every module**
> live one level down, in **`src/`**. So `mvn` and `npx` commands below run from `src/` (or a
> module inside it), while hook and rules paths are relative to the git root. Getting these
> backwards is the single most common mistake in this repo.

## MANDATORY: project memory is a database, not files

Durable project knowledge lives in a **SQLite store at `.claude/memory/memory.db`**.
`.claude/memory/SETUP.md` is the self-contained runbook.

**Search it before assuming something is unrecorded.** The two searches answer different
questions — reach for the semantic one when the query's wording probably won't match the
memory's wording:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" list
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" search -Query "<fts5 keywords>"
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\vec.ps1"  search -Query "<plain english>"
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" get -Name <slug>
```

`-ExecutionPolicy Bypass` is not optional here: every policy scope on this machine is
`Undefined`, so the effective policy is **Restricted** and a bare `powershell -File` fails with
a `SecurityError` while still **exiting 0** — it looks like a command that did nothing.

**Writing.** Always through `mem.ps1 set` — it derives `[[wikilinks]]`, updates the FTS index,
and regenerates `MEMORY.md`. Then embed, then export.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\mem.ps1" set -Name <kebab-slug> `
    -Description "one line, used for recall relevance" `
    -Type <user|feedback|project|reference> -BodyFile <path>
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\vec.ps1"    embed -All
powershell -NoProfile -ExecutionPolicy Bypass -File ".claude\memory\export.ps1"
```

Hard rules:
- **Never write a loose `.md` memory file**, and never hand-edit the generated `MEMORY.md`. The
  file-based memories were migrated into the DB on 2026-08-20; files are no longer the store.
- **Never write to the DB through the MCP server or raw SQL** — a direct INSERT bypasses
  wikilink derivation and index regeneration. The `memory-db` server is *not* read-only despite
  what `SETUP.md` says: it exposes `create_record`/`update_records`/`delete_records`, which
  `.claude/settings.json` denies, plus a `query` tool that runs **arbitrary SQL** and so could
  write. `query` is gated by `permissions.ask` — approve it only for reads.
- Prefer `-BodyFile` over `-Body` — a body containing a quote breaks `powershell -File` parsing.
- When a fact stops being true, mark it rather than deleting it: `mem.ps1 supersede -Name <old>
  -By <new>` / `mem.ps1 deprecate -Name <slug>`.
- **`memory.db` is gitignored; the committed form is `memories.sql`.** Run `export.ps1` after
  writing memories — nothing regenerates it automatically, so skipping it leaves the knowledge
  local-only, and `git clean -xdf` would erase it.

Embeddings come from Ollama `bge-m3` (1024 dims) on the DGX Spark at `192.168.1.42`, configured
once at user scope in `~\.claude\embed.config` so every project inherits it. `bge-m3` rather
than `nomic-embed-text` because its 8192-token window fits document-shaped memories; nomic
rejects anything past its 2048 outright. Semantic search degrades gracefully without a
provider — keyword search and the index need nothing but `sqlite3`.

---

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
- Read the reference UI (`src/AccountManagerUx752/` primary; `src/deprecated/AccountManagerUx7/client/`
  legacy) BEFORE writing UI code.

---

# Repository Architecture & Commands

> The behavioral rules in `.claude/rules/llm-conduct.md` are mandatory. This section is the
> practical orientation for working in the repo.
> Each module has its own `CLAUDE.md` with deep detail — read the relevant one before working in that module;
> don't duplicate those here.

## Module map (modules live under `src/`; `src/pom.xml` is the Maven aggregator)

| Module | Kind | Role | Deep docs |
|---|---|---|---|
| `AccountManagerObjects7` | jar | Core: schema-driven object model (`BaseRecord`), PBAC (`AccessPoint`), query system, groups/orgs, Olio (universe/world), vault/crypto | `src/AccountManagerObjects7/CLAUDE.md` |
| `AccountManagerISO42001` | jar | ISO 42001 bias-testing + certification subsystem (engine, scoring, reporting, certification factories, `ISO42001ServiceFacade`). Registers its models via `ISO42001ModelNames.use()`; **no ISO knowledge is allowed in Objects7** | `src/AccountManagerISO42001/CLAUDE.md` |
| `AccountManagerService7` | war | Jersey REST + MCP + WebSocket transport over Objects7/ISO42001. Deployed to Tomcat at **`https://localhost:8443`** | `src/AccountManagerService7/CLAUDE.md` |
| `AccountManagerAgent7` | jar | Agent runtime | `src/AccountManagerAgent7/CLAUDE.md` |
| `AccountManagerConsole7` | jar | CLI/console entry points | `src/AccountManagerConsole7/CLAUDE.md` |
| `AccountManagerUx752` | web (Vite+Mithril, not a Maven module) | Active frontend refactor — **primary/canonical UI reference; read before writing UI** (`src/AccountManagerUx752/`) | `src/AccountManagerUx752/CLAUDE.md` |
| `AccountManagerUx7` | web (not a Maven module) | **Deprecated** legacy Mithril monolith — legacy reference only, at `src/deprecated/AccountManagerUx7/client/` (Ux752 supersedes it) | `src/deprecated/AccountManagerUx7/CLAUDE.md` |

`ISO42001Service` (REST) and the ISO MCP tool provider are pure transport — business logic lives in the ISO module's factories/engine, marshaled through `ISO42001ServiceFacade` so REST and MCP share one resolution layer.

## Build & test

**Backend (Maven, multi-module).** All `mvn` commands run from **`src/`** (the aggregator dir), not
the git root. The corporate TLS proxy breaks normal dependency downloads, so build **offline** once
deps are cached:
```
cd src
mvn -o -q -pl AccountManagerISO42001 install -DskipTests   # rebuild+install a jar so dependents pick it up
mvn -o -pl AccountManagerService7 compile                   # compile the WAR against installed jars
```
Editing an ISO model JSON or facade means: `install` the ISO jar, then **rebuild+redeploy the Service7 WAR to Tomcat**. Run a single backend test with `mvn -o -pl <module> -Dtest=ClassName#method test` — but backend tests are integration tests that hit the live DB/LLM; **never reset the DB schema** (no `-Dreset`/drop — Stephen does that himself). Pure Objects7 JUnit tests (talk to DB/Ollama directly via `IOSystem`) do **not** need Service7/Tomcat running at all.

**Service7/Tomcat for testing: use the Docker setup, not a manually-run local Tomcat.** A verified
working `src/docker-compose.yml` + `src/Dockerfile` packages Service7 (Tomcat) + Ux752
behind nginx on `:8443` — see `src/aiDocs/DockerComposeDesign.md` for what's verified, the storage map
(`am7-data`/`am7-certs` volumes), and known follow-ups. Any task that needs a live Service7/Ux752
stack for testing (Playwright E2E, manual REST checks, etc.) should bring this up via
`docker-compose up` rather than assuming/depending on an ad hoc locally-managed Tomcat instance.

**Frontend (`src/AccountManagerUx752/`).** Run from that directory.
```
cd src/AccountManagerUx752
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
- **ISO 42001 vocabulary:** a "campaign" = a persisted `iso42001.testConfig`; runs launch against it and are synchronous (no cancel endpoint). Gap analysis + backend backlog: `src/AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`.
