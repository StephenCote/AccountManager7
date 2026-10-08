# Connection Model Refactor + Ux752 List-View Bug Fixes — Implementation Plan

Date: 2026-06-17

## Status 2026-10-07

- **Model name as shipped: `system.connection`** — not `olio.llm.connection` as this plan originally
  proposed. Lives at `AccountManagerObjects7/src/main/resources/models/system/connectionModel.json`
  (inherits `data.directory`, `common.description`, `crypto.vaultExt`); constant
  `ModelNames.MODEL_CONNECTION = "system.connection"` (`ModelNames.java:161`); `chatConfigModel.json:169-171`
  declares `connection` with `baseModel: "system.connection"`. The model name has been corrected
  throughout this document (2026-10-07); the file path in §1.1 was corrected to match. The model has since
  grown `dialect` (`ConnectionDialectEnumType`) and `upstream` (`ConnectionUpstreamEnumType`) fields — see
  `LiteLLMLangfuseIntegrationDesign.md` and `.claude/rules/architecture.md` "Config: DB-backed vs boot-pinned".
- **Backend: COMPLETE.** Clean break in `Chat.configureChat` (`Chat.java:688-724`): it reads the FK id off
  `chatConfig.connection`, loads the `system.connection` by reference with an explicit projection
  (`id, groupId, serverUrl, requestTimeout, apiKey, dialect, upstream`), and logs a warning — no inline
  fallback — when the reference is absent or cannot be loaded. Library seeding is
  `ChatLibraryUtil.populateDefaults` (`ChatLibraryUtil.java:152`) → `createLibraryConnection` (`:236`),
  `LIBRARY_CONNECTIONS = "Connections"` (`:29`).
- **Client (Ux752) migration: COMPLETE (2026-10-07, later the same day).** The list below is the
  morning survey of what *was* still reading the pre-refactor inline `serverUrl` / `apiKey` /
  `requestTimeout` on `chatConfig`; every entry has since been migrated to the `connection` FK — see
  "COMPLETE — 2026-10-07" below for the final, verified caller list. Kept for history only:
  - `src/chat/LLMConnector.js:144,183,245,283` (`syncFields` / `cloneFields` lists include `serverUrl`, `requestTimeout`)
  - `src/magic8/ai/SessionDirector.js:838-843` (requires `fullTemplate.serverUrl` on the "Open Chat" template)
  - `src/cardGame/ai/llmBase.js:62,132` (`ensureConfig(name, template, overrides, dir)` via the connector)
  - `src/chat/chatUtil.js:57-58` (`makeChat` sets `icfg.api.serverUrl(...)`/`serviceType(...)` on a new chatConfig)
    — callers `src/games/wordGame.js:270` (`makeChat(chatName, "herm-local", "http://localhost:11434", "ollama")`)
    and `src/components/moodRing.js:82` (`makeChat("MoodRing", null, null, null)`)
  - `src/chat/ChatSetupWizard.js:49` (collects `serviceType`, `serverUrl`, `model` — presets at `:18-26`)
  - `src/test-harness/llmTestSuite.js:151,198,368-383,1692` (note: path is `src/test-harness/`, not a top-level `test-harness/`)
  - `e2e/helpers/console.js:32,41` was listed in the survey, but on 2026-10-07 those lines are console-noise
    allowlist regexes with no `serverUrl`/`chatConfig` reference — **not verified as a caller**; re-check before
    treating it as one.
- Part 3 (list/view bug fixes) is not tracked here; see `KnownIssues.md`.

## COMPLETE — 2026-10-07 (Parts 1 and 2)

The connection refactor is finished on both sides: no Java main source and no Ux752 source reads
`serverUrl` / `apiKey` / `requestTimeout` off `olio.llm.chatConfig` any more (grep survey of
`AccountManagerObjects7`, `AccountManagerISO42001`, `AccountManagerService7`, `AccountManagerAgent7`,
`AccountManagerConsole7` main sources and `AccountManagerUx752/src`, 2026-10-07). The one stale caller
found is listed last; everything else below is the final, exhaustive list of call sites that resolve the
endpoint **through the `system.connection` record**. Line numbers are from the 2026-10-07 survey.

### Backend — reads of the connection (resolution)

| Call site | What it resolves | Notes |
|---|---|---|
| `Chat.configureChat` — `Chat.java:696-724` | `serverUrl`, `requestTimeout`, `apiKey`, `dialect`, `upstream` | **The** resolution point for every chat turn. Reads the FK id off `chatConfig.connection`, re-queries `system.connection` by id with the explicit projection at `:710`, then `setServerUrl` / `setRequestTimeout` / `setAuthorizationToken`. WARN branches at `:697-704` (no reference) and `:720-723` (dangling reference), no inline fallback. `TestResumedSessionLiveLlm` (2026-10-07) pins the WARN text. |
| `Chat.checkRemote` — `Chat.java:926-932` | `apiKey` (write-back on the copied connection) | Remote/proxied copy of the chatConfig: the copied `connection` sub-record is re-stamped with the decrypted `authorizationToken` because `copyRecord` drops vault metadata. |
| `Chat.getRequestTimeout` / `getServerUrl` — `Chat.java:672`, `:806` | derived getters | Consumers: `ChatUtil.java:1203`, `:1318` (per-chunk / per-call LLM timeouts), `PageIndexUtil.java:915` (streamed await), `Chat.java:4570` (`OllamaModelUtil.recordUsage(getServerUrl(), …)`). |
| `ChatUtil.getOpenAIRequest` diag — `ChatUtil.java:1053-1056` | `serverUrl` (log only) | Diagnostic line; reads `prompts.chatConfig.get("connection")` for the WARN/INFO text. |
| `ChatUtil` copy path — `ChatUtil.java:1872-1881` | `apiKey` | Copies `srcConn.apiKey` onto the cloned chatConfig's connection so `configureChat` can decrypt it. |
| `ChatUtil` — `ChatUtil.java:1014`, `:2042` | the `connection` FK itself | `:1014` carries the FK onto a library-derived chatConfig; `:2042` reads it for the request/dialect decision. |
| `ServerConfigUtil.getServerUrl` / `resolveConnection` — `ServerConfigUtil.java:98`, `:217-226` | `serverUrl`, `apiKey` (media/AI server records) | The six deployment-global `/System` connections (SD, TTS, …) — same model, different purpose; projection at `:217`. Not an LLM chat path. |

### Backend — writers of the connection

| Call site | Writes |
|---|---|
| `ChatLibraryUtil.createLibraryConnection` — `ChatLibraryUtil.java:236-253` | `serverUrl`, `requestTimeout` (library seeding; called from `populateDefaults` `:152`) |
| `ChatService` `POST /rest/chat/library/init` — `ChatService.java:262-280` | reads `serverUrl`/`model`/`serviceType` off the ad-hoc JSON body and hands them to `ChatLibraryUtil.populateDefaults` |
| `ServerConfigUtil` patch — `ServerConfigUtil.java:357-378` | `serverUrl`, `apiKey` on the `/System` media connections |
| Console7 `OlioAction` — `OlioAction.java:113-164`, `:591` | `-connection` / `-serverUrl` / `-apiKey` options create-or-patch a `system.connection` and link it |
| Console7 `ServerConfigAction` — `ServerConfigAction.java:54-70` | `-url` / `-apiKey` for the `/System` media connections |

### Client (Ux752) — all resolved through the `connection` FK

| Call site | Role |
|---|---|
| `src/chat/LLMConnector.js:37-43` (`CONNECTION_FIELDS`), `:134-150`, `:182`, `:195-199` (`requestTimeoutMs`), `:250`, `:364`, `:406` | Resolves the referenced `system.connection` (projection `id, objectId, name, groupId, serverUrl, requestTimeout, dialect, upstream`), defaults `requestTimeout` to 120 s when the serializer omitted it, and derives the client-side hard timeout from it. `apiKey` is never read client-side. |
| `src/chat/LLMConnector.js:899-905` (`initLibrary`) | Posts `serverUrl`/`model`/`serviceType` to `/rest/chat/library/init` (the only client writer). |
| `src/chat/ChatSetupWizard.js:4-7`, `:21-52`, `:92-145` | Collects `serverUrl` for `initLibrary`; the resulting chatConfigs carry only the FK. |
| `src/magic8/ai/SessionDirector.js:858` | Reads `conn.serverUrl` for a diagnostic label only (`'unresolved'` when no connection). |
| `src/chat/chatUtil.js:42-46` (`makeChat`), callers `src/games/wordGame.js:270`, `src/components/moodRing.js:82` | Endpoint comes from the library connection; no `serverUrl` argument any more. |
| `src/test-harness/llmTestSuite.js:147`, `:380-382` | Test harness reports the resolved connection's `serverUrl`/`requestTimeout`. |
| `e2e/helpers/console.js:32,41` | Confirmed 2026-10-07: console-noise allowlist regexes, **not** a caller. Removed from the migration list. |

### Stale caller (open — not fixed here)

- **`AccountManagerService7` `GameService.java:652-653`** still calls
  `getReader().populate(chatConfig, new String[]{"serverUrl", "apiKey", "model", "serviceType", "apiVersion", "chatOptions"})`.
  `serverUrl`/`apiKey` are no longer fields of `olio.llm.chatConfig`, so the projection names two
  non-existent columns; the populate either errors or leaves `model`/`chatOptions` unpopulated before
  `GameUtil.chat(...)`. Fix is to drop the two names and add `"connection"` (which `Chat.configureChat`
  then resolves itself). Found during this survey; left for the game-chat owner to change and test
  (needs a live `GameUtil.chat` run).

## DECISION (open, 2026-10-07) — retiring `olio.llm.chatConfig.serviceType`

Survey only; **no behavior changed**. `LiteLLMLangfuseIntegrationDesign.md` §5.1 decided to converge
on `system.connection.dialect` and deferred removal of `serviceType`. This section records what
still reads the field, what breaks if it is removed, and a recommended path. Line numbers are as of
2026-10-07 and will drift.

**Two distinct things share the name.** The persisted model field `olio.llm.chatConfig.serviceType`
(`chatConfigModel.json:17-22`, `LLMServiceEnumType`, deprecated) and the in-process
`Chat.serviceType` field (the *resolved* dialect, ~70 uses in `Chat.java`, plus `EmbeddingUtil`,
`VoiceUtil`, `VectorUtil`, `ClientUtil`, `PageIndexUtil`, Agent7 `Assistant`, `ConsoleMain`, which
construct an `LLMServiceEnumType` from properties, never from the chatConfig). Only the model field
is up for retirement; `LLMServiceEnumType` itself stays (it also carries `LOCAL`, which has no
`ConnectionDialectEnumType` peer — `ChatUtil.java:1936`, `EmbeddingUtil.java:179-298`).

### Readers of the model field

| Module | Site | What it does |
|---|---|---|
| Objects7 | `ChatUtil.resolveServiceType` `:1938-1953` | **The fallback.** `dialect` wins when non-`UNKNOWN`; else `chatConfig.getEnum("serviceType")`. |
| Objects7 | `ChatUtil.getMaxTokenField(cfg)` `:2108`, `supportsSamplingParams(cfg)` `:2182`, `applyChatOptions(req,cfg)` `:2266` | Single-arg convenience overloads that read `cfg.getEnum("serviceType")`. **No main-source callers** — only `TestChatOptions`, `TestChatPhase12`, `TestGpt5TemperatureFix`. |
| Objects7 | `ChatUtil.java:1017`, `ChatLibraryUtil.java:225` | Copy `serviceType` onto the library/default chatConfig at creation. |
| Objects7 | `ChatUtil.java:1057`, `Chat.java:926` (copyRecord list), `ChatListener.java:600`, `ChatAutotuner.java:128` | Diagnostics / prompt text; `ChatListener` actually reports the in-process resolved value. |
| Service7 | `ChatService.java:274` | Library-init request body param → `populateDefaults`. |
| Service7 | `GameService.java:653` | Stale populate list (see "Stale caller" above). |
| Console7 | `OlioAction.java:111,149-150,587-588` | `-serviceType` CLI option, documented as the deprecated fallback. |
| Ux752 | `LLMConnector.js:258` (`syncFields`), `:301` (`cloneFields`), `:899-905` (`initLibrary`); `chatUtil.js:66`; `LLMDebugPanel.js:92`; `SessionDirector.js:861`; `formDef.js:5418`; `modelDef.js` (2); `llmTestSuite.js:158,210,1703`; tests `appPanel.test.js`, `dialectUi.test.js` | Form field, clone/sync lists, debug display. |
| Tests | 67 Java test files reference `serviceType`/`LLMServiceEnumType`; most set it on a chatConfig they create. |

### What breaks if the field is removed today

Measured on `am7db` 2026-10-07 (`a7_olio_llm_chatconfig_0_1` joined to `a7_system_connection_0_1`):

| Connection state | chatConfig rows | Effect of removing the fallback |
|---|---|---|
| connection dialect `OPENAI_COMPAT` | 30 | none — dialect authoritative |
| connection dialect `UNKNOWN` | 34 (`OLLAMA` 24, lowercase `ollama` 7, `OPENAI` 3) | **lose transport selection** — `resolveServiceType` returns null/UNKNOWN |
| no connection row (`connection` null/0) | 11 (all `OPENAI`) | already WARN in `configureChat`; today the fallback still yields a dialect |

So 45 of 75 rows depend on the fallback. Connection-side: 33 `UNKNOWN`-dialect and 135 `EMULATOR`
rows exist; the 51 `OLLAMA|UNKNOWN` and 80 `OPENAI_COMPAT|UNKNOWN` rows are fine for dialect but
have no `upstream` (see the next DECISION). Removal also needs: the three single-arg `ChatUtil`
overloads and their three test classes; `ChatService` library-init body param and Console7
`-serviceType` (both become no-ops or errors); Ux752 form/sync/clone lists (`dialectUi.test.js`
asserts the field is present); the `varchar(16)` column (drop only through the gated
`removeFieldFromSchema`, `objects7-reference.md`).

### Recommended path (one-line: keep the fallback until the data no longer needs it, then remove in one tracked change)

1. **Data first, no code:** set `dialect` on the 33 `UNKNOWN` connections and attach a connection to
   the 11 connection-less chatConfigs (operator task; Ux752 connection editor already patches
   `dialect` with `name` + identity). Re-run the join above until the "lose transport selection" row
   is zero.
2. **Then one change, not a drive-by:** delete the three single-arg `ChatUtil` overloads (migrate the
   three tests to the two-arg form), drop the copy sites (`ChatUtil:1017`, `ChatLibraryUtil:225`,
   `ChatService:274`, `OlioAction`), remove the Ux752 form/sync/clone entries and `dialectUi.test.js`
   assertions, make `resolveServiceType` return `UNKNOWN` (not fall through) and log an ERROR naming
   the chatConfig, and mark the model field `ephemeral` for one release before removing it so stale
   clients that still send it do not fail deserialization.
3. **Not recommended:** removing the field while `UNKNOWN`-dialect connections exist, or auto-writing
   `dialect` from `serviceType` at read time (that is a write on a read path — see `architecture.md`).

## DECISION (open, 2026-10-07) — where `upstream` (and `dialect`) belong

Survey only; **no behavior changed**. Both fields live on `system.connection` today
(`connectionModel.json:29-42`); `dialect` by the approved §2.2 design ("endpoint protocol is a
property of the endpoint"), `upstream` by KI-72's resolution (an explicit per-connection family axis,
inferred from `dialect` when unset, `OPENAI_COMPAT -> UNKNOWN` mandatory).

**The placement problem.** `dialect` really is a property of the URL. `upstream` is a property of
the **(URL, model) pair**: a single LiteLLM proxy fronts both families at once —
`litellm/config.yaml` serves `gpt-5.6-terra` (Azure) and `qwen3:8b`, `qwen3:8b-ctr`,
`qwen3:8b-jos-ctr`, `way-local` (all Ollama) from one base URL. With `upstream` on the connection
the operator must create **two `system.connection` rows with the same `serverUrl`/`apiKey`** and
pick the right one per chatConfig; picking wrong silently sends Ollama extension params
(`num_ctx`, `num_gpu`, `think:false`, `repeat_penalty`) to Azure or drops them for Ollama. The
`am7db` numbers above show the current state: 99 `OPENAI_COMPAT|OLLAMA` rows against 80
`OPENAI_COMPAT|UNKNOWN` — nobody can tell from the row which of the 80 are "really Azure" and which
are proxied Ollama with the field unset (the `configureChat` WARN fires for every chatConfig that
uses any of the 80).

### Options

| | Option | Pros | Cons |
|---|---|---|---|
| A | **Keep as is** — `upstream` on `system.connection`; two rows per proxy. | Zero change; matches KI-72; Ux752 editor and `upstreamUi.test.js` already cover it. | Duplicate URL/apiKey rows; wrong-row picks are silent; the 80 `UNKNOWN` rows stay ambiguous. |
| B | **Add an `upstream` override on `olio.llm.chatConfig`** (default `UNKNOWN`), resolved as `chatConfig.upstream` if set else `connection.upstream` else dialect inference. Additive, nullable enum → boot DDL patch, no migration (`objects7-reference.md` Path 1). | One proxy row; the model-specific fact sits next to `model`; `resolveUpstream(connection, service)` grows one argument at its single convergence point (`Chat.configureChat:744`, `ChatUtil:1835`). | Reintroduces a per-chatConfig transport field just as `serviceType` is being retired; two places to look; Ux752 form + `LLMConnector` sync/clone lists change. |
| C | **Move `upstream` to `chatConfig` only** (drop it from the connection). | Single source for the per-model fact. | Breaks the 100 connection rows that already carry it; forces every chatConfig to restate it; contradicts the KI-72 resolution without new evidence. |
| D | **Infer per model at the proxy** — ask LiteLLM `/model/info` (or `/v1/models`) for `litellm_params.model` prefix (`ollama/`, `azure/`) and cache per (connection, model). | No schema change; always right for LiteLLM. | Network call on the config path; LiteLLM-specific; nothing comparable for other `OPENAI_COMPAT` servers; cache is process-global per-org state (`architecture.md` prohibition unless keyed per call). |

**Recommendation (one line):** B — additive `chatConfig.upstream` override, connection value as the
default; it keeps KI-72's row-level contract for the common case and removes the duplicate-row
requirement for multi-family proxies. Do not do C. D could later feed B's default but is not a
placement answer.

## Scope & Decisions

Three workstreams:
1. Split LLM connection info out of `chatConfig` into its own `system.connection` model (extends directory, has a system library for default connections), referenced from `chatConfig` via a foreign-key picker.
2. Add a property-gated column-dropping capability to the auto schema updater (so the clean break actually removes the old columns).
3. Fix three Ux752 list/view bugs (breadcrumb blanking, back-navigation blanking, session-expiry blank "Loading…").

**Decisions locked in:**
- **Connection fields:** move `serverUrl`, `apiKey`, `requestTimeout` into `system.connection`. `serviceType`, `apiVersion`, and `model` stay on `chatConfig` (LLM-chat-specific).
- **Migration:** clean break — inline fields are removed from `chatConfig`; records must reference a connection.
- **Schema drops:** NEVER use `-Dreset` / `properties.isReset()` or drop the schema entirely (Stephen does that himself). Removed columns are handled via a new, off-by-default property that lets the auto schema updater drop orphaned columns deliberately. All drops are logged and use `DROP COLUMN IF EXISTS`.

---

## Part 1 — Connection model (backend + Ux752)

### 1.1 New backend model `system.connection`
**New file:** `AccountManagerObjects7/src/main/resources/models/system/connectionModel.json` (as shipped; the plan originally said `models/olio/llm/` — corrected 2026-10-07)
- `inherits: ["data.directory", "common.description", "crypto.vaultExt"]`
  - `data.directory` → lives in groups/libraries (gets `groupId`/`groupPath` so the FK picker and the system library work).
  - `crypto.vaultExt` → `apiKey` encrypts exactly as today.
- Fields (lifted verbatim from `chatConfigModel.json`):
  - `serverUrl` (string, maxLength 512, default `http://192.168.1.42:11434`)
  - `apiKey` (string, maxLength 256, `provider: org.cote.accountmanager.provider.EncryptFieldProvider`, `encrypt: true`)
  - `requestTimeout` (int, default 120)
- Add a model-name constant `MODEL_CONNECTION = "system.connection"` (shipped in `ModelNames.java:161`, on the `ModelNames` load list) alongside `MODEL_CHAT_CONFIG`; verify the resource is on the model load list the same way `chatConfig`/`chatOptions` are.

### 1.2 Edit `chatConfigModel.json`
`AccountManagerObjects7/src/main/resources/models/olio/llm/chatConfigModel.json`
- **Remove** `serverUrl`, `apiKey`, `requestTimeout`.
- **Add** FK field:
  ```json
  { "name": "connection", "type": "model", "baseModel": "system.connection", "foreign": true }
  ```
- Keep `serviceType`, `apiVersion`, `model`. Keep `inherits crypto.vaultExt`.

### 1.2a Property-gated column dropping in the auto schema updater
Replaces any manual `ALTER TABLE` / reset. The updater is currently additive-only (`getMissingColumns` → `ADD COLUMN`); add the inverse, gated behind a new off-by-default flag.

- **`IOProperties.java`** — add `boolean dropColumns = false` + `isDropColumns()`/`setDropColumns()`.
- **`DBUtil.java`**:
  - `getOrphanedColumns(ModelSchema)` — live columns from `getTableColumns()` not matching any **persisted** model field. Must mirror `generateSchema`'s column emission exactly (skip virtual/ephemeral/referenced; include FK columns; include inherited fields; same name normalization `getColumnName(...).replace("\"","").toLowerCase()`). **Risk:** a mismatch could drop a legitimate column — mitigated by precise mirroring + off-by-default + per-drop logging.
  - `generateDropColumnSchema(ModelSchema)` → `ALTER TABLE <t> DROP COLUMN IF EXISTS <col>;` per orphan.
- **`IOSystem.java`** (after the `ADD COLUMN` patch loop, ~line 140) — only if `properties.isDropColumns()`, run drop statements with `logger.warn("Schema drop: " + stmt)`.
- **Flag wiring:**
  - Objects7 test `resource.properties`: `db.schema.dropColumns=false`, read where the test `IOProperties` is built (BaseTest) → `setDropColumns(...)`.
  - Service7 `web.xml`: new `database.dropColumns` context-param (default false), read in `RestServiceEventListener` next to `database.checkSchema` → `props.setDropColumns(...)`.
  - Console7 `ConsoleMain`: read the same property for parity.
- **Safety:** off by default, never drops tables / never resets, idempotent (`IF EXISTS`), every drop logged. Stephen flips the flag deliberately to clean up `serverUrl`/`apiKey`/`requestTimeout`, then can turn it back off.
- **Test:** extend `TestSchemaModification.java` — remove a field from an in-memory `ModelSchema`; flag on → column dropped; flag off → column remains. Real DB.

Table name reference: `A7_olio_llm_chatConfig_<version>` (verify actual version via `DBUtil.getTableName("olio.llm.chatConfig")`).

### 1.3 `Chat.java` — read connection via sub-record
`AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/Chat.java`
- `configureChat()` (~365): replace `chatConfig.get("serverUrl"/"apiKey"/"requestTimeout")` with reads off `BaseRecord conn = chatConfig.get("connection")` (null-guard; log clear warning if `conn == null` — no inline fallback after the clean break).
- `checkRemote()` (~511): `copyRecord(...)` list currently includes `"serverUrl"`; change to copy `"connection"` (plus existing `apiVersion`, `serviceType`, `model`, `chatOptions`). The apiKey re-set hack (encrypted field loses vault metadata on copy) must now re-set `apiKey` on the copied **connection** sub-record from the decrypted `authorizationToken`.

### 1.4 `ChatUtil.java` — make the query populate `connection`
`AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/ChatUtil.java`
- `getCreateChatConfig()` (~342): `OlioUtil.planMost(q)` recurses foreign models, but verify `connection` is actually planned. If the filter drops it, add an explicit sub-plan:
  `q.plan().plan("connection", new String[]{"serverUrl","apiKey","requestTimeout","keyId","vaultId","vaulted","vaultedFields"})` and ensure `"connection"` is in `q.getRequest()`. The decrypt path needs the vault metadata fields in the projection.
- Mirror anywhere else chatConfig is loaded for use (grep `MODEL_CHAT_CONFIG` + `planMost`).

### 1.5 `ChatLibraryUtil.java` — system library for default connections
`AccountManagerObjects7/src/main/java/org/cote/accountmanager/olio/llm/ChatLibraryUtil.java`
- Add `LIBRARY_CONNECTIONS` constant + `getCreateConnectionLibrary(user)` (mirror `getCreateChatConfigLibrary` via `LibraryUtil.getCreateSharedLibrary`).
- Add `createLibraryConnection(adminUser, libDir, name, serverUrl, requestTimeout)`.
- Change `createLibraryChatConfig(...)` to create/look-up a connection in the connection library and set the `connection` FK; `serviceType` and `model` stay on the chatConfig.
- Seed a default connection (e.g. "Local Ollama") on library init so new chatConfigs have something to pick.

### 1.6 Backend tests (real, live DB — per CLAUDE.md)
- Update every test setting `serverUrl`/`apiKey`/`requestTimeout` on chatConfig to instead create+link a `connection`: `TestChat`, `TestChatOptions`, `TestChatPhase10–13`, `TestChatStream`, `TestChatPolicy`, `TestChatAsync`, and Agent7 `TestMemoryPhase2`, `TestMemoryDuel`, `TestKeyframeMemory`.
- New `TestConnection.java`: create connection → link to chatConfig → reload via `getCreateChatConfig` → assert `chatConfig.get("connection").get("serverUrl")` is populated (explicit guard against the null/unpopulated bug) and `apiKey` decrypts.
- Run `mvn test -Dtest=TestConnection,TestChat,TestChatOptions` (+ schema test) and report actual `BUILD SUCCESS`/failures.

---

## Part 2 — Ux752 model + form

### 2.1 `modelDef.js`
`AccountManagerUx752/src/core/modelDef.js`
- Add `system.connection` model entry (mirror JSON: inherits + three fields).
- Edit `olio.llm.chatConfig` entry (~line 8769): remove `serverUrl`/`apiKey`/`requestTimeout`, add `connection` model/foreign field.

### 2.2 `formDef.js`
`AccountManagerUx752/src/core/formDef.js`
- `forms.chatConfig`: remove serverUrl/apiKey/requestTimeout entries; add a `connection` picker field (shape like existing `systemCharacter`/`policy` pickers — `format: 'picker'`, `pickerType: "system.connection"`, `pickerProperty: { selected: "{object}", entity: "connection" }`).
- Add `forms.connection`: `name`, `description`, `serverUrl`, `apiKey`, `requestTimeout`. The generic editor `views/object.js` renders any model with a formDef at `/view/system.connection/:id` and `/new/system.connection/:id` — no new component needed.

### 2.3 System library wiring for connections
- Add `'system.connection': 'connection'` to `libTypeMap` in `views/list.js` `openSystemLibrary()` (~line 454), and add the `'connection'` case to `LLMConnector.getLibraryGroup`. Verify the service endpoint backing `getLibraryGroup` resolves the new library name from `ChatLibraryUtil`.

---

## Part 3 — List/view bug fixes

### Bug (a): breadcrumb goes blank moving deeper
**Root cause:** `components/breadcrumb.js:67` `if (!contextLoaded) return crumbs;` returns `[]` during the async `am7client.get` of the newly-selected group, so the bar blanks whenever the route `objectId` changes to a not-yet-cached group.
**Fix:** keep a `lastRenderedCrumbs` (module-scoped or on `page.context()`) and return it while the new context is `'pending'` instead of `[]`; replace only once the fetch resolves and triggers redraw. Verify `contextObjects[objectId]` cache key matches the new `objectId` per hop.

### Bug (b): back-to-parent blanks the list
**Root cause:** `views/list.js:374` `navigateUp()` bails with `if (!pg.container) return;`. After `navByRoute` → route change → `update()` → `pagination.update()` starts an **async** container fetch (`pagination.js` ~289); a back-click before it resolves no-ops, leaving list/breadcrumb blank.
**Fix:** when `pg.container` is null, resolve it first — `am7client.get(type, m.route.param('objectId'), …)` (reuse the `contextObjects` cache), then derive `parentPath`/parent and continue the existing navigation in the callback. Apply to the `auth.group` and `isParent` branches. Audit the `pagination.pages().container = null` resets in `navInPlace`/`navigateDown` so the refetch repopulates before `doCount` paints an empty list.

### Bug (c): session expiry shows blank "Loading…" instead of redirecting to login
**Root cause:**
- On server restart the websocket closes; `core/pageClient.js` `reconnect()` retries `loginWithPassword` with a stale `page.token`. On failure it shows a toast but never clears `page.user` and never routes to `/sig`.
- `page.authenticated()` (`pageClient.js:708`) stays `true` because `page.user` is still set.
- `views/object.js:1284` renders `'Loading...'` when `!page.authenticated() || !inst`; auth stays "true" but data fetches fail → stuck on "Loading…".
- HTTP 401 path does redirect (`am7client.js` `_handle401` → `m.route.set("/sig")`), but catch blocks then call `fH()` with no args, so callback-based views just get `undefined` and sit on "Loading…". The websocket-reconnect-failure path has no redirect at all.
**Fix:**
1. In `reconnect()`'s failure branch (and when `wsMaxReconnectAttempts` is exhausted): clear session (`page.user = null`, drop `page.token`), stash current route in `sessionStorage["am7.returnRoute"]`, and `m.route.set("/sig")`. Factor a single `forceLogin()` helper shared by `_handle401` and the reconnect-failure path.
2. Make `object.js` (and list/pagination "Loading…" states) distinguish "authenticated but loading" from "not authenticated": if `!page.authenticated()`, route to `/sig` rather than render "Loading…" forever.
3. Verify `/sig` (`views/sig.js`) consumes `am7.returnRoute` and returns there after re-login.

### Part 3 verification (real, per CLAUDE.md, `ensureSharedTestUser()`, live backend at localhost:8443)
- (a) Click into nested groups (multi-level) → assert breadcrumb non-empty at each hop.
- (b) Into a sub-group then immediately back → assert list shows the parent's children (non-empty) and breadcrumb shows the parent.
- (c) Load a view, simulate session loss (invalidate cookie / restarted backend / mock 401 + ws close) → assert app lands on `/sig` within a bounded time, not stuck on "Loading…"; confirm post-login return to original route.
- `npx vite build` + `npx vitest run` for unit-testable pieces (breadcrumb trail builder).

---

## Suggested execution order
1. Schema-updater property (1.2a) + connection model (1.1–1.2) → `mvn compile`.
2. Chat.java/ChatUtil.java (1.3–1.4) + library (1.5) → backend tests (1.6).
3. Ux752 modelDef/formDef/library wiring (Part 2) → manual + build.
4. List/view bug fixes (Part 3: a, b, c) → Playwright.

## Open items / risks
- **Query projection of vaulted sub-record (1.4)** is highest risk — connection `apiKey` must return with `keyId`/`vaultId` or decryption fails. Proven by `TestConnection` before UI wiring.
- **`getOrphanedColumns` mirroring (1.2a)** must exactly match the generator's column emission to avoid dropping legitimate columns; off-by-default + logging mitigate.
- Clean break means existing chatConfig rows lose their endpoint until re-pointed at a connection; the seeded default connection (1.5) covers new ones. Re-link script available on request for hand-made records.
