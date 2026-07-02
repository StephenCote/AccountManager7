# ISO 42001 Ux Gap Analysis & Implementation Plan (Ux752)

**Date:** 2026-07-01
**Scope:** `AccountManagerUx752/src/features/iso42001/` vs. the design spec (`aiDocs/ISO42001/iso42001-design.md` §9A) and the live backend (`AccountManagerISO42001` engine + `ISO42001Service` REST shim + `ISO42001ServiceFacade`).
**Method:** Read every feature file, the REST service, the facade, the model schemas, and the §9A wireframes. Findings below distinguish **pure-UI gaps** (backend is ready) from **gaps that also need backend work** — this matters because two of the things called out (stop a campaign, revoke a cert, add a message) are *not* currently supported by the backend at all, so a UI-only plan would be dishonest.

---

## 1. What exists today

Six view files, ~960 lines, wired into the `iso42001` feature manifest (`features.js`) and routed in `routes.js`. Context roles are correctly populated in `router.js:298-307`.

| View | File | State |
|---|---|---|
| Dashboard | `dashboard.js` | Summary cards (PASS/FLAG/FAIL/total), system-library status, recent reports, pending cert requests, live policy-violation feed |
| Test Runner | `testRunner.js` | Lists runs; "New Run" modal that creates a **throwaway** testConfig inline then launches |
| Results Browser | `resultsBrowser.js` | Per-run result table + single-result statistical summary |
| Report Viewer | `reportViewer.js` | Report list + detail (sections, cert block), Export PDF, Request Certification |
| Certification | `certificationView.js` | Queue, request detail (approve/deny), cert detail (verify) |
| Common | `iso42001Common.js` | Verdict/status badges, RBAC helper, buttons |

The REST client (`iso42001Client.js`) already wraps every backend endpoint including `generateReport`, which **no view currently calls**.

---

## 2. Backend capability inventory (so gaps are attributable)

`ISO42001ServiceFacade` + `ISO42001Service` expose:

- **testConfig**: generic CRUD via `/rest/model` (schema `access.roles`: create/update = `ISO42001Testers`, delete = `ISO42001Administrators`). This is the persistent **"campaign"** object.
- **run**: `POST /iso42001/run` → `runFromConfig` → `TestRunner.run(...)`. **Synchronous and blocking.** Status goes `PENDING → RUNNING → COMPLETED|FAILED` inside one request. **There is no cancel/stop endpoint and no async job handle.**
- **report**: `generateReport(name, type, runIds)` and `exportPdf` — both live, both wrapped in the client.
- **certificationRequest**: `requestCertification`, `approveRequest`, `denyRequest` — live. Delete only via generic `/rest/model` DELETE (Admins). Inherits `access.accessRequest` (message spool) but **no message-append endpoint is exposed** (acknowledged in `certificationView.js` header comment).
- **certification**: `verify` — live. **No `revoke` in the facade or service.**
- **analysisProfile**: model exists (`iso42001.analysisProfile`), referenced by testConfig as the "campaign-wide scoring profile." **No endpoint, no UI.**

---

## 3. Gap analysis

### 3.1 Campaigns — "start/stop a campaign" (the headline gap)

The design and the `analysisProfile` field description both use **"campaign"** to mean a **persisted `iso42001.testConfig`** (a named, reusable bundle of module + endpoint + tier + samples + α + scoring profile) that runs are launched against.

**Current reality:** `testRunner.js:launch()` builds a config named `run-<timestamp>`, `createConfig`s it, immediately `startRun`s it, and never surfaces it again. There is:
- ❌ No campaign list (you cannot see, reuse, or compare configs).
- ❌ No create/edit/**delete** of a persistent campaign.
- ❌ No "start" in the campaign sense — only a fire-and-forget run against a disposable config.
- ❌ No "stop" — no cancel button, **and no backend cancel endpoint**; the run is synchronous so there is nothing to stop from the client once it's issued.
- ❌ Advanced params the model supports (`temperature`, `alpha`, `promptConfigName`, `analysisProfile`) are absent from the new-run form.

**Attribution:** Campaign CRUD + list + launch = **pure UI** (backend ready). True "stop a running campaign" = **needs backend work** (async run + cancel endpoint); see §5.

### 3.2 Report generation — missing from the UI entirely

`reportViewer.js` list header literally says *"Generate a report from the Test Runner,"* but `testRunner.js` has **no Generate Report action**. Design §9A.5 specifies a `[Generate Report]` button on COMPLETED runs. The client method and backend both exist. **Pure-UI gap.** Today reports can only appear if created by tests/MCP/another path — an operator using the UI cannot produce one.

### 3.3 Certification request management

- ✅ Create (request) — exists in report detail.
- ❌ **Delete/withdraw** a request — not in the UI (backend supports Admin delete via generic model).
- ❌ **Choose a certifier** — `requestCert()` always passes `certifierId = null`; the `requestedCertifier` field and picker (design §9A.8) are unused.
- ❌ **Messages / discussion thread** — rendered **read-only**; no "Add message." Design §9A.8 shows a two-way thread. **Needs a backend message-append shim.**
- ⚠️ **Approve dialog** — uses `window.prompt` for a note only. Design §9A.8 specifies a proper signing dialog (title, **validity period**, notes, irreversibility warning). Validity period is not even sent today.

### 3.4 Certification revoke — fake button

`certificationView.js:155` renders a Revoke button that only `toast`s *"not yet wired in the shim UI."* Per the NO-LYING rule this dead control should either be wired or removed. **Backend has no revoke** — needs a facade+service method plus a cert `status → REVOKED` transition. See §5.

### 3.5 Results browser — thin vs. spec

Design §9A.6 specifies per-group breakdown (mean/stddev/refusal per demographic group), an embedded bar chart, and a raw-log JSON download. Current detail shows only test statistic / p-value / effect size / notes. Missing: per-group table, chart, raw-log download, and list filters/pagination. **Mostly pure-UI** (data is on the result records; confirm field availability).

### 3.6 Dashboard — no heat map / trend

Design §9A.4 specifies a Model × Protected-Class heat map and a trend. Current dashboard has summary cards only. The `/dashboard` endpoint returns just `{reportCount, verdicts}` — **heat map needs either a richer endpoint or client-side aggregation over results.** Partially backend.

### 3.7 Report section editing

Design §9A.7 has per-section `[Edit]` for Reporters while status is DRAFT/REVIEW. Sections render read-only today. Backend edit path = generic `/rest/model` PATCH on `iso42001.reportSection` (needs verification of section access roles). **Mostly pure-UI.**

### 3.8 Smaller correctness issues

- `certificationView.js:31-41` `loadRequest()` fetches the whole queue (up to 200) and `.find()`s the row because there's no single-request read exposed — brittle, and messages never load. A `GET /certification/request/{id}` shim would fix both this and §3.3 messages.
- Cancel button on RUNNING runs (design §9A.5) absent (ties to §3.1 stop).
- No pagination anywhere despite `startRecord/recordCount` support in the client `list()`.

---

## 4. Summary table

| # | Gap | Design ref | Attribution | Priority |
|---|---|---|---|---|
| 3.1a | Campaign (testConfig) list + create/edit/delete + persistent launch | §9A.5 | Pure UI | **P0** |
| 3.1b | Stop / cancel a running campaign | §9A.5 | **Backend + UI** | P1 |
| 3.2 | Generate Report action | §9A.5 | Pure UI | **P0** |
| 3.3a | Delete/withdraw cert request | §9A.8 | Pure UI | P1 |
| 3.3b | Certifier picker on request | §9A.8 | Pure UI | P2 |
| 3.3c | Cert-request message thread (append) | §9A.8 | **Backend + UI** | P1 |
| 3.3d | Proper Approve & Sign dialog (title/validity/notes) | §9A.8 | Pure UI | P1 |
| 3.4 | Revoke certification (remove fake button or wire it) | §9A.8 | **Backend + UI** | P1 |
| 3.5 | Results: per-group table, chart, raw-log download, filters | §9A.6 | Pure UI | P2 |
| 3.6 | Dashboard heat map + trend | §9A.4 | Backend + UI | P2 |
| 3.7 | Report section inline edit | §9A.7 | Pure UI | P2 |
| 3.8 | Single-request read; pagination; brittle loadRequest | §9A.8/10 | Backend shim + UI | P1 |

---

## 5. Backend additions required (call these out before UI work)

Per the standing DB rule, **no schema resets**; these are additive endpoints/fields only, and each should be confirmed with Stephen before building:

1. **`POST /iso42001/run/{id}/cancel`** + async execution. `TestRunner.run` is currently blocking. Real "stop" needs the run to execute on a background executor with a cancellable status flag (`RUNNING → CANCELLED`). *Larger change — may be deferred; interim UI can hide "stop" rather than fake it.*
2. **`POST /iso42001/certification/{id}/revoke`** → facade `revoke(user, certId, reason)` setting cert `status = REVOKED`. Small.
3. **`POST /iso42001/certification/request/{id}/message`** + **`GET /iso42001/certification/request/{id}`** — append to the inherited `access.accessRequest` message spool and read a single request with messages. Small; unblocks §3.3c and §3.8.
4. *(Optional, §3.6)* richer `/dashboard` payload (verdict-by-module-by-class matrix) if client-side aggregation over results proves too heavy.

### 5a. Backend backlog discovered during Phase 1 (group resolution)

Defects surfaced while wiring campaign management. Backend items — the UI has workarounds where possible:

- **B-TYPE-REGEX (FIXED in source, needs redeploy):** `ModelService` routed by-id `GET` / `/full` / `DELETE` through `@Path("/{type:[A-Za-z\.]+}/...")`. The `{type}` regex excluded digits, so every digit-bearing model type (`iso42001.*`, etc.) 404'd on those routes — which is why generic delete-by-objectId failed. Fixed to `[A-Za-z0-9\.]+` on all six `{type}` routes in `ModelService.java`. Requires a Service7 rebuild + redeploy to take effect. Delete-by-objectId (`page.deleteObject`) then works normally.

- **B-PATCH-ID:** `PATCH /rest/model` with a body of `{schema, objectId, <changed fields>}` (i.e. keyed by `objectId` alone, no numeric `id`/`groupId`) fails to resolve the record's group → `PolicyUtil "Group could not be found"` → `AccessPoint.update` returns null → `patchModel` responds `200 false` and nothing is saved. **Expected:** `objectId` alone should be sufficient to resolve the record for update. **UI workaround (campaignsView):** the editor loads the full record and sends the full identity set (`id/objectId/urn/groupId/organizationId`) with the patch. Remove the workaround once the backend resolves records by `objectId` alone.
- **B-RUN-GROUP:** at launch, an `iso42001.testRun` reaches policy authorization serialized as just `{ "schema": "iso42001.testRun" }` (no `groupId`) → `PolicyUtil "Group could not be found"`. The run should inherit the campaign's (`testConfig`) group at create time. This is in the run-creation path (`TestRunner` / `ISO42001ServiceFacade.runFromConfig`), not the UI. Needs backend investigation — a run with no group can't be authorized or later listed/scoped correctly.
- **B-CERTREQ-FOREIGN-ROLES (fixed in model, needs redeploy):** approve/deny/append on `iso42001.certificationRequest` were denied (`AUDIT DENY … MODIFY`) for a legitimate `ISO42001Certifiers` member. Cause: `certificationRequest` carries no group, so the dynamic auth checker can't use the group-only shortcut and falls to field-level role checks — and the foreign-model fields (`report`, `requestedCertifier`, `resultingCertification`) had no `update` roles, so the modify was denied. Fixed by adding field-level `access.roles` (read/update, + create where written) for the ISO roles on those foreign fields in `certificationRequestModel.json`. Requires Service7 redeploy and may require re-provisioning so the new field roles apply. (Lists were also org-scoped client-side per the same "no group → PBAC needs org context" reasoning.)

---

## 6. Implementation plan (phased)

Follow the standing Ux752 orders: **read the Ux7 reference for any list/decorator/form pattern before writing UI**, use `ensureSharedTestUser()` in tests, and **every claim of "done" must have a Playwright/Vitest test that exercises the live backend** (backend is live at `localhost:8443`).

### Phase 1 — Campaigns + Report generation (P0, pure UI)
- New `campaignsView.js` + routes `/iso42001/campaigns`, `/iso42001/campaigns/:configId`.
  - List `iso42001.testConfig` via `client.list`.
  - Create/Edit form covering **all** model fields (moduleId, testIds, endpointName, endpointType, tier, samplesPerGroup, temperature, alpha, randomSeed, chatConfigName, promptConfigName, analysisProfile). Reuse the existing formDef/decorator pattern from Ux7 — do not hand-roll if a form component exists.
  - Delete (Admin) via generic `/rest/model` DELETE.
  - "Launch Run" on a campaign → `startRun(configId)` → route to results.
- Refactor `testRunner.js` to launch **against a selected saved campaign** instead of minting a throwaway config; keep a "quick run" affordance if desired.
- Add `[Generate Report]` to COMPLETED runs (run detail and/or multi-select in the run list) → `generateReport(name, type, [runIds])` → route to the new report.
- Add menu items (`Campaigns`) to `features.js`.
- **Tests:** Playwright — create campaign → launch → run completes → generate report → report appears. Vitest for the form serialization (enums lowercase per API gotcha).

### Phase 2 — Certification lifecycle (P1)
- Backend shims (§5.2, §5.3): revoke, single-request read, message append. Confirm with Stephen first.
- Certifier picker in `requestCert` (search `system.user` with `ISO42001Certifiers`), pass real `certifierId`.
- Replace `window.prompt` approve with a proper **Approve & Sign dialog**: title, validity period, notes, irreversibility warning; send validity period.
- Wire **Revoke** to the new endpoint (or remove the fake button until §5.1/§5.2 land — no dead controls).
- Fix `loadRequest` to use the new single-request GET; render the **message thread with append**.
- Add **withdraw/delete** for requests (requester and Admin).
- **Tests:** Playwright — request (with certifier) → message round-trip → approve & sign → verify → revoke; deny path; withdraw path. Use the shared test user granted the relevant ISO roles, **not admin**.

### Phase 3 — Results & Report depth (P2)
- Results detail: per-group mean/stddev/refusal table, bar chart, raw-log JSON download; list filters + pagination.
- Report detail: inline section edit for Reporters when status is DRAFT/REVIEW (PATCH `iso42001.reportSection`).
- **Tests:** Playwright over a real completed run's results and a DRAFT report.

### Phase 4 — Dashboard heat map + true campaign stop (P2/P1-deferred)
- Heat map (Model × Protected Class) + trend — client-side aggregation first; escalate to a richer `/dashboard` endpoint only if needed.
- Async run + `/run/{id}/cancel` and a real Stop/Cancel control (§5.1). This is the only way "stop a campaign" becomes truthful; until it lands, do not present a stop button that can't stop anything.

---

## 7. Honest caveats

- **"Stop a campaign" cannot be delivered as pure UI.** Runs are synchronous server-side; a truthful Stop requires the Phase-4 backend async+cancel work. Everything else in the "campaign" request (list/create/manage/**delete**/launch/generate report) is pure UI and backend-ready today.
- **Revoke and message-append are backend-blocked**; the current Revoke button is a fake and violates the NO-LYING rule — it should be wired or removed in Phase 2.
- I have **not** run any of this yet — this is analysis against source, not tested behavior. The plan's test steps are the point at which each claim gets verified against the live backend.
