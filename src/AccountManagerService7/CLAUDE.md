# AccountManagerService7 — REST Service Layer

## Read first

- **Working discipline** (NO LYING, honesty, testing): `../.claude/rules/llm-conduct.md`
- **Architecture & layering**: `../.claude/rules/architecture.md`
- **Deep technical reference** (Jersey config, auth flow, service tables, WebSocket, web.xml,
  testing): `../.claude/rules/service7-reference.md`
- **Cross-layer query/serialization/PATCH/foreign-model patterns**: `../.claude/rules/model-api.md`

## Role

Jersey-based REST API (war) plus MCP + WebSocket transport over Objects7/ISO42001. Deployed to Tomcat
at **`https://localhost:8443`**. This layer is **transport only** — no business logic. It bridges HTTP
↔ AM7 context via `AccessPoint` + `ServiceUtil`; business logic lives in Objects7/ISO42001.

## Must-know gotchas (details in the reference docs)

- **Never bypass PBAC.** All operations go through `AccessPoint` and respect authorization.
- **Always use `ServiceUtil.getPrincipalUser(request)`** to get the current user (handles JWT + session).
- **Generic CRUD** via `ModelService` (`/rest/model/{type}...`); by-id ops use
  `/rest/model/{type}/{objectId}`; `/full` uses `planMost(true)`; updates via `PATCH /rest/model`.
- **Responses use `toFullString()`**; the `schema` field is **required** for deserialization. Lists may
  carry `schema` only on the first item — restore it. See `model-api.md`.
- **New services** are `<Domain>Service` in `org.cote.rest.services` (auto-scanned); add `@RolesAllowed`
  to every new endpoint (except pre-auth WebAuthn), and each new service needs unit tests.

## Testing

Integration tests hit the live backend. Obtain a JWT via `/rest/login/token`, send
`Authorization: Bearer <token>`, and verify 401/403 paths. **Never test as `admin`** — use
`ensureSharedTestUser()`. Full verification standard: `../.claude/rules/architecture.md`.
