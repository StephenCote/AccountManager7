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

## 6. As-built (2026-09-14, extended 2026-09-15)

Everything below was **measured, or read from installed source in the image** — not inferred from
vendor documentation. Dates are inline. Where this section disagrees with §§1–5, this section wins.

**2026-09-15 pass.** KI-72 closed (§6.6 — the `system.connection.upstream` field; the blocker that
made this stack unusable for its actual purpose); the image-version contradiction settled empirically
and the streaming concurrency cap re-measured *with a control* (§6.3); the committed-credential
silence fixed with a startup assertion (§6.9); Guardrail 3 enforced at both emission points (§6.10);
and the "adding a model field needs a migration" premise disproven (§6.7). Two items in §6.10 are now
closed and one new one is open.

### 6.1 The stack as shipped

In `src/docker-compose.test.yml`, behind compose profile **`llmproxy`**. Nothing in the profile
starts with the default stack, and the `am7` service deliberately has **no `depends_on`** on it — so
a `chatConfig` pointing at `http://litellm:4000` **fails closed** when the profile is down.

- **`litellm`** — pinned **by digest** to litellm **1.102.0** (bundling langfuse Python SDK 2.59.7).
  **Re-verified empirically 2026-09-15** against the running container, not read off a comment:
  `python -c "import importlib.metadata as m; print(m.version('litellm'), m.version('langfuse'))"`
  → `1.102.0 2.59.7`. Deliberately NOT the `main-stable` channel (1.100.1): the concurrency cap is
  inert for streaming there, and AM7 always streams. Full rationale and measurements in §6.3;
  procedure for the next bump in [`dockerDevSetup.md`](dockerDevSetup.md) §12.7.
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
  `LANGFUSE_MIGRATION_V4_WRITE_MODE`. This is now step 3 of the written checklist,
  [`dockerDevSetup.md`](dockerDevSetup.md) **§12.7** — which also covers the two traps that make a
  bump dangerous rather than merely tedious (the streaming cap, and the numeric-config `os.environ/`
  failure), and both of which fail silently.

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

#### Re-settled empirically 2026-09-15 — the cap is live, and there is now a control

`docker-compose.test.yml` opened its `image:` comment by describing the `main-stable` **channel**
(1.100.1), and a second comment further down asserted outright that "the main-stable image now
pinned above (1.100.1)". Either reading made the pinned **digest** 1.100.1 — and if that had been
true the cap would be inert for streaming, i.e. the entire proxy pointless. It was never true. Both
statements are now corrected, and the compose comment leads with the pin, which is the single
authoritative version statement for this stack.

Settled by measurement, not by reading either comment, because image labels carry no version and
`pip show litellm` prints **nothing** in this image:

```
docker exec am7test-litellm-1 python -c "import importlib.metadata as m; print(m.version('litellm'), m.version('langfuse'))"
-> 1.102.0 2.59.7
```

Then the isolating experiment was re-run on that digest — throwaway litellm, threaded mock upstream
(fast first byte, 5s to complete, counting its **own** peak in-flight), K=3 concurrent **streaming**
requests, only the cap varied:

| cap | completion times | mock peak in-flight | verdict |
|---|---|---|---|
| 1 | 5.86 / 10.87 / 15.88s | **1** | queued |
| 3 | 5.32 / 5.32 / 5.32s | **3** | control |

**The cap=3 control is the important half, and it was missing from the original record.** Without it,
serialisation at cap=1 is not evidence — it could be the mock or the client serialising rather than
the semaphore. With it, the only variable is the cap. Note also that under cap=1 the **first-byte**
times were staggered 0.87 / 5.87 / 10.88: each request's first byte arrived only after the previous
one *finished*, which is exactly the property 1.100.1 lacks. The procedure is now a step-by-step
checklist in [`dockerDevSetup.md`](dockerDevSetup.md) **§12.7**, which also records that the bundled
SDK is still v2 and `LANGFUSE_MIGRATION_V4_WRITE_MODE=dual` is therefore still required.

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

### 6.6 Behavioural divergence — RESOLVED 2026-09-15 (was the blocker)

**The defect.** AM7 gated its whole Ollama-extension block on `serviceType == OLLAMA`, so the
`OPENAI_COMPAT` path silently dropped `num_ctx`, `top_k`, `repeat_penalty`, `typical_p`, `min_p`,
`repeat_last_n`, `num_gpu`, and `think` — a config that set `think:false` got thinking back **ON** —
and `Chat` halved the memory-extraction token floor (16384 → 8192). qwen3 is named in that very
source comment as the affected hybrid reasoning model and it is the model being proxied. Confirmed
live twice: a `max_tokens:20` request through the proxy returned pure `reasoning_content` with
`finish_reason: length`, and the same shape reproduced independently on 2026-09-15 at
`max_tokens:64` (`content:""`, all 64 tokens into `reasoning_content`).

**The fix — decide from the upstream family, not the dialect.** A new additive enum field
`system.connection.upstream` (`ConnectionUpstreamEnumType {UNKNOWN, OLLAMA, OPENAI}`) records what
the endpoint's model server actually **is**; Ollama behind a proxy is still Ollama. **Four** sites
are re-keyed onto it, plus the resumed-session call site — the extension block (now extracted to
`ChatUtil.applyOllamaUpstreamOptions`, and it **also emits `num_ctx`**, which it did not before),
`Chat`'s `keepThink`, `Chat`'s memory-extraction `minTokens`, and `Chat`'s token-field **prune
list**. The full table and why the fourth was missed are in
[`KnownIssues.md`](KnownIssues.md) KI-72. **Count them accurately here:** an undercount in the
record is how the next one gets missed, which is precisely what happened with the fourth.

`ChatUtil.resolveUpstream(connection)` reads the field and falls back to inference **from
`dialect`** — not from the deprecated `serviceType`, so §5.1's convergence is not made harder:

```
OLLAMA        -> OLLAMA
OPENAI        -> OPENAI
OPENAI_COMPAT -> UNKNOWN      <- MANDATORY. Never OLLAMA.
LOCAL/UNKNOWN -> UNKNOWN
```

**`OPENAI_COMPAT -> UNKNOWN` is the KI-72 prohibition**, and it is why widening the old `== OLLAMA`
test was never an option: the same dialect fronts Azure. The cost is that a proxied-Ollama connection
needs the operator to set `upstream = OLLAMA` explicitly — no inference can tell
LiteLLM-fronting-Ollama from LiteLLM-fronting-Azure. `Chat.configureChat` therefore logs a WARN
naming the chatConfig whenever it sees `OPENAI_COMPAT` + `UNKNOWN`. A discoverability log line was
chosen over a more permissive default precisely because no default can be both safe and convenient
here.

**Today's behaviour is unchanged by construction.** The column is nullable with **no DDL default**
(`DBUtil.generateSchemaLine` emits a `default` clause only for INT/DOUBLE/LONG/BOOLEAN/ZONETIME/
TIMESTAMP), so all pre-existing rows read SQL `NULL` — 148 in `am7db`, 20 in `am72db` — and `NULL`
routes to the dialect inference, which reproduces the old outcome exactly. `Chat.getUpstream()` is
inference-aware, so the many `new Chat(); setServiceType(OLLAMA)` constructions in the test suite
keep behaving natively without a persisted value.

**No migration exists or was needed** — see §6.7.

**A prerequisite bug fixed with it.** `ServerConfigUtil.putConnection` built its patch with the
**bare** `RecordFactory.newInstance(MODEL_CONNECTION)`, which materialises every field at its default
and the writer persists all of them. That silently reset `dialect` on every server-URL or apiKey edit
already, and would have reset `upstream` — meaning an operator's `upstream = OLLAMA` would vanish on
the next URL change, returning success. Now an explicit field projection. Found by architecture
review, not by a test; the covering assertion is called out as the first one to add.

**`num_ctx` reconciled, and the pin demoted.** Measured 2026-09-15 against the real proxy and LAN
Ollama, unloading the model between runs so `/api/ps` reflects the next load:

| request body | model entry | `/api/ps context_length` |
|---|---|---|
| `num_ctx: 12288` | pinned `40960` | **12288** |
| `num_ctx: 12288` | **no pin at all** | **12288** |

So a body `num_ctx` **always wins**, and `drop_params: true` does **not** drop it — litellm
recognises it as a valid `ollama_chat` parameter whether or not the entry declares it. **This
corrects the previous claim in this section and in `config.yaml`** that "`drop_params` would drop one
AM7 sent"; that was never measured and is false. ⇒ AM7 is the single authority; the pin applies only
to callers that send nothing (bare curl, the LiteLLM UI).

> **Expect a smaller context after repointing, not a larger one.** While the pin was the only
> source, proxied qwen3:8b effectively ran at 40960. Now the chatConfig governs, and
> `ChatUtil.applyChatOptions` defaults `num_ctx` to **8192** when `chatOptions` leaves it unset — so
> a config that never set it runs at 8192. Raising the pin cannot override the body. **Set `num_ctx`
> on the chatConfig.**

**`think` verified end to end, and the root cause corrected.** Through the proxy with no `think`
param: `content=''`, whole budget into reasoning. With `think:false` in the body:
`content='OK. How can I assist you?'`, no reasoning — so `think:false` passes through and works, and
`drop_params` does not drop it either. The direct native path with `think:false` returns **identical
content**, so proxied/direct parity holds once AM7 sends it. Note the mechanism is *not* a passive
Ollama default: the model reports the user said `"say OK /think"`, i.e. ` /think` is appended to the
last user message when thinking is on. That appears on the **direct native path too**, so it is
server/template-side, not something LiteLLM does.

**One gap accepted, deliberately.** `Chat`'s `OllamaModelUtil.recordUsage` call stays keyed on the
**native** dialect, because the registry it feeds is consumed by `unloadAll()`, which appends its own
`/api/generate` and speaks the native Ollama API — re-keying it would make `unloadAll()` POST
`/api/generate` at LiteLLM. Consequence: with `upstream=OLLAMA` behind the proxy, nothing records the
real Ollama load, so `unloadAll()` cannot free that GPU memory. That is a genuine hole for the
single-GPU scenario this whole stack exists to serve, and it is recorded in §6.10 rather than left
implicit in a comment.

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

**Adding a field needs no migration at all — established 2026-09-15 while shipping §6.6.**
`IOSystem.open()`'s boot loop patches missing columns for every model in `ModelNames.MODELS`
(`generatePatchSchema` → `ALTER TABLE … ADD COLUMN`), and it patches against the **resource** schema,
not the persisted blob: it calls `RecordFactory.getSchema()` *before* `activeContext` is assigned, so
`getIOSchema`'s `IOSystem.isInitialized()` gate is closed and `getSchema` falls through to
`importSchemaFromResource`. The resource-derived schema is then cached in-process for the life of the
JVM. So: **edit the model JSON, restart. That is the whole data-model change.** The propagation bound
is a restart; a running JVM will not pick it up.

Proof, from the run that added `upstream`:

```
[INFO] IOSystem - Schema patch: ALTER TABLE A7_system_connection_0_1 ADD COLUMN upstream varchar(16);
```

and independently, `am7db`'s persisted `system.connection` blob contains **no `dialect` at all** while
the `dialect` column exists, holds real values and its tests pass — a resource-only field working end
to end against a stale blob. (`am72db`'s blob *does* carry `dialect`; the two databases disagree and
both work, which is further evidence the blob is not load-bearing for this.)

This matters because `objects7-reference.md` previously said flatly that "editing a model `.json` has
no runtime effect" on a provisioned deployment. That is true only for **changing an existing field's
shape** (a genuine runtime-read path), and false for **adding a field**. On 2026-09-15 the blanket
claim led a planner and an architect to independently design a `RecordFactory.addFieldToSchema`
migration plus a `PUT /rest/schema` route, neither of which was necessary — and the REST route would
have set `system=false`, putting the new column within reach of `SchemaService.deleteField`'s ungated
`ALTER TABLE … DROP COLUMN`. Stephen rejected the premise; the rules file now carries the Path 1 /
Path 2 distinction.

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

**These were not gated on anything — now they are (2026-09-15).** Previously: omit `--env-file`,
mistype the flag, or run the profile from a script that forgets it, and compose started cleanly using
exactly these values with **no warning**. The silence was the defect; loopback binding was the only
mitigation.

`LLMPROXY_BIND_HOST` (default `127.0.0.1`) is now the **single source of truth** for both published
binds (`litellm` 4000, `langfuse-web` 3001; `langfuse-minio` still publishes nothing), and the same
variable is handed into the `litellm` container so the entrypoint's assertion evaluates the **real**
exposure rather than a second copy that could drift. On every start it:

- compares `LITELLM_MASTER_KEY`, `LITELLM_UI_PASSWORD`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`
  against their committed values and prints a `[am7] WARNING:` banner naming every match;
- **exits 1 (`[am7] FATAL`)** when any is a committed default **and** the bind host is not loopback.

> **SCOPE — and an earlier revision of this section overclaimed it.** That assertion protects the
> **LiteLLM admin surface only**: the master key (`/key/generate`, spend, model list) and use of the
> GPU. **It does not protect Langfuse, and it cannot.** `litellm` `depends_on` `langfuse-web` with
> `condition: service_healthy`, so `langfuse-web` is already up and serving before the assertion
> runs; nothing `depends_on` litellm in the other direction; and `langfuse-web` carries
> `restart: unless-stopped`, so litellm's `exit 1` is a restart loop that kills only litellm's own
> port. An earlier revision of this file additionally pointed **both** published binds at
> `LLMPROXY_BIND_HOST`, which made the worst case reachable by one environment variable: setting it
> to `0.0.0.0` without `--env-file` put the Langfuse UI and `/api/public/*` on the LAN, on committed
> well-known credentials, **while the service that "refused to start" was the less sensitive of the
> two** — and Langfuse traces hold full prompt and completion bodies (Guardrail 4), the most
> sensitive thing in the stack. Found by security review 2026-09-15.
>
> Fixed by **decoupling the binds**: `langfuse-web` now has its own `LANGFUSE_BIND_HOST`, which
> defaults to `127.0.0.1` and is **never** widened by `LLMPROXY_BIND_HOST`. Verified — with
> `LLMPROXY_BIND_HOST=0.0.0.0`, `docker compose config` renders litellm at `host_ip: 0.0.0.0` and
> langfuse-web still at `host_ip: 127.0.0.1`. Overriding `LANGFUSE_BIND_HOST` remains possible and
> is a deliberate, separate data-export decision **with no assertion behind it** — check the three
> Langfuse credentials yourself first.

**It deliberately does not fail merely because a default is in use.** Decided with Stephen
2026-09-15: `src/volatile/llmproxy.env` on this workstation sets all five of these to the throwaway
values, which this section sanctions for a loopback-bound dev box, so an unconditional failure would
refuse to boot a working setup and would train everyone to set an override flag reflexively. The
requirement was "stop being silent", and silence is what was removed.

**Coverage limit, stated rather than papered over:** the assertion cannot see
`LANGFUSE_INIT_USER_PASSWORD`, `LANGFUSE_SALT` or `LANGFUSE_ENCRYPTION_KEY`. Those are not copied
into the litellm container merely to be asserted on — that would spread secrets into a container with
no use for them. They are defaulted together with the other four in practice (same env file, or
none), so catching those four catches the condition; the banner says so explicitly.

Verified 2026-09-15 across all five branches: defaults+loopback → warn, start (confirmed on the live
container); defaults+`0.0.0.0` → FATAL, exit 1; real credentials+loopback → `no committed defaults in
use`, exit 0; real credentials+`0.0.0.0` → starts, since nothing well-known would leak; a **single**
defaulted credential among real ones + a LAN address → FATAL naming that one variable.

The profile remains opt-in and absent from the production `docker-compose.yml`. **Still never run it
on a shared or LAN-reachable host without overriding every value above** — the assertion enforces the
credential half, not the trace-content half (Guardrail 4, below).

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

**Guardrail 3 is now ENFORCED (2026-09-15) — see §6.10.** The paragraph below describes the
pre-fix state and is kept because it is the rationale for the fix; do not read it as current. It is
enforced by `TracingIdValidator.isOpaque` at **two** emission points (the `x-langfuse-*` headers and
the `OPENAI_COMPAT` wire body), by **dropping** a non-opaque value, never rejecting.

*Pre-fix state:* `Chat.buildTracingHeaders`
forwarded whatever string sat on `req.user` / `req.session_id` without validating it. Compliance today
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

- ~~**Objects7:** key the Ollama-extension block on "upstream is Ollama", not "dialect is OLLAMA"~~
  — **DONE 2026-09-15**, via the additive `system.connection.upstream` field. Repointing an existing
  `OLLAMA` chatConfig at the proxy is now supported (set `upstream = OLLAMA`; restart required).
  Full account, measurements and the accepted gap in §6.6; **KI-72** is closed.
- **Objects7 (NEW, opened by that fix):** with `upstream = OLLAMA` behind the proxy, nothing records
  the real Ollama model load, because `OllamaModelUtil.recordUsage` stays correctly keyed on the
  *native* dialect (its registry feeds `unloadAll()`, which speaks native `/api/generate` and would
  otherwise be pointed at LiteLLM). So `unloadAll()` cannot free GPU memory for a proxied load — a
  real hole for the single-GPU scenario this stack exists to serve. Needs a way to record the
  *upstream* server rather than `getServerUrl()`. See §6.6.
- **Objects7:** retire `chatConfig.serviceType` (§5.1 "Not this pass") — still deferred; it remains
  the only carrier of `LOCAL`.
- **Tests (opened by the §6.6 fix):** two parts of it are **compile-only**. The resumed-session
  emission fix in `ChatUtil.getOpenAIRequest` (the branch that applies chat options without a `Chat`
  instance) and the `configureChat` discoverability WARN have no executed coverage — no test builds a
  persisted resumed session on a connection with `upstream = OLLAMA`. Everything else in that change
  has wire-level or live coverage, so this is the one place a regression could land silently.
- **Objects7 — THE SAME "decided from the dialect" FAMILY, three more sites (found by architecture
  review 2026-09-15).** The §6.6 fix re-keyed four sites; these were not in scope and are not
  regressions, but they are the same defect shape and were recorded to be settled as one decision
  rather than discovered one at a time. **Item 2 is now DONE (2026-09-15), together with
  `ChatUtil.supportsSamplingParams` from §6.6's residual notes — sites 5 and 6, so the family count
  is now SIX re-keyed.** Items 1 and 3 remain open by Stephen's explicit decision. See KI-72's
  sites 5/6 table for what changed, the `TestUpstreamWireEmission` cases that cover each, and an
  important correction to item 2's reported blast radius (it bites only when `chatOptions.think`
  is `true`; with the default it was a no-op, measured):
  1. **`Chat.applyAnalyzeOptions` / `getScenePrompt` / `buildKeyframeRequest`** write their
     deliberate reduced cap into `ChatUtil.getMaxTokenField(chatConfig, serviceType)`, which is
     **dialect**-derived and resolves to `max_tokens` on `OPENAI_COMPAT`. So on the proxied path the
     override sets `max_tokens` while `num_ctx` stays at the full conversational value and is now
     *retained* by the new prune — analyze/scene/keyframe calls through a proxy assert the full
     context instead of the reduced one. Better than pre-fix (where no `num_ctx` was sent and the
     40960 LiteLLM pin applied), but **not** the native/proxied parity §6.6 aims at.
     `TestUpstreamWireEmission` `caseD` proves the override only for the native dialect; a proxied
     equivalent would fail today.
  2. ~~**`PageIndexUtil.java:892`** gates `req.set("think", false)` on the **deprecated**
     `chatConfig.serviceType` — neither axis.~~ **DONE 2026-09-15**: now
     `chat.getUpstream() == OLLAMA`, off the `Chat` instance already in hand, which also retires a
     deprecated-`serviceType` consumer (§5.1 direction). Covered by `TestUpstreamWireEmission`
     `caseI1`/`caseI2`. **The "a proxied Ollama gets no `think` field set at all" reasoning recorded
     here was wrong**: `req.hasField("think")` is true for the schema default, so `keepThink` already
     put `think:false` on the wire for any Ollama upstream. The gate is load-bearing only when
     `chatOptions.think = true`. Details and the measurement in KI-72.
  3. The native `max_tokens` prune, immediately below.

  **The structural point worth acting on** (architecture review): the code that *emits* a field and
  the code that *decides whether that field may ride the wire* live in different classes with
  independently derived keys. The invariant "whatever `applyOllamaUpstreamOptions` emits is exactly
  what the prune keeps" is maintained by hand in three places and has already failed three times.
  Recommended consolidation, no behaviour change: `ChatUtil.contextField(upstream)` (→ `num_ctx` iff
  OLLAMA), keep `getMaxTokenField` untouched as the *cap-field name* function, and make
  `OLLAMA_EXTENSION_FIELDS` one public constant the emitter and the prune both iterate. Then sites
  agree with a constant rather than with each other. **Do not** fold the prune into
  `getMaxTokenField` — that would force a dialect-shaped function to return upstream-shaped output.

  The heuristic that tells the two axes apart, worth keeping: **if the decision would change when
  you put a proxy in front of the same GPU, it is upstream-keyed; if it describes the endpoint you
  are talking to, it is dialect-keyed.**
- **Objects7 (pre-existing, surfaced by the §6.6 wire capture; needs a decision, not a fix):** the
  token-field prune also strips `max_tokens` on the **native** Ollama wire, because `tokField` is
  `num_ctx` there. That defeats `applyOllamaUpstreamOptions`' `max_tokens` set, whose comment says it
  exists "so generation terminates at the user-configured cap instead of running unbounded" — so on
  the native path generation is not capped by the user's setting today. Unrelated to the upstream
  axis. Fixing it changes main-path generation behaviour (cost and latency), so it is Stephen's call;
  `TestUpstreamWireEmission` `caseC` pins the current behaviour rather than the comment's intent.
- **B4 is partial** (`Chat.java:4007,4068`, gated to `OPENAI_COMPAT`); no further Tier B work is
  scheduled.
- ~~**Objects7:** enforce Guardrail 3 opaqueness at the emission point~~ — **DONE 2026-09-15** via
  `TracingIdValidator.isOpaque`, an allowlist shape test (UUID, or a 12–128 char
  `^[A-Za-z0-9][A-Za-z0-9._:-]*$` token containing a digit). Two corrections to the item as it was
  written here: it is enforced by **dropping, not rejecting** — a tracing field must not be able to
  fail a chat, and a reject path would hand any client that can write `openai.openaiRequest.user` a
  way to break every chat; and it is applied at **two** emission points, not one. This item named
  only `buildTracingHeaders`, but the wire **body** keeps `user` for exactly `OPENAI_COMPAT` — the
  one dialect where LiteLLM maps body `user` onto `trace.userId` — so gating the header alone would
  have left the leak fully open on the only path that reaches Langfuse. The WARN logs the field name,
  the value's length and a truncated SHA-256 prefix, never the value.
- ~~**Deployment:** the `llmproxy` profile's committed fallback credentials are unguarded~~ —
  **DONE 2026-09-15.** `LLMPROXY_BIND_HOST` is now the single source of truth for both published
  binds and is handed to the `litellm` container, whose entrypoint names every committed-default
  credential in a startup banner and **exits 1** when a default is in use *and* the bind is not
  loopback. Deliberately does not fail on defaults alone — see §6.9 for why, the coverage limit, and
  the five verified branches.
- ~~**Every litellm image bump:** re-check the bundled langfuse SDK major AND that
  `LANGFUSE_MIGRATION_V4_WRITE_MODE=dual` is still required~~ — **DONE 2026-09-15**, as a written
  procedure rather than a reminder: [`dockerDevSetup.md`](dockerDevSetup.md) **§12.7**. It covers
  establishing the real version via `importlib.metadata` (`pip show` prints nothing in this image),
  re-running the streaming cap measurement **with the cap=3 control**, the SDK-major → callback
  mapping table, the numeric-config trap, and pinning by digest. Current state recorded there and
  re-verified 2026-09-15: litellm 1.102.0 / SDK 2.59.7 (v2), so `dual` **is still required**.

---

*§1's connection-model map was read against source on 2026-09-01; §2 reflects the architecture review
of the same date. §6 records what was actually built, run and measured, with dates inline.*
