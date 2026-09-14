# LiteLLM / Langfuse Integration — Design & Plan

**Date:** 2026-09-01 (design) · **updated 2026-09-14** (as-built)
**Status:** **implemented** — B1/B2/B3/B5 **done**, B4 **partial**. Design ratified by architecture
review (APPROVED with guardrails) on 2026-09-01; built, run and measured through 2026-09-14.
**Read §6 "As-built (2026-09-14)" first** for what actually shipped, what was measured, and the open
follow-ups. §§1–5 are the original design and are retained; where they disagreed with the build they
have been corrected in place.

**Scope:** Add optional support for **LiteLLM** (an OpenAI-compatible LLM proxy/gateway) as a chat
connection dialect, and **Langfuse** (LLM observability / metrics / tracing) as an optional metric
source. Touches `AccountManagerObjects7` (LLM layer) and the Docker compose stack; an optional future
tie-in surfaces Langfuse metrics inside `AccountManagerISO42001`.

> Behavioral rules: `../.claude/rules/llm-conduct.md`. Layering: `../.claude/rules/architecture.md`.
> Cross-layer model/PATCH rules: `../.claude/rules/model-api.md`.

---

## 1. LLM connection design as it stood on 2026-09-01 (pre-change baseline)

> **Superseded in two places by the build.** `system.connection` now **does** carry a type field —
> `dialect` (`ConnectionDialectEnumType`), B2 — and `getServiceUrl()` now has an `OPENAI_COMPAT`
> branch (B1, `Chat.java:4443`). The rest of this section still describes the current code. Read §6
> for the built state.

Established by source review 2026-09-01. The connection handling is **less custom than it looks** —
it already has an OpenAI code path; that path is just wired to Azure's URL scheme, not generic OpenAI.

- **Connection record = `system.connection`** (`AccountManagerObjects7/src/main/resources/models/system/connectionModel.json`).
  Inherits `data.directory, common.description, crypto.vaultExt`. Fields: `serverUrl`
  (default `http://192.168.1.42:11434`), `apiKey` (vault-encrypted via `EncryptFieldProvider`),
  `requestTimeout`. **It carries no provider/type field.**
- **Provider type = `LLMServiceEnumType { UNKNOWN, LOCAL, OLLAMA, OPENAI }`**
  (`olio/llm/LLMServiceEnumType.java`), carried on **`olio.llm.chatConfig`** (field `serviceType`,
  default `OPENAI`), which also holds `model`, `apiVersion`, and a foreign `connection` FK.
- **Resolution:** `Chat.configureChat()` (`Chat.java:366-434`) loads the `connection` FK with an
  explicit projection (`serverUrl, requestTimeout, apiKey` — `apiKey` **must** be requested or
  `EncryptFieldProvider` never decrypts), then reads `apiVersion/model/serviceType` off `chatConfig`.
- **URL assembly — `Chat.getServiceUrl()` (`Chat.java:~4290-4300`):**
  - `OLLAMA` → `serverUrl + "/api/chat"` (native Ollama API).
  - `OPENAI` → `serverUrl + "/openai/deployments/" + model + "/chat/completions?api-version=" + apiVersion`
    — **this is Azure OpenAI's deployment scheme, NOT standard `/v1/chat/completions`.**
- **HTTP:** `ClientUtil.postToRecordAndStream(url, token, json)` (`ClientUtil.java:294-320`) sets a
  **fixed** header set including `Authorization: Bearer <token>`. No hook for custom headers.
- **Response parse — `Chat.processStreamChunk()` (`Chat.java:4148-4249`):** `OPENAI` expects SSE
  `data:` framing + `choices[].delta`; `OLLAMA` expects top-level `message` + `done`. Both deserialize
  into `MODEL_OPENAI_RESPONSE`.
- **The six media/AI URLs** (`sd, face, tag, voice.tts, voice.stt, embedding`) are a **different** set
  of deployment-global `system.connection` records resolved by `ServerConfigUtil` into process-global
  bound utils. **None is the chat path** — chat always uses `chatConfig.connection`. `embedding` is the
  only LLM-adjacent one and is bound into the single process-global `VectorUtil`.

**Bottom line:** pointing the existing `OPENAI` type at a LiteLLM base URL **404s** (LiteLLM serves
`/v1/chat/completions`, not Azure's `/openai/deployments/...`). What already fits LiteLLM unchanged:
Bearer auth, the OpenAI request body model, and the `choices/delta` SSE parser.

---

## 2. Design (architecture-review APPROVED, 2026-09-01)

### 2.1 Chat via LiteLLM — small
Add a new dialect and a URL branch; reuse everything else.

- New `LLMServiceEnumType` value **`OPENAI_COMPAT`** (a.k.a. LiteLLM) →
  `getServiceUrl()` returns `serverUrl + "/v1/chat/completions"`.
- Reuse the existing OpenAI request body model, the `choices/delta` SSE parser, and Bearer auth.
- All of this stays in the **Objects7 LLM layer** (`Chat.java` / `ChatUtil`) — legal (LLM lives in
  Objects7); no Service7 or ISO involvement.

### 2.2 Dialect belongs on the connection, not chatConfig
Endpoint protocol is a property of the **endpoint**, so add the dialect field to **`system.connection`**
(it travels as one record with `serverUrl`/`apiKey`). Keep `chatConfig.serviceType` for back-compat, or
derive it from the connection.

**Model-change guardrails (enforce all — from architecture review):**
1. **Do NOT name the field `provider`** — `provider` is a reserved field-schema keyword and will
   collide. Use **`dialect`** as a proper `ConnectionDialectEnumType` under `schema/type`, serialized
   lowercase, read via `getEnum()`.
2. **Walk the `inherits` chain first** (`data.directory`, `common.description`, `crypto.vaultExt`) and
   confirm `dialect` isn't already declared upstream — a duplicate field/constraint causes
   `DBUtil Index collision` / `Column does not exist` DDL errors on **every** boot.
3. **Safe default** (`UNKNOWN` or `OLLAMA`) so existing `system.connection` rows stay valid when the
   column is added.
4. **PATCH validation:** `system.connection` inherits `common.nameId` (via `data.directory`), so any
   PATCH editing `dialect` must include `name` + identity or it fails validation **silently** and
   returns a discarded result. The UI edit path must send `name` + identity.

### 2.3 Langfuse — two tiers

- **Tier A (default, recommended): observability *inside* the LiteLLM proxy.** Enable Langfuse
  callbacks in LiteLLM config; **zero AM7 code change**. AM7 just points `chatConfig.connection` at the
  LiteLLM base URL. This is the recommended first step and the primary "metric source" path.
- **Tier B (optional, native AM7 tracing): additive, subtler than "just headers".**
  - Add optional per-request `metadata`/`user`/`session_id` to the OpenAI request model, **and**
  - a per-request custom-header injection hook (`x-langfuse-*`) in `ClientUtil.postToRecordAndStream`.
  - **Guardrail 1 (process-global rule):** the header hook must be a **method parameter threaded per
    call**, never a field on a shared/static `ClientUtil`; url + headers move as **one immutable
    argument** (no torn pairs).
  - **Guardrail 2 (body leakage):** the OpenAI request body is a `BaseRecord` serialized via
    `toFullString()`, so newly-added `metadata/user/session_id` would emit to **Ollama and Azure**
    bodies too. These fields must be **gated to the `OPENAI_COMPAT` dialect** (conditional
    serialization or a dialect-specific request build) — not merely added to the model.
  - **Guardrail 3 (PII):** the `user` value flows to Langfuse as the trace **user id** (body field
    for OPENAI_COMPAT, and the `x-langfuse-user-id` header). It **MUST be an opaque identifier** — an
    `objectId`/URN or a generated nonce — and **NEVER a username, email, or any human-identifying
    string**, or Langfuse (a third-party observability store) accumulates PII. Likewise `session_id`
    is a correlation key (in practice the run `objectId`), not domain data. As shipped, the only
    production writer is the ISO engine (`TestExecutor.applySession` sets `session_id` = run
    `objectId`; it never sets `user`); nothing passes a human identifier. The constraint is also
    recorded on the model field (`openaiRequestModel.json` `user`).

### 2.4 Embeddings via LiteLLM — OUT OF SCOPE (phase 1)
Bound by the **boot-pinned `embedding.dimensions`** (the vector column is one fixed width; stored
vectors carry no provenance). `EmbeddingUtil` lives in the single process-global `VectorUtil`. Keep
embeddings as-is; only revisit if a LiteLLM-backed embedding model produces the identical dimension.

### 2.5 Docker — optional sidecars behind compose profiles
The **test stack is the clean template** (`am7-pg` on `am7-test-net` with service-name DNS). LiteLLM
and Langfuse ship as **optional** services behind the compose profile **`llmproxy`** in
`docker-compose.test.yml`; the app reaches them by service name (`http://litellm:4000`). Nothing in
that profile starts with the default stack.

**Correction (measured 2026-09-14): Docker on this host CAN reach the LAN.** An earlier version of
this section asserted that Docker Desktop bridges to `172.20.x.x` and cannot route to `192.168.1.x`,
and concluded that an in-compose LiteLLM could only be useful if it ran the model itself, forwarded
to a cloud endpoint, or went out via `host.docker.internal`. That is false. A container attached to
`am7test_am7-test-net` reached `http://192.168.1.42:11434/api/tags` → **HTTP 200 in 34ms**, and from
inside the running `litellm` container specifically → **200 in 24ms**. `.claude/rules/troubleshooting.md`
corrected this claim on 2026-09-13; these aiDocs lagged. So proxying the **LAN Ollama** through
LiteLLM works — and one queue in front of one physical GPU is the main reason the proxy exists (§6.3).

As shipped, Langfuse is **not** "its own web service + Postgres": v4 is a six-service deployment
(§6.1).

### 2.6 Langfuse metrics into ISO 42001 reports — SHIPPED (was "optional future")
Surface Langfuse cost/latency/token metrics into ISO reports. This logic lives in the
**`AccountManagerISO42001`** module (depends on Objects7). **Never** let Langfuse token/cost parsing
leak into Objects7 or Service7. This is the natural bridge between "metric source" and the ISO
reporting half of the original request.

**Status: SHIPPED (B5, 2026-09-14).** `LangfuseMetricsClient` / `LangfuseMetrics` live in
`AccountManagerISO42001` (`iso42001/metrics/`) and are wired through `TestExecutor` / `TestRunner`.
The layering constraint above held.

---

## 3. Phased plan

| Phase | Work | Status (verified 2026-09-14) | Module(s) |
|---|---|---|---|
| B1 | `OPENAI_COMPAT` dialect enum + `getServiceUrl()` branch (`/v1/chat/completions`); reuse body/parser/auth | **DONE** — `ConnectionDialectEnumType{UNKNOWN,OLLAMA,OPENAI,OPENAI_COMPAT}`; URL branch at `Chat.java:4443`; `Chat.java:4432` (`isOpenAiCompatible()`) shares SSE framing with `OPENAI` | Objects7 |
| B2 | `dialect` (`ConnectionDialectEnumType`) field on `system.connection` + safe default; resolver | **DONE** — `dialect` on `system.connection` (`maxLength:16`, default `UNKNOWN`), authoritative per §5.1 P3-1; `ChatUtil.resolveServiceType(connection, chatConfig)` at the `Chat.configureChat()` convergence point. `chatConfig.serviceType` remains the deprecated derived fallback and is **NOT** retired (still the only carrier of `LOCAL`) | Objects7 (+ Ux752 edit form) |
| B3 | Optional LiteLLM + Langfuse compose services behind profiles | **DONE — profile first actually run 2026-09-14** (it had never been run before; the first-run defects it exposed are in §6.8) | Docker |
| B4 | *(optional)* Tier B native tracing: gated request metadata + per-call header hook | **PARTIAL** — `Chat.java:4007,4068` emits body `session_id` + `x-langfuse-*` headers, gated to `OPENAI_COMPAT` only | Objects7 |
| B5 | *(optional, future)* Langfuse metrics → ISO 42001 reports | **DONE** — ISO42001 `LangfuseMetricsClient` / `LangfuseMetrics` wired via `TestExecutor` / `TestRunner` | ISO42001 |

**Sequence (as originally recommended):** B1 → B2 → B3, then decide on B4/B5. As of 2026-09-14 B1,
B2, B3 and B5 are done and B4 is partial. The open code items are the tracked Objects7 follow-up in
§6.6 / **KI-72** (Ollama-extension gating) and the still-deferred retirement of `chatConfig.serviceType` (§5.1).

## 4. Verification standard
- Objects7 change: `mvn -o -pl AccountManagerObjects7 install -DskipTests` then compile dependents;
  add a JUnit test that exercises the `OPENAI_COMPAT` URL assembly and a live round-trip against a
  reachable OpenAI-compatible endpoint. `-DskipTests=false` is mandatory for Objects7. Docker **can**
  reach the LAN (§2.5), so the compose LiteLLM is a valid target for that round-trip.
- No schema resets. Adding `dialect` is an additive column with a safe default.

## 5. Open decisions — all three now resolved (kept for provenance)
1. **Langfuse depth:** Tier A only (proxy-side, zero AM7 code) or also Tier B (native tracing)?
   Recommendation: **Tier A first.**
   **Resolved as built (2026-09-14):** Tier A shipped; Tier B is **partial** — body `session_id` +
   `x-langfuse-*` headers, gated to `OPENAI_COMPAT` (`Chat.java:4007,4068`). No further Tier B work
   is scheduled.
2. **`serviceType` fate:** **Decided (2026-09-03) — see §5.1:** derive from `connection.dialect` and
   deprecate `chatConfig.serviceType`; interim resolver now, full removal deferred.
3. **B5 in scope?** Whether Langfuse-metrics-into-ISO-reports is part of this initiative or a later one.
   **Resolved: yes — B5 shipped 2026-09-14** (§2.6, §3).

### 5.1 Decision (2026-09-03): converge on `dialect`, deprecate `chatConfig.serviceType`

Resolves open decision #2. Today both `dialect` (`ConnectionDialectEnumType` on `system.connection`:
`UNKNOWN`/`OLLAMA`/`OPENAI`/`OPENAI_COMPAT`) and the deprecated `chatConfig.serviceType`
(`LLMServiceEnumType`) encode the same thing — the transport protocol. **`dialect` is the
authoritative-going-forward field** (Phase 1/2); `serviceType` is annotated deprecated on the server
model.

- **Convergence target:** `Chat.configureChat()` should prefer `connection.dialect` when set
  (non-`UNKNOWN`) and **derive `serviceType` from it**, falling back to `chatConfig.serviceType` only
  when `dialect` is `UNKNOWN`. This interim resolver + a deprecation timeline is the plan.
- **Not this pass:** full removal of `serviceType` touches **every `chatConfig` consumer** and is
  deferred. Do not retire `serviceType` yet — land the resolver first.
- **Operational prerequisite — RESOLVED 2026-09-14, see §6.7** (found live this phase; NOT a code
  task): the live `am7db` column
  `A7_olio_llm_chatConfig_0_1."serviceType"` is a stale `varchar(10)` that **cannot hold
  `"OPENAI_COMPAT"` (13 chars)**. The model is correctly `maxLength:16`, but the column predates the
  bump and `BaseTest` runs with `repairColumnTypes=false`. A **non-destructive widen** (`ALTER … TYPE
  varchar(16)`, or a `repairColumnTypes` pass) is required before `OPENAI_COMPAT` chatConfigs can
  persist on that DB. Flag as an operational prerequisite for B1/B2, not code work.
- **DONE (P3-1, 2026-09-03):** interim resolver landed — `ChatUtil.resolveServiceType(connection, chatConfig)` is called once at the `Chat.configureChat()` convergence point (connection projected with `dialect`), and the three `ChatUtil` model-nuance methods (`getMaxTokenField`/`supportsSamplingParams`/`applyChatOptions`) grew a two-arg overload threaded with the resolved value; `serviceType` is now the derived fallback (and the only carrier of `LOCAL`). **TRACKED FOLLOW-UP:** full removal of `chatConfig.serviceType` (retire the field + single-arg fallback overloads once every `chatConfig` consumer reads `dialect`) remains deferred per the "Not this pass" note above.

---

## 6. As-built (2026-09-14)

Everything below was **measured, or read from installed source in the image** — not inferred from
vendor documentation. Dates are inline. Where this section disagrees with §§1–5, this section wins.

### 6.1 The stack as shipped

In `src/docker-compose.test.yml`, behind compose profile **`llmproxy`**. Nothing in the profile
starts with the default stack, and the `am7` service deliberately has **no `depends_on`** on it — so
a `chatConfig` pointing at `http://litellm:4000` **fails closed** when the profile is down.

- **`litellm`** — pinned **by digest** to litellm **1.102.0** (bundling langfuse Python SDK 2.59.7).
  Deliberately NOT the `main-stable` channel (1.100.1): the concurrency cap is inert for streaming
  there, and AM7 always streams. Full rationale and measurements in §6.3.
- **Langfuse v4** (latest stable) — a **six-service** deployment, upgraded this pass from the v2
  two-service form at Stephen's request: `langfuse-web`, `langfuse-worker`, `langfuse-clickhouse`,
  `langfuse-minio`, `langfuse-redis`, `langfuse-db`. Traces live in **Clickhouse**; `langfuse-db`
  (Postgres) holds only metadata — orgs, projects, API keys. **The worker is not optional:** without
  it the API accepts events and the UI shows nothing.
- v4 requires **`ENCRYPTION_KEY` to be exactly 64 hex characters** or the server refuses to boot.
- **A stock `langfuse:4` REJECTS the v2 SDK, and this bit us.** litellm's bundled SDK v2 posts to the
  legacy `/api/public/ingestion` endpoint; v4 defaults to **`events_only` mode** and refuses it:
  `"Rejected N event(s) from the legacy /api/public/ingestion endpoint ... because this Langfuse v4
  deployment runs in events_only mode"`, while `GET /api/public/traces` (what every test polls)
  answers `"This endpoint is not available on deployments running in Langfuse v4 events_only mode."`
  Both strings are present in the image and `areLegacyWritesActive()` gates on the write mode.
  **The failure is silent from LiteLLM's side** — chats keep returning 200 and the SDK logs only a
  generic `API errors occurred: Bad request`.
  ⇒ `LANGFUSE_MIGRATION_V4_WRITE_MODE: dual` on the shared `&langfuse-env` anchor is **required** for
  as long as litellm ships SDK v2.

  > **Correction.** An earlier revision of this section and of `docker-compose.test.yml` asserted the
  > opposite — that v4 "keeps the v2 endpoint, verified 2026-09-14". That was wrong. The traces
  > observed when that claim was written were landing only because the `dual` bridge had already been
  > switched on during test authoring; the claim was never isolated. Found while making
  > `TestLiteLLMRoundTrip` runnable.

- **Version coupling to re-check on every image bump:** if a future litellm image ships SDK v3+,
  switch `success_callback` from `langfuse` to `langfuse_otel` and drop
  `LANGFUSE_MIGRATION_V4_WRITE_MODE`.

**Operational runbook — this doc is the *why*, that one is the *how*:**
[`dockerDevSetup.md`](dockerDevSetup.md) **§12**. Specifically:

| Question | Runbook section |
|---|---|
| What are these seven containers, and do I need them all? (plus the eighth DB, `litellmdb`) | §12.1 |
| Start / stop it; why `down -v` is needed to clear traces | §12.2 |
| Is it actually up? | §12.3 |
| **Configure AM7 to use it** — `system.connection` (`dialect`, `serverUrl`, `apiKey`, `requestTimeout`) + `olio.llm.chatConfig` (`connection`, `model`) | §12.4 |
| **Read the metrics** — LiteLLM admin UI and Langfuse UI, including the login that used to 500 | §12.5 |
| Traces not appearing; other first-run gotchas | §12.6 |

### 6.2 LAN reachability — the old claim is dead

A container on `am7test_am7-test-net` reached `http://192.168.1.42:11434/api/tags` → **HTTP 200 in
34ms**; from inside the running `litellm` container specifically → **200 in 24ms** (2026-09-14).
`.claude/rules/troubleshooting.md` corrected the "Docker cannot reach the LAN" claim on 2026-09-13
and the aiDocs lagged; it has now been struck from §2.5, §4, and `DockerComposeDesign.md`.

### 6.3 Concurrency cap — the point of the exercise

`litellm_params.max_parallel_requests` on the **model entry** is the ONLY knob that queues. Read
against the installed source in the pinned image (litellm **1.102.0**):

- `client_initalization_utils.py` (`set_max_parallel_requests_client`) — it becomes an
  `asyncio.Semaphore(N)`, held per deployment.
- `router.py` (async completion path) — the semaphore is entered on an `AsyncExitStack` before the
  upstream call, so request N+1 **awaits** a free slot; it does not fail.

By contrast `general_settings` / per-key `max_parallel_requests` routes through
`litellm/proxy/hooks/parallel_request_limiter.py`, which raises **HTTP 429** — it *rejects* instead of
queueing. That is the wrong outcome here: AM7 treats a non-200 as a failed call and retries, which is
the retry storm this work exists to stop.

#### The streaming trap — and why the image is digest-pinned to 1.102.0

**Whether the slot survives a streaming response is version-dependent, and AM7 always streams.**
`Chat.java:4034` sets `wireReq.setStream(true)` **unconditionally** — the `stream` flag on the caller's
request only controls whether tokens are forwarded to the client, never what goes on the wire. So the
streaming path is not an edge case here; it is *all* of AM7's chat traffic.

- **1.102.0** holds the slot in an `AsyncExitStack` for the life of the stream.
- **1.100.1** (`main-stable`, the newest released tag) releases it when the upstream call returns —
  i.e. at **first byte**. The cap is therefore **completely inert for streaming**.

Measured 2026-09-14, cap=1, K=3 concurrent, identical threaded mock upstream (fast first byte, ~5s to
complete), only the image changed:

| image | mode | completion times | mock's own peak in-flight | verdict |
|---|---|---|---|---|
| 1.100.1 | `stream:false` | 5.69 / 10.74 / 15.79s | **1** | queued |
| 1.100.1 | `stream:true` | 5.05 / 5.08 / 5.08s | **3** | **INERT** |
| 1.102.0 | `stream:true` | 5.90 / 10.92 / 15.93s | **1** | queued |

No `*-stable` tag above 1.100.1 exists, so the fix is only reachable on the rolling channel. The image
is therefore pinned **by digest** to 1.102.0 rather than tracking `main-latest`, so it cannot drift
silently. It bundles langfuse SDK 2.59.7, identical to stable, so tracing is unaffected by the choice.

> **This corrects an earlier revision of this section**, which cited
> `router.py:2746-2756` for "the slot is held until the stream is exhausted" while the compose file was
> pinned to `main-stable`. The behaviour is real in 1.102.0 but **false in 1.100.1**, and the citation
> line numbers were carried over from a different build. Hard line-number citations into litellm have
> been removed for that reason — cite the function, and re-measure on every bump.

#### End-to-end on the shipped stack

Streaming, K=3, cap=1, real `qwen3:8b` on the LAN DGX, through `litellm:4000`:

```
 req | start | first |   end | http
   2 |  0.00 | 10.01 | 14.16 | 200
   1 |  0.00 | 14.36 | 18.77 | 200
   0 |  0.00 | 18.94 | 23.35 | 200
```

First-byte times staggered ~4.4s apart is the signal: uncapped, all three receive first byte almost
immediately (0.07 / 0.07 / 0.08s on 1.100.1). All 200, no 429s — queued, not rejected.

**Honest limit on the real-hardware runs.** The same K=3 issued **directly** to the DGX also staggers
(first byte 3.30 / 7.75 / 12.25s), because Ollama serialises this model on its own. So a real-model run
is *consistent with* the cap but cannot **isolate** it; the mock table above is the isolating
experiment. What the proxy adds over Ollama's own behaviour is that the limit is enforced at a single
point, independently of the upstream's configuration, across every AM7 caller and process — plus the
Langfuse trace of each call.

### 6.4 The value is config-driven, but NOT via `os.environ/`

litellm resolves `os.environ/X` through `get_secret()`, which returns a **string**; a numeric setting
then fails at **request** time (not startup) with
`'<' not supported between instances of 'str' and 'int'`.

So the compose entrypoint `sed`-renders `__LOCAL_MAX_PARALLEL__` from `$LITELLM_LOCAL_MAX_PARALLEL`
before litellm parses the YAML — the same render-a-template pattern `docker/entrypoint.sh` uses for
`web.xml`. **Changing it requires a container RECREATE, not a `restart`** (`docker-compose restart`
does not re-read the compose file).

### 6.5 Timeout ladder — and the finding that sets its direction

Layers, outermost first:

| Layer | Value | Note |
|---|---|---|
| nginx `proxy_read_timeout` | 3600s | browser→Tomcat only — **not** on the Tomcat→LiteLLM hop |
| AM7 per-call latch | `connection.requestTimeout + 5` — 305s live | |
| AM7 `orTimeout` | `connection.requestTimeout` — 300s live | |
| LiteLLM per-model `timeout` | **240s** | innermost bound |
| LiteLLM default | 6000s if unset (`litellm/constants.py:512`) | |

Two measurements decide the ordering:

**(1) A client disconnect does NOT release the semaphore slot.** Abandoning a request after 1s —
exactly what AM7 does when its latch expires and `Chat.cancelOutbound()` fires — left the slot held;
the next request was not dispatched upstream until **5.01s** later, the full generation time of the
request whose caller had already gone. **So with N=1, one timed-out AM7 caller blocks the whole queue
for the remainder of its generation, and nothing in AM7 can free it.**

**(2) LiteLLM's own timeout DOES release it, promptly.** With `timeout: 2` against a 5s upstream the
call 408'd at **2.47s** and the queued request was dispatched **2.05s** later — the instant the
timeout fired.

Therefore the LiteLLM per-model `timeout` **must be the innermost bound**, strictly below AM7's
latch. An earlier draft of the config had it **above** the latch, which would have recreated the very
wedge the proxy exists to prevent, one layer further out.

**(3) `timeout` does not cover queue wait.** Its clock starts **after** semaphore acquisition (a
queued request's timeout fired ~2s after *its* dispatch, not after submission). Queue wait is charged
entirely to AM7's latch, so with N=1 and queue depth D the last caller needs roughly **D × timeout**
of AM7 budget. Raise `requestTimeout` on the `system.connection` row pointing at the proxy rather
than lowering the LiteLLM value.

The resulting concrete setting — `requestTimeout: 300` on the connection, above LiteLLM's 240s — and
where to enter it are in `dockerDevSetup.md` §12.4.

### 6.6 Behavioural divergence — proxied is NOT equivalent to direct (TRACKED FOLLOW-UP)

AM7 gates its whole Ollama-extension block on `serviceType == OLLAMA` (`ChatUtil.java:2124-2153`), so
the `OPENAI_COMPAT` path silently drops `num_ctx`, `top_k`, `repeat_penalty`, `typical_p`, `min_p`,
`repeat_last_n`, `num_gpu`, and `think` (`Chat.java:3989`) — a config that set `think:false` gets
thinking back **ON**. qwen3 is named in that very source comment as the affected hybrid reasoning
model, and it is the model being proxied; confirmed live, a `max_tokens:20` request through the proxy
returned pure `reasoning_content` with `finish_reason: length`. Separately `Chat.java:2731` halves
the memory-extraction token floor (16384 → 8192) when `serviceType != OLLAMA`.

`num_ctx` is pinned on the LiteLLM model entry — the only place it can be, since `drop_params: true`
would drop one AM7 sent. The rest needs an **Objects7 fix keying that block on "upstream is Ollama"
rather than "dialect is OLLAMA"**; that is a **tracked follow-up**. Filed as **KI-72** in `aiDocs/KnownIssues.md`.

**Until it lands, no existing `OLLAMA` chatConfig may be repointed at the proxy.** New
`OPENAI_COMPAT` configs are fine. Exposure today is **zero** — all 19 existing `system.connection`
rows are `dialect=UNKNOWN`. This constraint is repeated as a warning at the point of use, in
`dockerDevSetup.md` §12.4.

### 6.7 Operational prerequisite — RESOLVED this pass

The §5.1 blocker is cleared. Host DB `am7db` had `A7_olio_llm_chatConfig_0_1.serviceType` as a stale
`varchar(10)`, too narrow for `"OPENAI_COMPAT"` (13 chars). A non-destructive widen was applied and
verified:

```sql
ALTER TABLE a7_olio_llm_chatconfig_0_1 ALTER COLUMN servicetype TYPE varchar(16);
```

Docker DB `am72db` was already `varchar(16)`. `dialect` is `varchar(16)` on both. **No schema reset
was performed.** psql gotcha: the column name is folded to lowercase in the DB, so a quoted
`"serviceType"` fails — use unquoted `servicetype`.

### 6.8 First-run problems found and fixed (the profile had never been run)

- **An `--env-file` feeds docker-compose variable interpolation ONLY — it puts nothing in the
  container.** `os.environ/OLLAMA_API_BASE` therefore resolved to nothing and litellm silently fell
  back to `localhost:11434` ("Cannot connect to host localhost:11434"). Fixed by declaring the vars
  in the service's `environment:` block.
- **The numeric `os.environ/` type failure** described in §6.4.
- **The pg18 `PGDATA` trap** that the compose file already documented was **verified to work** — the
  bind mount populated correctly on the real first run.

### 6.9 Secrets, committed test defaults, and what reaches Langfuse

`src/volatile/llmproxy.env` (git-ignored via the root `.gitignore` `volatile*` rule) supplies runtime
values through `--env-file`. `src/litellm/config.yaml` is **committed and secret-free** — every
credential is an `os.environ/<VAR>` reference.

**Committed fallback credentials — dev workstation only.** Every `${VAR:-default}` in the `llmproxy`
profile carries a well-known throwaway value so the stack comes up with no manual bootstrap:

| Variable | Committed fallback | What it protects |
|---|---|---|
| `LITELLM_MASTER_KEY` | `sk-am7-litellm-test` | LiteLLM **admin** key — `/key/generate`, spend, model list, and use of the GPU |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | `pk-lf-am7-test` / `sk-lf-am7-test` | read of every captured trace |
| `LANGFUSE_INIT_USER_EMAIL` / `_PASSWORD` | `am7@example.com` / `am7-langfuse-pw-test` | Langfuse UI login |
| `LANGFUSE_ENCRYPTION_KEY` | 64 zeros | Langfuse at-rest encryption |
| clickhouse / minio / redis | `clickhouse`, `minio`/`miniosecret`, `langfuse` | the backing stores |

**These are not gated on anything.** Omit `--env-file`, mistype the flag, or run the profile from a
script that forgets it, and compose starts cleanly using exactly these values — there is no warning.
Two mitigations are in place and must stay: the published ports are bound to **`127.0.0.1` only**
(`litellm` 4000, `langfuse-web` 3001; `langfuse-minio` publishes nothing), and the profile is opt-in
and absent from the production `docker-compose.yml`. **Never run this profile on a shared or
LAN-reachable host without overriding every value above.**

**Guardrail 4 (content): traces contain FULL prompt and completion bodies.** Guardrail 3 (§2.3)
constrains only the trace *identifiers*. It does not cover the message bodies, and LiteLLM's Langfuse
callback ships the entire `input` (system prompt plus every turn) and `output` — verified by reading
traces back off `/api/public/traces`. On AM7 paths those bodies carry user-authored conversation
content, character and narrative material, and on ChapBook/PictureBook, text extracted from
user-uploaded documents.

Today that is a local-disk concern: Langfuse is a container on this host and the UI is loopback-bound.
It stops being local the moment `LANGFUSE_HOST` points at hosted Langfuse or any shared instance —
**that is an explicit data-export decision, not a config tweak.** If metrics are wanted without the
bodies, LiteLLM's `turn_off_message_logging: true` suppresses prompt/completion content while
preserving token counts and latency, which is all the ISO 42001 client consumes
(`LangfuseMetricsClient` reads only `promptTokens`/`completionTokens` from `/api/public/observations`).

**Guardrail 3 is honored but not enforced.** `Chat.buildTracingHeaders` (`Chat.java:4461-4475`)
forwards whatever string sits on `req.user` / `req.session_id` without validating it. Compliance today
is real — the only production writer is `TestExecutor.applySession` (`TestExecutor.java:94-103`),
which sets `session_id` to an `iso42001.testRun.objectId`, and nothing sets `user` at all. But both are
persisted fields on `openai.openaiRequest`, and `ChatService.chatHistory` (`ChatService.java:141-144`)
builds the request from the DB-loaded session record, so a client that can write those fields through
the generic `/rest/model` routes controls what reaches Langfuse. Enforcing opaqueness at the single
emission point is a **tracked follow-up** (§6.10), not something to leave to every caller having read
§2.3.

**Cleartext Bearer inside the compose network.** AM7 sends the LiteLLM master key over plain HTTP to
`http://litellm:4000`. This is the same trust domain as the cleartext Postgres connection on the same
bridge network and is acceptable at that scope; it stops being acceptable if `litellm` ever moves off
this host. The key itself is stored in `system.connection.apiKey`, which is vault-encrypted via
`EncryptFieldProvider` exactly like every other connection secret, and nothing logs it
(`Chat.java:4058` prints `authToken=present(<length>)` only).

### 6.10 Open / tracked follow-ups

- **Objects7:** key the Ollama-extension block on "upstream is Ollama", not "dialect is OLLAMA"
  (§6.6) — filed as **KI-72**. Blocks repointing any existing `OLLAMA` chatConfig at the proxy.
- **Objects7:** retire `chatConfig.serviceType` (§5.1 "Not this pass") — still deferred; it remains
  the only carrier of `LOCAL`.
- **B4 is partial** (`Chat.java:4007,4068`, gated to `OPENAI_COMPAT`); no further Tier B work is
  scheduled.
- **Objects7:** enforce Guardrail 3 opaqueness at the emission point — validate/reject a
  non-opaque `user` in `Chat.buildTracingHeaders` rather than trusting every caller (§6.9). Not
  exploited today (nothing production sets `user`), but the field is client-writable via the
  generic `/rest/model` routes.
- **Deployment:** the `llmproxy` profile's committed fallback credentials are unguarded — omitting
  `--env-file` silently starts the stack with well-known keys (§6.9). Ports are loopback-bound as
  mitigation; a startup assertion would be better.
- **Every litellm image bump:** re-check the bundled langfuse SDK major AND that
  `LANGFUSE_MIGRATION_V4_WRITE_MODE=dual` is still required (§6.1). An SDK v3+ image should switch
  `success_callback` to `langfuse_otel` and drop that variable.

---

*§1's connection-model map was read against source on 2026-09-01; §2 reflects the architecture review
of the same date. §6 records what was actually built, run and measured, with dates inline.*
