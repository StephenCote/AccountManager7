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
- **Every authorization decision is audited.** Each operation that goes through `AccessPoint`
  writes an audit record: who acted, the action, the resource, the query, the outcome (PERMIT,
  DENY, or INVALID), and the policy response behind it. This includes the denials. Each record is
  hashed and signed with the organization's private key, so a stored audit cannot be altered
  without detection. Records are written in the background. If no IO context is available, for
  example during shutdown, they are saved to disk and loaded on the next start instead of being
  dropped.
- **Organizations are the tenancy boundary.** Every deployment has the default organizations
  `/System`, `/Development`, and `/Public`, and each organization has its own keystore and vault.
  Per-organization values travel with the call and are never written into process-wide state.
- **Encryption at rest.** Fields marked `encrypt: true` are encrypted transparently through the
  organization vault: a per-organization asymmetric key pair, rotating symmetric keys, and a
  per-vault salt. Binary data payloads can also be gzip-compressed.
- **The vault splits key storage between the database and the filesystem.** The encrypted data,
  the data keys wrapped by the vault, and the secret key that protects the vault credential are
  kept in the database. The vault's enciphered private key and its protected credential are kept
  on the filesystem, outside the database. Recovering protected data needs both halves. So, in
  theory, someone who takes only the database (a dump, a backup, or SQL injection) or only the
  file store cannot decrypt vaulted fields. This also means the two halves must be backed up and
  restored together, because neither can be used without the other. Credentials are stored
  separately, as salted hashes, and cannot be recovered.
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
  (`PolicyUtil.setTrace`) shows which rule, pattern, or participation decided the outcome. Every
  decision is also kept as a signed `system.audit` record that includes its policy response.
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
  credentials, SCIM 2.0, WebAuthn, policy evaluation with tracing, and a signed audit trail of every authorization
  decision. Authorization as a service is ready in the
  policy layer, with the REST API pending.
- **LLM chat and memory.** Enterprise LLM conversations, described under
  [LLM chat](#llm-chat) below.
- **MCP server.** Model Context Protocol tools for AM7 data and ISO 42001 operations.
- **Olio.** World and character simulation driven by pluggable rules. A world configuration
  combines three kinds of rule: *context* rules (map and location setup, item data), *evolution*
  rules (what happens each increment), and *state* rules. A universe holds shared reference data
  (names, occupations, traits, a WordNet dictionary, location grids, and colors). Each world holds
  its own population, placed on an MGRS-style grid map. Characters have statistics, personality,
  instincts, apparel and wear levels, inventory, position, and needs.
  - **Hierarchy-of-needs rules (prototype, not complete).** The current rule set
    (`HierarchicalNeedsEvolveRule` with `NeedsUtil` and `Overwatch`) advances the world in hourly
    increments. It models physiological, safety, love/belonging, and esteem needs, for example food,
    water, shelter, sleep, security, health, friendship, family, status, and freedom.
  - **The design is a three-step cycle: Recommend, React, Result.** Each step is evaluated in
    order for the individual, their peer group, nearby groups, leadership, and authority groups.
    - *Recommend:* propose actions from the most pressing need and from the gap between a
      character's current and desired state, as chains of dependent actions. For example, eating
      with no food leads to foraging or hunting, and hunting needs a weapon or trap.
    - *React:* run those actions, resolving counter-actions and contested actions in turn order.
    - *Result:* feed the outcomes back into behavior and personality.
  - **Overwatch** tracks pending interactions, action results, and schedules across clock cycles.
    Actions (walk, gather, consume, sleep, dress, build, transfer, look, and others) are separate
    classes. What runs today is the base layer: hunger, thirst, and fatigue build up every hour for
    non-player characters and wear down health once a critical need is maxed. The full
    recommend/react/result loop is designed but not finished.
  - **Earlier population rules.** Epoch-based population evolution is still available: a year of
    monthly cycles producing birth, death, marriage, divorce, and immigration events.

  Olio also includes Stable Diffusion portrait and scene generation (SwarmUI/ComfyUI), voice
  (TTS/STT), face analysis, and tagging.
- **PictureBook / ChapBook.** Turns source text into illustrated books: scene extraction, a cast for
  each book world, a workflow graph, and image composition. Long operations run as background jobs
  that you poll (`?async=true` → `GET /rest/job/{jobId}`). Current design:
  [`src/aiDocs/PictureBookV3Design.md`](src/aiDocs/PictureBookV3Design.md).
- **ISO/IEC 42001.** AI bias-testing campaigns, differential scoring, reporting, and signed
  certification, with UI and MCP front ends. See [`src/aiDocs/ISO42001/`](src/aiDocs/ISO42001/).
- **Observability (optional).** A LiteLLM proxy and Langfuse tracing for every LLM call.

### LLM chat

LLM calls are ordinary AM7 records, so they get the same ownership, authorization, auditing, and
encryption as everything else.

- **Identity on both sides of the call.** A chat configuration (`olio.llm.chatConfig`) names a
  *system character* (the identity the model speaks as) and a *user character* (the identity of
  the person it is talking to). Both are person records (`olio.charPerson` inherits
  `identity.person`) with profiles, personality, and statistics. The request and session are owned
  by the authenticated user who made the call. Every exchange can therefore be attributed to the
  model's persona, the person it addressed, and the account that sent it.
- **Privacy-safe trace correlation.** When calls go through LiteLLM/Langfuse, AM7 sends the
  session and caller as trace identifiers (in a header, the `user` field, and a structured
  `metadata` object). They must be opaque objectIds, URNs, or nonces. `TracingIdValidator` drops
  any value that looks like a username or email address, so personal identifiers never reach the
  third-party trace store. A bad value cannot break a chat.
- **Request and response policies.** A chat configuration can attach a response policy template
  (`policyTemplate`: `general`, `rpg`, `bias`, `rpg.bias`, `clinical`), which is evaluated against
  every completion. The operations it can use include timeout, recursive-loop, wrong-character,
  refusal, bias-pattern, ideology-injection, masculine-softening, and age-up detection.
  Violations are reported to the client while the response streams and can trigger *autotuning*.
  In autotuning, a separate analysis model proposes a revised prompt, which is saved as a new
  record and never overwrites the original. The configuration can also reference a full
  `policy.policy` record for policy-engine routing of conversation requests. That reference is in
  development.
- **Compliance and quality checks.** An optional second-pass LLM evaluator (`complianceCheck`, run
  every *N* turns) checks each response for:
  - speaking as the user
  - voice that doesn't match the character's gender
  - drift from the character's profile and personality
  - ignoring age guidance
  - racial or orientation bias

  A local, non-LLM quality evaluator tracks echo, distinct-token, and memory-utilization scores and
  their trend. A content rating (ESRB-style `rating`) bounds what the configuration allows.
- **Connections and libraries.** LLM endpoints (Ollama, OpenAI-compatible, Azure, and LiteLLM) are
  `system.connection` records. Their API keys are vault-encrypted fields, and chat configurations
  reference a connection rather than embedding credentials. Prompt templates, chat configurations,
  and connections are published in shared, permissioned libraries. Prompts are composed from
  templates with conditional sections and are validated.
- **Built-in RAG.** Any record can be chunked and embedded with pgvector (`/rest/vector/vectorize`),
  using a configurable chunking strategy and size. Documents, records, or tags attached to a chat
  as persistent `contextRefs` are searched on every turn, and the matching passages are injected
  into the prompt as citations. A tag scopes the search to everything carrying it. The same vector
  store can be queried directly (`/rest/vector/reference`).
- **PageIndex.** Hierarchical, table-of-contents indexing of long documents (`/rest/pageIndex`:
  build, retrieve, tree, and node navigation). Retrieval walks the document structure and ranks
  leaf passages, instead of matching flat chunks. A chat can opt in (`usePageIndex`) to add
  PageIndex-ranked passages alongside the flat-vector citations.
- **Summarization.** Map-reduce summarization of large documents and records
  (`/rest/vector/summarize`), with a configurable number of concurrent LLM workers. Long-running
  conversations get rolling *keyframe* summaries every *N* turns, so they stay coherent beyond
  the context window. Sessions can be titled automatically (`autoTitle`).
- **Chat history.** Sessions and their messages are persisted records owned by the caller, so they
  can be reopened, browsed, and deleted through the API. History can also be stored with vector
  embeddings for semantic recall.
- **Long-term memory.** Memories (`tool.memory`) are extracted from conversations on a schedule.
  Each one is typed (fact, relationship, decision, insight, discovery, behavior, outcome, error
  lesson, note) and scored for importance, and the types to extract are configurable. Memories are
  keyed to the two people in the conversation rather than to one chat, so they carry over to
  every later conversation between the same pair. For each turn, memories are chosen within a
  token budget. The choice weighs relevance, recency (a half-life), diversity (MMR), and
  importance, and drops near-duplicates. Memories are injected as a system-prompt section or as an
  MCP context block. Per-conversation caps keep memory from growing without bound. The REST API
  (`/rest/memory`) searches memories by conversation, person, pair, or semantic query, and can
  create, extract, or delete them.
- **Gossip: recall across conversations.** With `gossipEnabled`, a character can recall what it
  learned in conversations with *other* people. AM7 finds memories that involve the current
  person and anyone other than the current partner, then offers them above a relevance threshold,
  up to a set limit. Characters can then mention things they heard elsewhere.
- **Interactions.** Structured `olio.interaction` records (who did what to whom, and the outcome)
  can be extracted from chats on their own schedule, feeding the Olio simulation.
- **Operational controls.** Responses stream over WebSocket with an idle watchdog. Async LLM work
  is serialized per chat, so retries don't pile up on the model server. A record/replay LLM
  emulator gives tests deterministic fixtures.

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
is [`src/aiDocs/dockerDevSetup.md`](src/aiDocs/dockerDevSetup.md).

**Start with the wrapper script**, `am7-docker-up.bat` (Windows) or `am7-docker-up.sh`
(Linux/macOS). It handles preparation that `docker-compose.test.yml` does not:

- **Creates the database volume on a genuine first run.** The Postgres data volume
  (`am7test_am7-test-pg`) is declared `external`, so a plain `docker compose up` on a fresh checkout
  fails with "external volume … not found". This is deliberate: compose never silently starts an
  empty database. The wrapper creates the volume only when the store has no `.setup.done`. If the
  store is already configured but the volume is missing, the wrapper refuses to start, because an
  empty database would not match the existing keystores.
- **Stages the Olio seed corpus** into `docker-data\am7\datagen` on every run. It calls
  `assemble-seed` with the staging directory set by `SEED_STAGING` in `src\.env`, and it copies
  the location data by default whenever you have staged it. The copy is idempotent, and the step is skipped with a warning if
  `SEED_STAGING` is unset. A fresh image ships this directory empty. Character and world generation and extraction 
  draw on reference data that is loaded into the Olio universe or shared organization library:
  - **Bundled in `src/seedData/seedData.7z`:** first names and surnames, occupations and trades,
    colors and patterns, and personality traits. Unzip it into your staging directory.
  - **Downloaded separately**, following the `readme.txt` in each directory of the extracted corpus:
    - **Word data:** the Princeton WordNet dictionary files, in `wn3.1.dict/dict/` (`data.noun`,
      `index.*`, and the rest; about 50 MB). These are required for character generation and extraction.
    - **Location data:** the GeoNames gazetteer extract, in `location/`. It is **optional and
      large**: about 1.5 GB for a handful of countries, mostly the US file (about 290 MB) and the
      alternate-names table (about 620 MB). It needs one `<ISO>.txt` file per country you want,
      for example `US.txt` or `CA.txt`, plus `countryInfo.txt`, `admin1CodesASCII.txt`,
      `admin2Codes.txt`, and `alternateNamesV2.txt`. Locations are loaded only when requested, and
      only for the countries staged. A world can run on the generated grid map without them.

    Put everything in the same staging directory before seeding. The seed scripts skip the
    `readme.txt` / `README.md` files when copying.
- **Sets runtime values** that would otherwise need to be exported by hand:
  - `CORS_ALLOWED_ORIGINS`: localhost, 127.0.0.1, the LAN IP, and the Vite dev ports.
  - Tomcat `CATALINA_OPTS` and the Postgres tuning overrides.
  - With `--ollama`, it points the app's embeddings at the container.
- **Layers optional compose files** when they apply: like any corporate-CA bundle
  (`docker-compose.extra-ca.yml`, used when `volatile\extra-ca.pem` exists), the NVIDIA GPU
  override, and the `llmproxy` env file.
- **Warns before an embedding-provider switch** on an existing data volume. Stored vectors carry no
  model provenance, so switching providers invalidates them.
- **Prints the ready-to-open setup URL**, with the one-shot token filled in, for localhost and the
  LAN.
- **Manages the lifecycle across every profile.** `--stop` and `--start` act on all containers.
  A bare `docker compose stop` misses the profile sidecars.

```powershell
cd src
.\am7-docker-up.bat                 # build + start  (--no-build, --prebuilt, --llmproxy, --ollama, --app-only, --stop, --start)
```

With raw compose you must do these steps yourself. At minimum, run
`docker volume create am7test_am7-test-pg` and the seed script first, then
`docker compose -p am7test -f docker-compose.test.yml up --build -d`.

1. **Open the setup URL.** The wrapper prints it. With raw compose, read the token from
   `src\docker-data\am7\store\.setup.token`; it never appears in `up -d` output or the logs. The URL
   is `https://localhost:9443/#!/setup?token=<TOKEN>`. Accept the self-signed certificate.
2. **Run first-time setup.** Choose the admin password, which applies to all three organizations.
   Use `password` on the `am7test` stack, because the Playwright helpers expect it. You can
   optionally create an initial user in `/Public` or `/Development` and set the media/AI server URLs
   here.
3. **Sign in** at `https://localhost:9443`.

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

Build from `src/`. Use `-o` (offline) once dependencies are cached; any corporate TLS proxy may inhibit downloads.

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
