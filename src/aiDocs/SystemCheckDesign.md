# System check: dependency and state report (proposal, 2026-11-17)

> **Superseded 2026-11-27 by [`ServiceStatusDesign.md`](ServiceStatusDesign.md)**, the design of record. This file is kept for its origin and rationale.

Origin: on 2026-11-17 the chat stack ran with every embedding call failing (`ChatWardrobeTokensDesign.md` §1.1). The only symptom was `EmbeddingUtil - Response was null` in the Tomcat log. Stephen asked for a Ux warning when embedding settings are wrong: either in the app profile, or as a system check in the control panel that reports system dependencies and state.

## Why not only the app profile

- **The app profile is cached.** `PrincipalService.getApplicationProfile` caches it per user in a static `profiles` map for the life of the JVM (`PrincipalService.java:146-153`). A health flag there would show its first value forever. Fixing the `/System` embedding connection at runtime would not clear a stale "broken" flag, and the flag would never change from "fine" to "broken" either.
- **Embedding config changes at runtime.** The URL is DB-backed and can be changed while the app runs (`architecture.md`, "Config: DB-backed vs boot-pinned"). So "right or wrong at app start" is not quite a fixed property.
- **The existing probe can't detect this failure.** `EmbeddingUtil.heartbeat()` (`:480-487`) GETs `<url>/heartbeat`, an endpoint only the `local` Python service has. Against an `openai_compat` URL it fails even when embeddings work. Against the Python URL with the `openai_compat` type it *passes* while every real call 404s, which is exactly the 2026-11-17 state. Only a real test embedding tells the truth.

## Proposal

### 1. Objects7 `SystemCheckUtil`: runs the checks, holds no transport

Each check returns `{name, status: OK|WARN|FAIL|SKIP, detail, latencyMs, checkedAt}`.

| Check | How | FAIL when |
|---|---|---|
| `embedding` | One real `getEmbedding("system check")` through the live `EmbeddingUtil`, then `validateEmbeddingWidth` | null, or width ≠ `embedding.dimensions` |
| `embedding.config` | Compare the boot-pinned type with the shape of the DB-backed URL | `local` with a `/v1/embeddings` URL, or `openai*` with a URL that answers `/heartbeat` (WARN) |
| `vectors` | Row counts per vector table (`vectormemory`, `vectorchathistory`, `vectormodelstore`, `pageindexnode`) | Never fails. Informational: "switching provider now invalidates N vectors" |
| `llm.<connection>` | For each `system.connection` used by a chatConfig: LiteLLM `/health/liveliness`, or Ollama `/api/tags` | unreachable |
| `sd`, `face`, `tag`, `voice.tts`, `voice.stt` | Their existing heartbeat or a GET on the server URL | unreachable (WARN: these are optional) |
| `db` | `select 1` plus schema version | error |

Each probe has a short timeout (~5 s), and the probes run in parallel. The util takes no per-org state and writes nothing (read path, per `architecture.md`).

### 2. Cached summary plus forced recheck

- **Boot.** Run once at boot (`RestServiceEventListener`) and keep the last result in memory.
- **Connection edits.** When a `/System` connection is saved through `ServerConfigUtil`, re-run the checks for that connection. That is the same hook that already refreshes the `EmbeddingUtil` singleton.
- **Polling.** No periodic polling. The panel's "Run checks" button forces a fresh run.
- **Cross-JVM bound.** Console7 edits to a connection are not seen until the next forced run or restart. This must be stated in the panel.

### 3. Service7: transport only

| Route | Role | Returns |
|---|---|---|
| `GET /rest/system/check/summary` | `user` | `{status: OK\|DEGRADED\|FAIL, degraded: ["embedding", …], checkedAt}`. Names only: no URLs, hosts or tokens |
| `GET /rest/system/check` | admin | Full results, including URLs. Shows internal topology, so admin only |
| `POST /rest/system/check` | admin | Force a re-run; returns the full results |

### 4. Ux

- **Banner.** At app start, `router.js:291` already awaits `am7client.application()`. Next to it, fetch `/system/check/summary`. If anything user-facing is degraded, show a dismissible top banner, for example: *"Embeddings unavailable — chat memories and search are disabled. Ask an administrator to run System Check."* Re-fetch the summary on focus or reconnect, not on every page.
- **Feature-local warning.** `MemoryPanel` shows the same embedding status inline, where the missing memories would be noticed.
- **Control panel "System Check" card (admin).** A table of checks, each with a status icon, detail, latency and checked-at time; a "Run checks" button; and, for embedding, the boot type, model, dimensions and DB URL side by side, with a link to edit the `/System` connection.
- **Test embedding on connection save.** Saving the `embedding` connection runs check 1 first and rejects the save (or warns) on FAIL. This closes the gap noted in `ChatWardrobeTokensDesign.md` §1.1.

### 5. Tests

- **JUnit `TestSystemCheck`** against live services. The embedding check must FAIL for a deliberately mismatched type and URL pair, using a scratch `EmbeddingUtil` instance and never the process singleton. It must pass for the configured one. The summary must never contain a URL.
- **Playwright** as `ensureSharedTestUser()`: the summary endpoint is reachable and the full endpoint returns 403. With an admin-role test user (not `admin`), the card renders and "Run checks" refreshes `checkedAt`.

## Open questions

1. Which roles count as admin for the full report: `AccountAdministrators`, or a system-level role?
2. Should a FAIL on `embedding` also disable memory extraction automatically, with the skip reason reported, instead of letting each extraction fail on its own?
