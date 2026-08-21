AccountManager7 (AM7) is Stephen Cote's ground-up rewrite of the Account Manager identity and
data-management platform. It is **schema-first**: every entity is a `BaseRecord` shaped by a JSON model
definition interpreted at runtime — strong typing with no reflection — and every operation is
authorized through participation-based access control (PBAC) via `AccessPoint`.

**Repo shape — sessions open at the GIT ROOT (`c:\Projects\GitHub\AccountManager7`) as of 2026-08-20.**
The Maven aggregator `pom.xml` and all modules live one level down, under `src\`. So "project root"
is ambiguous here and the two meanings must not be conflated:

- Harness/session root = the git root. `CLAUDE.md`, `.claude\` (settings, memory, agents, commands,
  rules, hooks, loop) and `.mcp.json` are there, and `CLAUDE_PROJECT_DIR` resolves to it.
- Build root = `src\`. Maven, npm, the module `CLAUDE.md` files, and `aiDocs\` are relative to `src\`.

**Migration to the root is COMPLETE as of 2026-08-21.** `src\.claude\` no longer exists: `agents`,
`commands`, `rules`, `hooks` and `loop` moved to `.claude\`, the two `settings.json` files were merged
into one at the root, and `src\CLAUDE.md` is now a stub pointing at the root `CLAUDE.md`, which carries
the orientation, module map and build/test commands. Module-level `.claude\` dirs still exist under
`src\AccountManagerUx752\` and `src\deprecated\AccountManagerUx7\`; they are inert while sessions open
at the root.

`.claude\loop\detect.sh` now distinguishes the two roots explicitly: `PROJECT_ROOT` is the `.claude`
parent, `ROOT` is the module root, resolved as `$LOOP_ROOT` → `$PROJECT_ROOT/pom.xml` → `$PROJECT_ROOT/src/pom.xml`.
The old rule ("ROOT = the .claude parent") was correct only while `.claude` sat at `src\.claude`; left
unfixed after the move it makes `_is_module` false for every module, so `verify.sh` prints `VERIFY_OK`
having compiled and tested nothing. See [[feedback-memory-active-use]] for the related write-side gap.

Maven modules — dependencies point *downward* toward Objects7, never the reverse:

- `AccountManagerObjects7` (jar) — the core. `BaseRecord`/schema system, PBAC, query system
  (`Query`/`QueryPlan`/`ISearch`), groups/orgs, vault/crypto, the Olio population-simulation
  framework, and all runtime LLM prompt templates.
- `AccountManagerISO42001` (jar) — ISO 42001 AI-management bias testing, scoring, certification.
  No ISO knowledge is permitted in Objects7.
- `AccountManagerService7` (war) — Jersey REST + MCP + WebSocket. **Transport only**, no business
  logic; deployed to Tomcat at `https://localhost:8443`.
- `AccountManagerAgent7` (jar) — agent runtime. `AccountManagerConsole7` (jar) — CLI entry points.

Frontends, not Maven modules: `AccountManagerUx752` (Vite + Mithril) is the active refactor and the
canonical UI reference — read it before writing UI code. `deprecated/AccountManagerUx7/client/` is the
legacy monolith it supersedes.

Persistence is PostgreSQL primary, plus H2 and pgvector. A live Service7/Ux752 stack for testing comes
up via docker-compose rather than a hand-managed Tomcat — see [[project-service-testing-docker]]. LLM
and embedding work targets the DGX Spark at `192.168.1.42`; see [[reference-sd-llm-hardware]].

Authoritative detail lives in the repo, not here: root `CLAUDE.md` for orientation and build/test
commands, `.claude/rules/` for the mandatory conduct, architecture, and model-API rules, each module's
own `CLAUDE.md` for depth, and `src/aiDocs/` for design documents.
