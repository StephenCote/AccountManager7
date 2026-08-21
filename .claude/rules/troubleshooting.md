# Troubleshooting & layer isolation

The default failure mode on this project is a UX symptom being blamed on the backend when the
client query is actually wrong. Isolate the layer before changing backend code.

## The gate
Before concluding "the backend is broken," reproduce the exact failing call against the live REST
API on :8443 with `ensureSharedTestUser()` (curl or a small script), bypassing the UI.
- Raw API returns correct data → it's a **client/query bug**. Fix in the UX / query layer.
- Raw API is genuinely wrong → it's a **backend/query-plan issue**. Escalate to backend-specialist.

## Backend PBAC/policy tracing — use it, don't skip it

Several backend utilities have a built-in verbose trace mode, purpose-built for exactly this kind of
investigation (authorization/PBAC decisions especially) — `IOSystem.getActiveContext().getPolicyUtil()
.setTrace(true)` turns it on across `PolicyEvaluator`, `AuthorizationUtil`, and `PolicyUtil` itself
(all three are toggled together by one call, `PolicyUtil.java:165-168`); `AccessPoint` checks
`getPolicyUtil().isTrace()` at its own key decision points (`AccessPoint.java:206,213,497,657`) to emit
detailed policy/authorization reasoning. It is genuinely verbose — **bracket it tightly**: enable
immediately before the one specific call under investigation, disable immediately after
(`setTrace(false)`), and don't leave it on across a whole test run or session. Prefer this over
guessing from a bare `PBAC denied`/`Group could not be found` log line when the actual authorization
reasoning (which role/policy/participation check failed, and why) matters — it's the direct, intended
tool for that, not a workaround.

## Server-side gotchas to check first
- `/rest/model/search` request bodies must use `"schema":"io.query"` (the real registered model,
  `ModelNames.MODEL_QUERY`) — **not** the bare `"schema":"query"` shown in some doc examples in this
  repo (`model-api.md`/`service7-reference.md`). The bare form fails `RecordFactory`/`ResourceUtil`
  resource lookup (`ModelNotFoundException: Model query was not found`) and `ModelService.search`
  silently 404s (`imp == null` branch, `ModelService.java:290-293`) — no stack trace beyond an ERROR-level
  `RecordDeserializer`/`RecordFactory` log line, easy to mistake for a routing/auth failure. Confirmed
  live 2026-07-22 while trying to reproduce KI-28/KI-29 — cost real time chasing a phantom "auth/session"
  theory before checking the app log and finding the actual deserialization error there.

## Client-side gotchas to check first
- `groupId` = directory numeric `.id`, not `.objectId` UUID.
- Id-typed query fields are numbers, not strings (`{value:2}` not `{value:"2"}`).
- `/rest/model/search` is cached — set `cache:false` for fresh reads.
- `am7client.member()` sField = field name, not participant model.
- PATCH needs `schema` + identity + changed fields; by-id ops use `/rest/model/{type}/{objectId}`.
- Foreign fields aren't auto-populated — project them (`planMost`/`/full`).
- `data.directory`-derived list queries need an explicit `organizationId` condition (else PBAC denies).
- Enums: lowercase on the wire, UPPERCASE in Java — compare case-insensitively.
- Lists may carry `schema` only on the first element.

## Routing map (which specialist owns what)
- Data missing/wrong from the UI, "backend seems broken" → **query-specialist** (runs the gate above).
- Schema/model design, persistence, PBAC internals, genuine query-plan bugs → **backend-specialist**.
- Module layering / design-philosophy questions → **architect**.
- Authorization, `@RolesAllowed`, secrets, bias-directive integrity → **security-reviewer**.
- Mithril UI rendering/behavior → **ux-specialist**.
- Writing/running real tests → **test-author**; final pass/verdict → **verifier**.
