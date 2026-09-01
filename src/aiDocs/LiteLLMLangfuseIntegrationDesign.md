# LiteLLM / Langfuse Integration — Design & Plan

**Date:** 2026-09-01
**Status:** design ratified by architecture review (APPROVED with guardrails); **not started**.
**Scope:** Add optional support for **LiteLLM** (an OpenAI-compatible LLM proxy/gateway) as a chat
connection dialect, and **Langfuse** (LLM observability / metrics / tracing) as an optional metric
source. Touches `AccountManagerObjects7` (LLM layer) and the Docker compose stack; an optional future
tie-in surfaces Langfuse metrics inside `AccountManagerISO42001`.

> Behavioral rules: `../.claude/rules/llm-conduct.md`. Layering: `../.claude/rules/architecture.md`.
> Cross-layer model/PATCH rules: `../.claude/rules/model-api.md`.

---

## 1. Current LLM connection design (as-built)

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

### 2.4 Embeddings via LiteLLM — OUT OF SCOPE (phase 1)
Bound by the **boot-pinned `embedding.dimensions`** (the vector column is one fixed width; stored
vectors carry no provenance). `EmbeddingUtil` lives in the single process-global `VectorUtil`. Keep
embeddings as-is; only revisit if a LiteLLM-backed embedding model produces the identical dimension.

### 2.5 Docker — optional sidecars behind compose profiles
The **test stack is the clean template** (`am7-pg` on `am7-test-net` with service-name DNS). Add
LiteLLM and Langfuse (Langfuse = its own web service + Postgres) as **optional** services behind
compose profiles; the app reaches them by service name (`http://litellm:4000`, etc.).

**Constraint:** Docker on this Windows host **cannot reach LAN `192.168.1.x`**
(`../.claude/rules/troubleshooting.md`). So an in-compose LiteLLM that merely forwards to the LAN
Ollama (`.42:11434`) or SD (`.39:7801`) fails identically. To be useful it must (a) run the model
itself, (b) forward to a cloud endpoint, or (c) reach the host via `host.docker.internal` (a **host**
process can see the LAN). Langfuse (receives over the compose network) needs no LAN and is unconstrained.

### 2.6 Optional future — Langfuse metrics into ISO 42001 reports
Surface Langfuse cost/latency/token metrics into ISO reports. This logic lives in the
**`AccountManagerISO42001`** module (depends on Objects7). **Never** let Langfuse token/cost parsing
leak into Objects7 or Service7. This is the natural bridge between "metric source" and the ISO
reporting half of the original request.

---

## 3. Phased plan

| Phase | Work | Effort | Module(s) |
|---|---|---|---|
| B1 | `OPENAI_COMPAT` dialect enum + `getServiceUrl()` branch (`/v1/chat/completions`); reuse body/parser/auth | **small** | Objects7 |
| B2 | `dialect` (`ConnectionDialectEnumType`) field on `system.connection` + safe default; UI edit path sends `name`+identity | **small** | Objects7 (+ Ux752 edit form) |
| B3 | Optional LiteLLM + Langfuse compose services behind profiles; document LAN-reachability constraint | **small–med** | Docker |
| B4 | *(optional)* Tier B native tracing: gated request metadata + per-call header hook | **moderate, additive** | Objects7 |
| B5 | *(optional, future)* Langfuse metrics → ISO 42001 reports | **moderate** | ISO42001 |

**Recommended sequence:** B1 → B2 → B3 (get LiteLLM+Langfuse working via the proxy, zero-tracing-code),
then decide on B4/B5.

## 4. Verification standard
- Objects7 change: `mvn -o -pl AccountManagerObjects7 install -DskipTests` then compile dependents;
  add a JUnit test that exercises the `OPENAI_COMPAT` URL assembly and a live round-trip against a
  reachable OpenAI-compatible endpoint (LiteLLM in compose, or the host-reachable LAN LLM via a host
  Tomcat — **not** Docker, which can't reach the LAN). `-DskipTests=false` is mandatory for Objects7.
- No schema resets. Adding `dialect` is an additive column with a safe default.

## 5. Open decisions (need Stephen's call)
1. **Langfuse depth:** Tier A only (proxy-side, zero AM7 code) or also Tier B (native tracing)?
   Recommendation: **Tier A first.**
2. **`serviceType` fate:** keep on `chatConfig` for back-compat, or derive from `connection.dialect`
   and deprecate?
3. **B5 in scope?** Whether Langfuse-metrics-into-ISO-reports is part of this initiative or a later one.

---

*This document is design/plan only. No code, model, or config has been changed. The connection-model
map in §1 was read against source on 2026-09-01; §2 reflects the architecture review of the same date.*
