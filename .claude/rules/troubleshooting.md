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
- **`olio.world` records in the Books universe are owned by the olio principal, not the request user.**
  They are created by `OlioContext` initialization, which runs as the olio system user. So
  `AccessPoint.find` with the HTTP principal returns **null** for them — which looks like "the record
  doesn't exist" rather than a permission problem. `WorldUtil.deleteWorld`, and any lookup of these
  worlds or their `olio.pb.book` records, must resolve the principal first via
  `Factory.findUser(OlioContext.OLIO_USER_NAME, orgId)` and use that. Don't filter `olio.pb.book` by
  `ownerId` when the book may be olio-principal-owned.

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
- **`pagination.new()` alone does not show a just-created record** on return from a `/new/` or `/pnew/`
  route — the server keys its `/rest/model/search` cache to the original query, and a client-side reset
  doesn't invalidate it, so the re-fetch returns the same stale (often empty) page. The complete pattern,
  in `pagination.js` + `list.js` as of 2026-08-29: (1) set `pagination.pages().noCache = true` on every
  list `oninit`/remount as a one-shot flag; (2) when it's set, `getSearchQuery()` calls
  `am7client.clearCache(type, true)` **and** sends `cache:false` in the request body; (3) on return from
  `/new/`, also set `sort="id"` / `order="descending"` so the new record (highest id) lands on page 1
  regardless of alphabetical position; (4) clear `noCache` after the first successful load.

## Testing-environment gotchas (Docker, Playwright, Vitest)

**Read this section before writing or running any Playwright test against the Docker stack.** Four
things must all be true or the tests fail in ways that look like application bugs:

1. **`localhost` resolves to IPv6 `::1`; Docker only maps IPv4.** The browser tries `::1`, TLS resets,
   and `page.goto` times out after 30s with a blank white screenshot. Either use `127.0.0.1` explicitly
   (`PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443`), or map it in chromium args — preferred when the test
   URL must say "localhost":
   ```js
   use: { launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] } }
   ```
   This re-bit on 2026-08-29 because the agent running the tests didn't read this first.
2. **Stub the WebSocket.** Docker's nginx proxy doesn't forward the session cookie on the WS upgrade, so
   Tomcat closes it immediately; 1000ms later `pageClient.js` `reconnect()` calls `forceLogin()` and
   redirects to `#!/sig`. Call `page.addInitScript()` **before** `page.goto()` to stub
   `window.WebSocket` with a class that fires `onopen` and never fires `onclose` — canonical pattern in
   `loginAsSharedUser()` in `e2e/chapBook.spec.js`.
3. **`dist` must be current.** The image bakes a `vite build` snapshot at image-build time, so frontend
   changes made afterward are not being served. Update without a full rebuild:
   ```bash
   cd src/AccountManagerUx752 && npx vite build
   docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/
   ```
4. **`docker-compose.yml` is in `src/`, not the git root**, and the bash cwd resets between tool calls —
   put the `cd` in the same line: `cd "C:\Projects\GitHub\AccountManager7\src" && docker-compose up -d`.

**CORS: same-origin POSTs 403 while GETs work.** Chrome 103+ sends an `Origin` header on same-origin
POSTs, and Tomcat's `CorsFilter` 403s any origin missing from `cors.allowed.origins`. Because Playwright
on Windows must use `https://127.0.0.1:9443` (see 1 above), every `POST /rest/model/search` and
`/search/count` 403s while GETs succeed — which silently empties every list view's row content and looks
like a data bug. `CORS_ALLOWED_ORIGINS` in `docker-compose.test.yml` now includes
`https://127.0.0.1:9443,http://127.0.0.1:9443`. When adding an origin, re-run
`docker-compose -f docker-compose.test.yml up -d` — **`restart` does not re-read the compose file.**

**Docker cannot reach the LAN — don't use it for SD/LLM tests.** Docker Desktop on Windows bridges to
`172.20.x.x` and cannot route to arbitrary LAN addresses: 100% packet loss to the SD server at
`192.168.1.39` and the LLM at `192.168.1.42` from inside any container. SD image generation or LLM calls
through Docker Tomcat fail silently — no connection, no images, no visible errors. The host's
Eclipse-managed local Tomcat *can* reach them and is the correct target; when Stephen says "Tomcat is
running on localhost," that is the local Eclipse Tomcat, not Docker. Build the jars
(`mvn -o -pl AccountManagerObjects7 install -DskipTests && mvn -o -pl AccountManagerService7 compile`)
and ask him to bounce it.

**Vitest: all tests pass but the gate is red.** Ux752 Vitest runs in `environment: 'node'`, which has no
`requestAnimationFrame`. Mithril's `mount-redraw.js` captures `schedule = (typeof
requestAnimationFrame !== "undefined" ? requestAnimationFrame : null)` **at import time**, so it's
`null` in node. Any test that triggers `m.request(...)` makes Mithril auto-redraw on completion and
throw `TypeError: schedule is not a function` as an *unhandled rejection*, surfacing under whatever test
happens to run next. Every test still reports passing while `verify.sh --quick` reports
`Errors 1 error` → `VERIFY_FAILED`. Fix is a `setupFiles` shim (`src/test/setup.js`) defining
`globalThis.requestAnimationFrame`/`cancelAnimationFrame` before any test imports Mithril — **do not
`import mithril` in the setup file**, since the ESM import hoists above the assignment and Mithril
captures the still-undefined RAF. Overriding `m.redraw` does not help; the `request` module holds its own
redraw closure. Separately, `core/pageClient.js` ↔ `components/dialogCore.js` are a circular import; a
test importing `dialogCore` first leaves `Dialog` undefined when `pageClient`'s object literal evaluates
`open: Dialog.open` at module init. Defer to call time with `open: (...a) => Dialog.open(...a)`.
Production is unaffected (a real browser has RAF, and `main.js` loads `pageClient` first). ⇒ When the
gate is red but every test passes, suspect an async Mithril redraw without RAF or a module-init circular
import — not a real test failure.

**Fresh test users always have `AccountUsers`.** Org initialization auto-enrols every new `system.user`
into the `AccountUsers` role at creation. A role-gate test that needs a user *without* it therefore
cannot be built by creating a fresh user — it needs an admin-level removal after creation. Found
2026-08-29 testing the PictureBook role check: the warning-banner code was correct, the condition just
wasn't reachable by fresh-user creation.

## Routing map (which specialist owns what)
- Data missing/wrong from the UI, "backend seems broken" → **query-specialist** (runs the gate above).
- Schema/model design, persistence, PBAC internals, genuine query-plan bugs → **backend-specialist**.
- Module layering / design-philosophy questions → **architect**.
- Authorization, `@RolesAllowed`, secrets, bias-directive integrity → **security-reviewer**.
- Mithril UI rendering/behavior → **ux-specialist**.
- Writing/running real tests → **test-author**; final pass/verdict → **verifier**.
