# Troubleshooting & layer isolation

The default failure mode on this project is a UX symptom being blamed on the backend when the
client query is actually wrong. Isolate the layer before changing backend code.

## The gate
Before concluding "the backend is broken," reproduce the exact failing call against the live REST
API on :8443 with `ensureSharedTestUser()` (curl or a small script), bypassing the UI.
- Raw API returns correct data → it's a **client/query bug**. Fix in the UX / query layer.
- Raw API is genuinely wrong → it's a **backend/query-plan issue**. Escalate to backend-specialist.

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
