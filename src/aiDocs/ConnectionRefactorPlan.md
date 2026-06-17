# Connection Model Refactor + Ux752 List-View Bug Fixes — Implementation Plan

Date: 2026-06-17

## Scope & Decisions

Three workstreams:
1. Split LLM connection info out of `chatConfig` into its own `olio.llm.connection` model (extends directory, has a system library for default connections), referenced from `chatConfig` via a foreign-key picker.
2. Add a property-gated column-dropping capability to the auto schema updater (so the clean break actually removes the old columns).
3. Fix three Ux752 list/view bugs (breadcrumb blanking, back-navigation blanking, session-expiry blank "Loading…").

**Decisions locked in:**
- **Connection fields:** move `serverUrl`, `apiKey`, `requestTimeout` into `olio.llm.connection`. `serviceType`, `apiVersion`, and `model` stay on `chatConfig` (LLM-chat-specific).
- **Migration:** clean break — inline fields are removed from `chatConfig`; records must reference a connection.
- **Schema drops:** NEVER use `-Dreset` / `properties.isReset()` or drop the schema entirely (Stephen does that himself). Removed columns are handled via a new, off-by-default property that lets the auto schema updater drop orphaned columns deliberately. All drops are logged and use `DROP COLUMN IF EXISTS`.

---

## Part 1 — Connection model (backend + Ux752)

### 1.1 New backend model `olio.llm.connection`
**New file:** `AccountManagerObjects7/src/main/resources/models/olio/llm/connectionModel.json`
- `inherits: ["data.directory", "common.description", "crypto.vaultExt"]`
  - `data.directory` → lives in groups/libraries (gets `groupId`/`groupPath` so the FK picker and the system library work).
  - `crypto.vaultExt` → `apiKey` encrypts exactly as today.
- Fields (lifted verbatim from `chatConfigModel.json`):
  - `serverUrl` (string, maxLength 512, default `http://192.168.1.42:11434`)
  - `apiKey` (string, maxLength 256, `provider: org.cote.accountmanager.provider.EncryptFieldProvider`, `encrypt: true`)
  - `requestTimeout` (int, default 120)
- Add a model-name constant `MODEL_CONNECTION = "olio.llm.connection"` alongside `MODEL_CHAT_CONFIG` (in `OlioModelNames`/`ModelNames`); verify the resource is on the model load list the same way `chatConfig`/`chatOptions` are.

### 1.2 Edit `chatConfigModel.json`
`AccountManagerObjects7/src/main/resources/models/olio/llm/chatConfigModel.json`
- **Remove** `serverUrl`, `apiKey`, `requestTimeout`.
- **Add** FK field:
  ```json
  { "name": "connection", "type": "model", "baseModel": "olio.llm.connection", "foreign": true }
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
- Add `olio.llm.connection` model entry (mirror JSON: inherits + three fields).
- Edit `olio.llm.chatConfig` entry (~line 8769): remove `serverUrl`/`apiKey`/`requestTimeout`, add `connection` model/foreign field.

### 2.2 `formDef.js`
`AccountManagerUx752/src/core/formDef.js`
- `forms.chatConfig`: remove serverUrl/apiKey/requestTimeout entries; add a `connection` picker field (shape like existing `systemCharacter`/`policy` pickers — `format: 'picker'`, `pickerType: "olio.llm.connection"`, `pickerProperty: { selected: "{object}", entity: "connection" }`).
- Add `forms.connection`: `name`, `description`, `serverUrl`, `apiKey`, `requestTimeout`. The generic editor `views/object.js` renders any model with a formDef at `/view/olio.llm.connection/:id` and `/new/olio.llm.connection/:id` — no new component needed.

### 2.3 System library wiring for connections
- Add `'olio.llm.connection': 'connection'` to `libTypeMap` in `views/list.js` `openSystemLibrary()` (~line 454), and add the `'connection'` case to `LLMConnector.getLibraryGroup`. Verify the service endpoint backing `getLibraryGroup` resolves the new library name from `ChatLibraryUtil`.

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
