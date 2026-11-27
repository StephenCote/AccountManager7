# Service Status app: design of record (2026-11-27)

Supersedes `SystemCheckDesign.md` (the 2026-11-17 proposal). Built from the planner's plan, Stephen's
decisions of 2026-11-27, and the architect's pre-code review (CHANGES-NEEDED B1–B6, all folded in below).

## 1. Decisions (Stephen, 2026-11-27)

| # | Decision |
|---|---|
| D1 | A **new standalone Ux752 app**, "Service Status" at `#!/admin/status`, with its own feature id `serviceStatus` (`required:false`, `deps:["core"]`). Orgs that already have a saved feature set must enable it in Feature Config; the e2e enables it and restores the old set. |
| D2 | Scope covers the db, the six `/System` servers (sd, face, tag, voice.tts, voice.stt, embedding), the embedding type/URL shape, and **every `system.connection` the caller can read**, including the LLM connections behind the chatConfigs the caller can read. Each is read and tested **as the caller through PBAC**. |
| D3 | The default **Test** is a dialect-aware reachability/auth probe with 5 s timeouts. The opt-in **Deep test** sends one real minimal request with a **30 s** timeout. |
| D4 | Every full endpoint is `@RolesAllowed admin`. `GET /rest/system/check/summary` is open to role `user` and returns names only. |
| D5 | The six `/System` URLs are shown **only to `/System` administrators** (`AdminMaintenanceUtil.isSystemOrganizationAdministrator`). Other admins see status, latency, type and `hasApiKey`, and "configured in /System" in place of the URL. |
| D6 | This slice also includes: a **user-facing degraded banner** (summary fetched at app start), **vector row counts** (informational), and adding `serviceStatus` to the **enterprise / compliance profiles**. |

## 2. Layering

- All logic lives in Objects7: `util/SystemCheckUtil.java` (orchestration, authorization, cache) and
  `util/ConnectionProbeUtil.java` (network only).
- Service7 `SystemCheckService` is transport only.
- The web.xml fallback URLs reach Objects7 as a plain `Map<String,String>` built from
  `ServerConfigUtil.SERVER_INIT_PARAMS`.
- **The service never accepts a URL from the client.** Its inputs are an objectId, a server name from a
  fixed whitelist, and `deep`.
- **Diagnostics may make outbound calls but persist nothing.** The only state they keep is the in-memory
  cache in §6.

## 3. Checks

All probes use `java.net.http.HttpClient` with a 5 s connect and 5 s request timeout and the default trust
store, never a trust-all `SSLContext`. Redirects are never followed (`Redirect.NEVER`). `ClientUtil` is not
used: `ClientUtil.java:263-264` dereferences a null static `authToken`, and its timeouts run up to 1200 s.

| Target | Probe | Result |
|---|---|---|
| `db` | `DBUtil.getDataSource()` → `select 1`, reporting the connection type | `fail` on error. A db fail makes the overall status `fail`. |
| OLLAMA | `GET {url}/api/tags` | `ok` when there is a `models[]` list. `warn` when a referencing chatConfig's `model` is missing (`VlmQaProvider.hasModel` / `stripLatest` rule). |
| OPENAI_COMPAT (LiteLLM) | `GET {url}/v1/models` with `Authorization: Bearer` | `ok` on 200 with `data[]`. 401/403 is `fail`, reason `auth`. |
| OPENAI (Azure) | `GET {url}/openai/models?api-version=<cfg.apiVersion or default>` with an `api-key` header | `ok` on 200. There is no Azure route in the test stack, so this row is untested until there is one. |
| EMULATOR | no network | `skip`, "in-process emulator" |
| UNKNOWN | `ChatUtil.resolveServiceType(conn, cfg)` (made public; the record passed in must have `dialect`/`upstream` projected). If the type stays UNKNOWN, a plain `GET serverUrl`. | Status < 500 means reachable. |
| sd / face / voice.tts / voice.stt | `GET` on the resolved URL | reachable → `ok`; unreachable → **`warn`**, because these are optional |
| tag | `new ImageTagUtil(url).isHealthy()` | `ok` when the model is loaded, otherwise `warn` |
| embedding (probe) | By the live `EmbeddingUtil.getServiceType()`: LOCAL → `/heartbeat`; OPENAI_COMPAT → `/v1/models`; OPENAI → reachability only, with "deep test required" | Unreachable is **`fail`**, because embeddings are not optional. |
| `embedding.config` | `EmbeddingUtil.describeTypeUrlMismatch(type, url)`, moved from `RestServiceEventListener.warnOnEmbeddingTypeUrlMismatch` (`:173-216`). The listener keeps logging its own URL-bearing text; the util returns a **reason code** | `warn` when there is a mismatch |
| `vectors` | Row counts per vector table. The table list is **derived** from every registered model that inherits `common.vectorExt`, through the instance method `DBUtil.getTableName` (`:341`); it is never a hard-coded list | Always `ok` (informational): "changing the embedding provider invalidates N stored vectors". These are deployment-wide counts across all organizations, taken by raw SQL. **Sanctioned exception, `/System` administrators only:** the row is removed at serve time for every other caller, exactly like the URLs (D5, N1). |

### 3.1 `detail` never carries a URL, host, response body or generated text (B2)

- Every check reports a **reason code** (`ok`, `unreachable`, `timeout`, `tls`, `auth`, `http_<code>`,
  `model_missing`, `type_url_mismatch`, `emulator`, `not_configured`, `deep_required`, `width_mismatch`,
  `empty_response`, `in_progress`) plus fixed text chosen from that code.
- Exception messages are never copied into `detail`. `java.net.http` exceptions name the host and port.
- Each check carries the URL only in `target.url`. Redaction is applied **per caller, at serve time**,
  because the deployment report is shared (§6).
- Tests assert there is no `://` anywhere in the summary, and none in the full report served to a
  non-`/System` admin.

## 4. Deep tests (30 s)

### 4.1 Deep chat: must not write (B1)

`Chat.newMessage` → `pruneCount` (`Chat.java:4363`) runs the keyframe, memory and interaction trigger
checks. Each check writes with `AccessPoint.update(user, chatConfig.copyRecord({id, ownerId, groupId,
<counter>}))`. `checkMemoryExtractionTrigger` (`:3733`) is guarded only by `!chatMode`. Its stale-marker
reset (`:3750-3756`) fires whenever `lastMemoryExtractionAt > msgSize`, and that would reset a real
config's counter to 0.

The deep-chat config is therefore an **identity-free, in-memory** record:
- Copy only the transport fields of the referencing chatConfig (`model`, `analyzeModel`, `serviceType`,
  `apiVersion`, `chatOptions`, `connection`), or use a fresh `RecordFactory.newInstance(...)` with
  `connection` and the first probed model. With no model, the result is `skip`.
- No `id`, `objectId` or `urn`.
- `lastKeyframeAt = lastMemoryExtractionAt = lastInteractionAt = 0`.
- `keyframeEvery = 0`, `memoryExtractionEvery = 0`, `interactionEvery = 0`, `remindEvery = 0`.
- `extractMemories = false`, `extractInteractions = false`, `stream = false`.
- **No** `systemCharacter` or `userCharacter`. Otherwise memory retrieval (`:5463-5486`) runs searches
  and embedding calls through the global embedding singleton.
- A small output cap through the field returned by `ChatUtil.getMaxTokenField(cfg, svc)`.

The call sequence is `new Chat(user, cfg, null)` → `setPersistSession(false)` → `setRequestTimeout(30)` →
`newRequest(model)` → `newMessage` → **`chat(req)` only, never `continueChat`**. `continueChat` saves the
session, creates chat events, auto-titles and flushes keyframes and memories.

The result is `ok` when the response is non-null and `Chat.getLastCallError()` is null. That getter is a
ThreadLocal, so it must be **read inside the worker task**, on the thread that ran `chat()`. The detail is
the model, the finish reason and the latency; generated content is never returned.

JUnit pins this: a chatConfig whose `last*At` counters are non-zero must be byte-for-byte unchanged on a
fresh `setCache(false)` re-read after a deep chat.

### 4.2 Deep embedding: own executor, one at a time (B3)

- A **scratch** `new EmbeddingUtil(type, url, token)` takes its type, model and dimensions from the live
  instance and its URL/token from `ServerConfigUtil`. No setter is ever called on the live instance.
- It calls `getEmbedding("system check")` and compares the width with the live
  `getObservedEmbeddingWidth()` when that is > 0, otherwise with `resolveExpectedDimensions()`. Null,
  empty or mismatched is `fail`.
- It runs on a **dedicated single-thread executor**. A second concurrent deep run is refused with
  **409**, reason `in_progress`. The embedding POST goes through the shared Jersey client, whose read
  timeout can reach 1200 s, so a hung server ties up only that one thread. The JSON states
  `"bounded": false` for this check.
- Deep chat runs on its **own** single-thread executor, with the same rule of one at a time and 409
  while busy. A hung embedding server therefore never blocks deep chat. Running one deep chat at a time
  also respects LiteLLM's local parallelism of 1.

## 5. Listing readable connections (B4)

1. `AccessPoint.list(user, q)` on `system.connection`, with a numeric `organizationId` from the caller, a
   projection of `id, objectId, name, groupId, groupPath, dialect, upstream, serverUrl` (**no `apiKey`**),
   `setCache(false)`, paged. `authorizeQuery` runs and is audited.
2. **Then** a per-record `getAuthorizationUtil().canRead(user, user, rec)` filter. `list` is not a
   per-record boundary (model-api.md).
3. The same two steps for `olio.llm.chatConfig` (`id, name, groupId, model, apiVersion, connection`)
   build `referencedBy`. A referenced connection missing from step 1 is fetched with `AccessPoint.find`
   by id. If it is unreadable, it goes into `undetermined` and the report gets `complete:false`.
4. `hasApiKey` comes from an in-process `AccessPoint.find` that projects `apiKey`; the value is reduced to
   a boolean at once and never kept on a map.
5. `IOSystem.getSearch()` is never called directly on a path whose results reach the caller. There are
   two PBAC bypasses, and both are sanctioned. One is the existing, internal `ServerConfigUtil.load` for
   the six servers; no record from it leaves the util. The other is the `vectors` row's raw-SQL counts
   (§3), which are served **only** to `/System` administrators and removed at serve time for everyone
   else.
6. A `/System` admin's list also contains the six deployment records. These are deduplicated against the
   deployment section.

## 6. Cache, invalidation, propagation (B5)

| Part | Shared? | TTL | Invalidation |
|---|---|---|---|
| Deployment section | One `volatile` report shared by all users. It is safe to share because it is not shaped by any user's view. URL redaction happens per caller at serve time. | 60 s, single-flight, so `/summary` cannot fan out probes | `SystemCheckUtil.invalidate()` from `ServerConfigUtil.putConnection`; a forced run |
| Per-user connection results | `ConcurrentHashMap<userId, entry>`, LRU cap 64, served only to the same user | 60 s | A per-user **epoch** captured before computing; the result is refused for the cache if the epoch moved (the `PbStaleUtil.put(scan, epoch)` pattern). `complete` / `undetermined` are served exactly as computed. |

**Propagation, stated in the report and the panel:** a `system.connection` or chatConfig edit made through
generic `PATCH /rest/model`, Console7, or another Service7 JVM invalidates nothing here. It shows up after
at most **30 s** (`ServerConfigUtil` TTL) **+ 60 s** (this cache), or on the next forced **Run**. Each check
says whether it probed the stored record's URL (`source:"record"`) or the live singleton's
(`source:"live"`); the two can differ for up to one `ServerConfigUtil` TTL.

Probes run on a fixed 8-thread daemon pool with `future.get(timeout + 1 s)`. `shutdown()` is called from
`RestServiceEventListener.shutdown()`.

## 7. REST (`SystemCheckService`, `@Path("/system/check")`)

| Method | Path | Role | Returns |
|---|---|---|---|
| GET | `/summary` | user, admin | `{status, degraded[], checkedAt}`. Names come only from `{db, embedding, sd, face, tag, voice.tts, voice.stt}`. No URLs, hosts or tokens. |
| GET | `` | admin | The last report for the caller |
| POST | `?deep=` | admin | A forced full run |
| GET | `/connections` | admin | Readable connections, without probing |
| POST | `/connection/{objectId}?deep=` | admin | Test one connection; **404** when it is not readable |
| POST | `/server/{name}?deep=` | admin | Test one deployment check; **400** for a name outside the whitelist |

- Every admin method calls `AdminMaintenanceUtil.requireAdministrator(user)` as a second gate behind
  `@RolesAllowed`.
- **Feature gate (B6):** the server does **not** gate on `serviceStatus`. The feature id is a UI-only
  gate; authorization is the admin role. A future server gate goes after `requireAdministrator` and after
  the record is resolved as readable.
- A deep run while another is in progress returns **409**.

## 8. Ux

- `features/serviceStatus/{routes,serviceStatusClient,serviceStatusView}.js`, following
  `features/systemAdmin/`.
- Header: overall status chip, last checked, a Deep checkbox, a Run button, the propagation note, and an
  incomplete-view banner.
- Table 1, **Deployment services**: db, embedding, embedding.config, vectors, sd, face, tag, voice.tts,
  voice.stt.
- Table 2, **Connections**: name, group, dialect, upstream, API key set, used by, status, detail,
  latency, and Test / Deep actions.
- Status values are compared case-insensitively. A 403 shows "Administrator role required"; a 409 shows
  "A deep test is already running".
- **Banner:** at app start, next to the `am7client.application()` await in `router.js`, fetch
  `/system/check/summary`. If anything is degraded, show a dismissible top banner naming the degraded
  services and saying "Ask an administrator to open Service Status". Refetch on window focus at most
  once per 60 s; never on every route. The fetch **fails silently** on 401, 403 and 5xx and never goes
  through `forceLogin`. The wording names no app, so it stays correct in orgs without `serviceStatus`.
  A cold `/summary` from a plain user does run the probes. That is acceptable: the run is single-flight
  with a 60 s TTL, does no deep test, and persists nothing.
- `features.js` wiring: `{icon:'dns', label:'Service Status', route:'/admin/status', section:'aside',
  adminOnly:true, roles:['admin']}`, `routePrefixes:['/admin/status']`. Added to the `enterprise` and
  `compliance` profiles.
- Manifest entry: added to `AccountManagerObjects7/src/main/resources/features/uxFeatureManifest.json`,
  then **byte-copied** to `AccountManagerUx752/src/features.manifest.json`.

## 9. Tests

- **JUnit `TestSystemCheckUtil`** (Objects7, live; quote the `[LLM-GATE]` line):
  - db `ok`; OLLAMA `ok` with models; LiteLLM `ok`, and a wrong key gives `auth`.
  - A closed port gives `fail`, and a black-hole address times out in under 7 s. Eight parallel
    black-hole probes finish in under 8 s.
  - EMULATOR gives `skip`.
  - Deep embedding is `ok` at 768, a mismatched type gives `fail`, and no setter is called on the live
    instance.
  - Deep chat is `ok`, and the chatConfig with non-zero `last*At` counters is unchanged (B1).
  - In a two-user org, user B's private connection is absent from user A's list.
  - A sentinel `apiKey` and any `://` are absent from the summary and from the full report served to a
    non-`/System` admin. That report also has **no `vectors` row** (N1).
  - A plain user is refused; a concurrent deep run gives 409; record counts are unchanged.
- **JUnit `TestSystemCheckService`** (Service7, `HttpServletRequestMock`): the 200/400/403/404/409 status
  mapping, a names-only summary, `urlRedacted` for a non-`/System` admin.
- **Vitest:** the client request shapes, the pure helpers, routes, menu visibility, the banner, and the
  manifest byte-identity.
- **Playwright** (`--workers=1`):
  - `ensureAdminRoleTestUser()` (not the `admin` account) and `ensureSharedTestUser()`.
  - `setOrgFeatures` enables `serviceStatus`, and is restored in `finally`.
  - The shared user gets a 200 summary and 403 elsewhere.
  - The admin-role user runs a check, tests a reachable and an unreachable connection, and the sentinel
    apiKey is absent everywhere.
  - Deep tests sit behind `SERVICE_STATUS_DEEP=1`.
