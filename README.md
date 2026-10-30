# AccountManager7

AccountManager7 (AM7) is a multi-tenant platform for identity, authorization, and data management.
Its object model is defined by schemas, and every operation is checked against policy-based
access control (PBAC). On top of that core it runs LLM and image-generation features: chat with
memory and retrieval, the Olio world/character simulation, PictureBook/ChapBook illustrated-story
generation, and an ISO/IEC 42001 AI-bias testing and certification subsystem.

At its heart AM7 is a store for varied data where each item can have its own permissions and
encryption. It supports complex permission relationships and keeps organizations logically
separate. It is not a complete identity-management product with connectors and workflow engines.
It is a foundation for multi-tenant identity and data services that can take in identity, account
and entitlement data from other systems and evaluate policy over it. The data lives in a relational
database (PostgreSQL) because hierarchy, membership and entitlement relationships are expressed
more naturally there than in a directory service.

The backend is Java (Maven, multi-module) and is served by Tomcat as a Jersey REST + MCP + WebSocket
WAR. The frontend is a Vite + Mithril single-page app.

> **Repository layout.** The git root holds this file, `CLAUDE.md`, and `.claude/`. **The Maven
> aggregator and all modules are one level down, in `src/`.** Every `mvn`, `npx`, and `docker compose`
> command below runs from `src/` or from a module inside it.

---

## Modules

| Module (`src/…`) | Kind | Role |
|---|---|---|
| `AccountManagerObjects7` | jar | Core: the `BaseRecord` object model, PBAC (`AccessPoint`), the query system, IO/persistence (PostgreSQL + pgvector, H2), vault/crypto, organizations/groups, Olio, and all runtime LLM prompt templates |
| `AccountManagerISO42001` | jar | ISO 42001 bias-testing engine, scoring, reporting, and certification, exposed through `ISO42001ServiceFacade` |
| `AccountManagerAgent7` | jar | Agent runtime (chain execution) over the LLM services |
| `AccountManagerConsole7` | jar | Command-line admin, setup-repair, export, and Olio tools |
| `AccountManagerService7` | war | REST (`/rest/...`), MCP, and WebSocket transport over Objects7 and ISO42001 |
| `AccountManagerUx752` | web | The current frontend (Vite 6, Mithril 2, Tailwind; Vitest + Playwright). Not a Maven module |
| `deprecated/AccountManagerUx7` | web | The old Mithril frontend. Kept only as a reference; Ux752 replaces it |

Dependencies point **downward** toward Objects7. Objects7 depends on nothing above it and contains no
ISO 42001 code. Service7 is transport only: business logic lives in the Objects7 and ISO42001
factories and engines.

---

## Design philosophy

- **Schema first, no reflection.** Models are JSON definitions in
  `AccountManagerObjects7/src/main/resources/models/<domain>/`, interpreted at runtime. Fields are
  typed explicitly through `FieldEnumType`. Models compose behavior through multiple inheritance
  (`inherits`, resolved depth-first, last wins) rather than deep class hierarchies. Schemas and data
  stay portable and can be introspected at runtime (`GET /rest/schema`).
- **One central record type.** Everything is a `BaseRecord`, serialized by its own
  `RecordSerializer`/`RecordDeserializer`. Field providers add computed values, path resolution, and
  encryption.
- **Explicit over implicit.** Foreign references are not populated unless a `QueryPlan` asks for
  them. Partial updates (`PATCH`) send identity fields plus the changed fields only. List queries
  state their organization and group scope. Nothing loads, cascades, or widens scope on its own.
- **Universe vs world (Olio).** Shared, immutable reference data (the universe) is kept separate
  from per-world state that changes as the simulation evolves, so a world can be reset on its own.
- **Performance-conscious.** The platform supports batch create/update in a single transaction,
  explicit control over how deep a query reads, field-level locks, and cached authorization
  decisions.

Deeper tours: [`src/aiDocs/Objects7DeepReference.md`](src/aiDocs/Objects7DeepReference.md) and
[`src/aiDocs/Service7Reference.md`](src/aiDocs/Service7Reference.md).

## Security philosophy

- **Authorization is never bypassed at the edge.** User-driven operations go through
  `AccessPoint`: query → policy evaluation → execution. Schemas can require roles on whole models
  or on individual fields. Every REST endpoint carries `@RolesAllowed`. The only exceptions are
  WebAuthn and first-run setup, which exist to create the first credential. The access model is
  described under [Authorization model](#authorization-model) below.
- **Organizations are the tenancy boundary.** Every deployment has the default organizations
  `/System`, `/Development`, and `/Public`, and each organization has its own keystore and vault.
  Per-organization values travel with the call and are never written into process-wide state.
- **Encryption at rest.** Fields marked `encrypt: true` are encrypted transparently through the
  organization vault: a per-organization asymmetric key pair, rotating symmetric keys, and a
  per-vault salt. The private vault key is protected by a credential kept on the filesystem, not
  in the database. Protected data can therefore be recovered only when both the database and the
  key store are present; a copy of the database alone is not enough. Binary data payloads can
  also be gzip-compressed. Credentials are
  stored as salted hashes and cannot be recovered.
- **Authentication.** Login uses a JAAS login module and issues JWTs. Long-lived API tokens and
  WebAuthn passkeys are also supported. SCIM 2.0 user and group endpoints are available for
  provisioning.
- **Secure first run.** A fresh deployment starts with no credentials. Setup is guarded by two
  checks: a latch stored in the database and a one-shot, 192-bit setup token written to the
  filesystem. The token is never logged and is deleted once setup completes. The container refuses
  to start when CORS is set to `*`.
- **Read paths do not write as admin.** Code that only reads never silently creates records with
  elevated privileges. Feature gates run after authorization and existence checks, so a gate can
  never reveal that a record exists to a caller who could not see it.

The authoritative rules are in [`.claude/rules/architecture.md`](.claude/rules/architecture.md).

### Authorization model

AM7 uses **policy-based access control**, and the policies are built on **participation**:

- **Ownership and containment.** Every record belongs to an organization and has a user as its
  owner. Most records also sit in a group (a directory-like hierarchy) or under a parent. Ownership
  gives the discretionary baseline: owners can act on what they own.
- **Participation.** Participation records link a participant (a user, account, person, group, or
  role) to a resource. A participation can carry an effect, either granting or denying a specific
  permission. Group and role membership are participations too, so entitlements can be assigned
  directly or inherited indirectly through role and group hierarchies. One mechanism covers ACL-,
  group-, and role-based access.
- **Policies.** Every decision comes from evaluating a policy, built as policy → rules → patterns →
  facts:
  - **Policy:** has an enabled flag and a `decisionAge`, which sets how long its decision may be
    cached.
  - **Rule:** has a type (PERMIT, DENY, or TRANSFORM) and a condition (ALL, ANY, NONE, or a numeric
    threshold) for combining its patterns and sub-rules.
  - **Pattern:** compares a subject fact to a match fact using a comparator. Pattern types are
    authorization (a participation/entitlement check), expression, function, operation (custom
    code or an external decision point), separation of duty, and approval (which can return
    PENDING).
  - **Fact:** can be a parameter supplied with the request, a static value, a record, an attribute,
    a group, role, or permission, the request context, or the result of a function or operation.
- **Built-in checks are themselves policies.** The standard create/read/update/delete/execute
  checks are ordinary policy definitions: owner, group, parent, resource permission, model role,
  and token access. They are JSON templates under
  `AccountManagerObjects7/src/main/resources/{policies,rules,patterns,facts}/`. Attribute-based,
  separation-of-duty, and approval rules use the same evaluator as basic CRUD.
- **Decisions are explainable.** A policy response is PERMIT, DENY, PENDING, or an error, and it
  records the chain of rules and patterns that was evaluated. Turning on the policy trace
  (`PolicyUtil.setTrace`) shows which rule, pattern, or participation decided the outcome.
- **Ready for authorization as a service (API pending).** The policy layer is designed for
  external callers, not just internal checks. A *policy definition* is a public description of a
  policy, including the parameters it needs. A client fills in a *policy request*, submits it for
  evaluation, and gets back a *policy response* whose expiry comes from the policy's
  `decisionAge`, so the client knows how long it may cache the decision. Those models and the
  evaluator exist in Objects7. The REST endpoint that would let other systems use AM7 as their
  policy decision point has not been wired up yet.

---

## Key features

- **Generic model API.** Typed CRUD, search, pagination, and `PATCH` for any registered model
  (`/rest/model/...`), plus path-based access, schema introspection, and import/export.
- **Identity and access.** Users, groups, roles, permissions, organizations, access requests,
  credentials, SCIM 2.0, WebAuthn, and policy evaluation with tracing. Authorization as a service is ready in the
  policy layer, with the REST API pending.
- **LLM chat and memory.** Chat sessions with configurable prompt templates, long-term memory,
  vector search (pgvector embeddings), and PageIndex hierarchical document indexing. LLM servers are
  configured as `system.connection` records (Ollama, OpenAI-compatible endpoints, and LiteLLM).
- **MCP server.** Model Context Protocol tools for AM7 data and ISO 42001 operations.
- **Olio.** World and character simulation. A universe holds shared reference data (names,
  occupations, traits, a WordNet dictionary, map and location grids, and colors). Each world holds
  its own population, which is generated by region on an MGRS-style map and then evolved over
  epochs, by default one year of twelve monthly cycles. Evolution produces events for birth,
  death, marriage, divorce, and immigration. Characters have statistics, personality, instincts,
  apparel and wear levels, inventory, and position. Olio also includes Stable Diffusion portrait and scene generation
  (SwarmUI/ComfyUI), voice (TTS/STT), face analysis, and tagging.
- **PictureBook / ChapBook.** Turns source text into illustrated books: scene extraction, a cast for
  each book world, a workflow graph, and image composition. Long operations run as background jobs
  that you poll (`?async=true` → `GET /rest/job/{jobId}`). Current design:
  [`src/aiDocs/PictureBookV3Design.md`](src/aiDocs/PictureBookV3Design.md).
- **ISO/IEC 42001.** AI bias-testing campaigns, differential scoring, reporting, and signed
  certification, with UI and MCP front ends. See [`src/aiDocs/ISO42001/`](src/aiDocs/ISO42001/).
- **Observability (optional).** A LiteLLM proxy and Langfuse tracing for every LLM call.

The current backlog and known defects are tracked in
[`src/aiDocs/KnownIssues.md`](src/aiDocs/KnownIssues.md). The full documentation index is
[`src/aiDocs/README.md`](src/aiDocs/README.md).

---

## Building and running locally

### Prerequisites

| | Version |
|---|---|
| JDK | **26** (`maven-compiler-plugin` uses `release 26`) |
| Maven | 3.9+ |
| Node.js | 20+ (the Docker image uses 24) |
| PostgreSQL + pgvector | PostgreSQL 18 with pgvector 0.8.6 (the version the Docker stack pins) |
| Docker Desktop | Recommended. Compose v2 |

### Option A: Docker (recommended)

One container runs nginx (TLS), Tomcat 11 with the Service7 WAR, and the built Ux752 frontend.
`docker-compose.test.yml` also brings up its own tuned pgvector database. The step-by-step runbook
is [`src/aiDocs/dockerDevSetup.md`](src/aiDocs/dockerDevSetup.md). In short:

```powershell
cd src
docker compose -p am7test -f docker-compose.test.yml up --build -d
# or use the wrapper: .\am7-docker-up.bat   (also accepts --no-build, --prebuilt, --llmproxy, --ollama)
```

1. **Read the one-shot setup token.** The first boot writes it to
   `src\docker-data\am7\store\.setup.token`. It is never printed, so do not look for it in
   `up -d` output.
2. **Run first-time setup.** Open `https://localhost:9443/#!/setup?token=<TOKEN>` and accept the
   self-signed certificate. Choose the admin password (it applies to all three organizations). Use
   `password` on the `am7test` stack, because the Playwright helpers expect it. You can optionally
   create an initial user in `/Public` or `/Development` and set the media/AI server URLs here.
3. **Sign in** at `https://localhost:9443`.
4. **(Olio) Seed the corpus** before generating characters, using `assemble-seed.bat` (Windows) or
   `assemble-seed.sh`. See runbook §2e.

When you call REST through Docker, prefix the path with `/AccountManagerService7`, for example
`https://127.0.0.1:9443/AccountManagerService7/rest/setup/state`.

The runbook also covers these optional profiles:
- `--llmproxy`: the LiteLLM proxy (`:4000`) plus Langfuse tracing (`:3001`). Runbook §12.
- `--ollama`: an in-stack Ollama at `127.0.0.1:11435`. It pulls `nomic-embed-text`, `qwen3:8b`, and
  `goekdenizguelmez/JOSIEFIED-Qwen3:8b` (about 11 GB) and implies `--llmproxy`. Runbook §13.
- `PREBUILT=1`: reuses a WAR built on the host when a TLS-intercepting proxy blocks Maven Central
  inside the image. Runbook §2b.
- `docker-compose.yml`: the standard stack, which uses an external Postgres and listens on `:8443`.
  Runbook §6.

### Option B: Host build (Tomcat or Eclipse)

Build from `src/`. Use `-o` (offline) once dependencies are cached; the corporate TLS proxy breaks
normal downloads.

```powershell
cd src
mvn -o install -DskipTests                                    # all modules, in reactor order
# or, one at a time:
mvn -o -pl AccountManagerObjects7,AccountManagerISO42001 install -DskipTests
mvn -o -pl AccountManagerService7 package -DskipTests         # -> AccountManagerService7/target/*.war
```

Configuration for a host deployment:
- **Database (Service7):** the JNDI pool in
  `AccountManagerService7/src/main/webapp/META-INF/context.xml`. By default it points at
  `jdbc:postgresql://localhost:15430/am72db`.
- **File store (Service7):** `store.path` in `AccountManagerService7/src/main/webapp/WEB-INF/web.xml`.
  This directory holds keys, certificates, and streams, for example `C:/Projects/data/am7`. Create
  it before the first start.
- **Console7:** `app.basePath` and the `test.db.*` settings in
  `AccountManagerConsole7/src/main/resources/resource.properties`. **Check `test.db.reset` before
  you point it at a database you care about.** When `true`, it drops that database's schema, keys,
  and data.

Deploy the WAR to Tomcat 11 over HTTPS on `:8443`. On first boot the server creates the schema
(about 132 `a7_*` tables) and the three default organizations. Then finish first-time setup in the
UI, as in Option A. Alternatively, from Console7, run
`java -jar AccountManagerConsole7/target/AccountManagerConsole7-7.0.0-SNAPSHOT.jar -setup -adminPassword <pw>`.
This repair command is idempotent and never overwrites an existing credential, but running it
**permanently closes** the web setup page for that database.

### Frontend (Ux752)

```powershell
cd src\AccountManagerUx752
npm install
npx vite            # dev server on https://localhost:8899, proxies /AccountManagerService7 to https://localhost:8443
                    # (override the backend with $env:AM7_BACKEND)
npx vite build      # production build -> dist/
```

To load a new frontend build into a running Docker container without rebuilding the image, run
`npx vite build`, then `docker cp .\dist\. am7test-am7-1:/opt/ux752/dist/`.

### Tests

```powershell
# Backend (from src/). These are integration tests: they hit the live DB and, where relevant, the LLM.
mvn -o -pl AccountManagerObjects7 -Dtest=ClassName#method -DskipTests=false test
#   -DskipTests=false is required for Objects7. Without it the POM skips tests and still reports BUILD SUCCESS.

# Frontend (from src\AccountManagerUx752)
npx vitest run
$env:PLAYWRIGHT_BASE_URL = "https://127.0.0.1:9443"     # against the Docker stack; use 127.0.0.1, not localhost
npx playwright test --workers=1 --project=chromium
```

E2E tests sign in with `ensureSharedTestUser()` / `ensureIso42001TestUser()` from
`e2e/helpers/api.js`, never as `admin`. Tests that need an LLM use the first tier that answers: the
local Ollama container, then Azure through LiteLLM, then the LAN Ollama host. They must run with
`--workers=1`. Docker-specific Playwright pitfalls (IPv6 `localhost`, CORS origins, the WebSocket
stub, a stale `dist`) are covered in runbook §9.

---

## Further reading

- [`CLAUDE.md`](CLAUDE.md) and the `CLAUDE.md` in each module: orientation and module-specific notes
- [`.claude/rules/`](.claude/rules/): architecture, model/query API patterns, troubleshooting
- [`src/aiDocs/README.md`](src/aiDocs/README.md): index of design, plan, and reference docs
