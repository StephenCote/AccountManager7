# ISO 42001 Ux Gap Analysis & Status (Ux752)

**Status refreshed:** 2026-09-01 (original analysis 2026-07-01 — see "History" below).
**Scope:** `AccountManagerUx752/src/features/iso42001/` vs. the design spec
(`aiDocs/ISO42001/iso42001-design.md` §9A) and the live backend (`AccountManagerISO42001` engine +
`ISO42001Service` REST shim + `ISO42001ServiceFacade`).
**Method:** Source review of every feature file, the REST service, the facade, and the model schemas
(read-only; not runtime-tested — the test steps below are where each claim gets verified).

> **The 2026-07-01 version of this doc is stale and overstated what was outstanding.** Its headline P0
> gaps (campaign CRUD, Generate Report) and most P1 certification-lifecycle items are **now built and
> wired to real endpoints**. This refresh records current reality; the original phased plan and the
> backend-defect backlog (still valuable) are preserved and marked below.

---

## 1. Current UX surfaces — `src/features/iso42001/`

Seven files, routed in `routes.js:21-34`, menu-wired in `features.js:64-68` (Compliance, ISO
Campaigns, ISO Test Runs, ISO Reports, ISO Certifications; gated on `iso42001Any`). RBAC via
`iso42001Common.js:9-20`.

- **Dashboard** (`dashboard.js`) — verdict summary cards, system-library status, recent reports,
  pending cert requests, live policy-violation feed.
- **Campaigns** (`campaignsView.js`, **added since the original doc**) — list, create/edit modal
  covering the config knobs, save, delete, launch, per-campaign run list, generate-report-from-run.
- **Test Runner** (`testRunner.js`) — run list; "New Run" launches against a **saved** campaign
  (no longer mints a throwaway config).
- **Results** (`resultsBrowser.js`) — run result table, single-result stat summary, generate report.
- **Reports** (`reportViewer.js`) — list, detail with sections + cert block, export PDF, request cert
  with certifier search/pick.
- **Certification** (`certificationView.js`) — queue, single-request read, message thread + append,
  approve & sign, deny, delete request, verify, **revoke** (now real).

## 2. Coverage table (as of 2026-09-01)

| Backend capability | UX entry point | Evidence | Notes |
|---|---|---|---|
| testConfig (campaign) CRUD | **yes** | `campaignsView.js:159,206,274` | Full form incl. tier/samples/α/temp/seed |
| Launch run vs. saved campaign | **yes** | `campaignsView.js:223`; `testRunner.js:39` | |
| List / view runs | **yes** | `testRunner.js:18`; `resultsBrowser.js:35` | |
| View run results | **yes** | `resultsBrowser.js:54,69` | |
| Generate report | **yes** | `campaignsView.js:240`; `resultsBrowser.js:16` | Original "P0 missing" — built |
| View report + sections | **yes** | `reportViewer.js:147,171` | |
| Export/download PDF | **yes** | `reportViewer.js:35,41` | |
| Request certification + certifier picker | **yes** | `reportViewer.js:53-70` | passes real `certifierId` |
| Cert request queue + single read | **yes** | `certificationView.js:45,55` | `getRequest` (ISO42001Service.java:319) |
| Message thread append | **yes** | `certificationView.js:136,234` | facade `appendMessage` (:188) |
| Deny / delete request | **yes** | `certificationView.js:97,116` | |
| Verify certification | **yes** | `certificationView.js:177` | |
| Revoke certification | **yes** | `certificationView.js:152` | `/certification/{id}/revoke` (:357), facade (:195) — original "fake button" now real |
| Approve & sign | **partial** | `certificationView.js:69,81` | validity period is **static "1 year" text**, not an input; approve sends only title/note |
| Stop/cancel a running run | **no** | — | Genuine backend gap: no cancel endpoint (ISO42001Service.java:123-155); runs synchronous |
| `analysisProfile` (scoring profile) | **no** | form omits it (`campaignsView.js:46-53`) | Model field exists; no endpoint, no picker |
| Results: per-group table / chart / raw-log download | **no** | `resultsBrowser.js:69-87` | §9A.6 depth unbuilt |
| Dashboard heat map / trend | **no** | `dashboard.js:62-75` | Cards only; §9A.4 unbuilt |
| Report section inline edit | **no** | read-only `reportViewer.js:147-157` | §9A.7 unbuilt |
| List pagination | **no** | fixed ranges, e.g. `iso42001Client.js:73` | `startRecord/recordCount` supported but hardcoded 0..50/100 |

## 3. Remaining true gaps (the current backlog)

Pure-UI unless noted:

1. **`analysisProfile` management** — needs a **backend endpoint** + a picker/CRUD surface.
2. **Results depth** — per-group mean/stddev/refusal table, bar chart, raw-log JSON download, filters.
3. **Dashboard heat map + trend** — client-side aggregation first; escalate to a richer `/dashboard`
   payload only if needed (**backend + UI**).
4. **Report section inline edit** — PATCH `iso42001.reportSection` for Reporters while DRAFT/REVIEW
   (verify section access roles).
5. **List pagination** across all ISO views (client already supports `startRecord/recordCount`).
6. **Approve & Sign validity-period input** — currently static "1 year" text; the certifier can't set
   a period and it isn't sent.
7. **Stop/cancel a running run** — **backend-blocked** (see §4.1); runs are synchronous. Do not present
   a stop button that can't stop anything until the async+cancel work lands.
8. **Cosmetic:** `reportViewer.js:213` still says "Generate a report from the Test Runner" though
   generation now lives on the results/campaign views.

## 4. Backend items (from the ISO 42001 backend status review, 2026-09-01)

These sit behind the UX gaps or are correctness issues found in the engine:

1. **No run cancel endpoint; runs are synchronous** (`ISO42001Service.java:123-155`). Real "stop"
   needs background execution + a cancellable status flag. Larger change; may stay deferred.
2. **`tier=0` ("both") silently runs Tier 1 only** (`TestRunner.java:64`). Correctness gap — implement
   "both" or reject `tier=0` with a clear error.
3. **`controlAreas` hardcoded** to A.5.4/A.5.5 (`ReportGenerator.java:128`). Report/Annex-A completeness.
4. **Reserved (unwired) statistics/scoring code — tracked so it stays discoverable.**
   `StatisticalAnalyzer.kruskalWallis` / `fisherExactTwoSided` (`:68,164`) and the swap-test A3 path
   `SwapTestRunner` / `SwapPair` / `SwapDimension` are implemented and unit-tested but have **no
   caller** — reserved-but-unwired, marked only by javadoc in the source today. **Intended future
   use:** `kruskalWallis` / `fisherExactTwoSided` provide **non-parametric significance testing** for
   scoring; the `Swap*` classes provide a **swap-based bias A3 path**. Decide when picking this up: wire
   into the run pipeline or keep as an intentional reserve. Do **not** delete the code or its javadoc
   meanwhile — this entry exists so a future implementer can find them.
5. **Cross-model aggregation** (multi-model heat-map) documented-but-missing ("Phase 5",
   `TestExecutor.java:22-23`).
6. **Revocation is a status flag only** (no CRL) — acceptable for internal use; note it.
7. **REST auth is coarse** `@RolesAllowed({"user","admin"})` on every endpoint; fine-grained ISO RBAC is
   enforced downstream via model `access.roles`/PBAC. Worth a security pass, not a defect.

## 5. Backend backlog discovered during original Phase 1 (group resolution) — preserved

Defects surfaced while wiring campaign management. Statuses updated where known:

- **B-TYPE-REGEX (FIXED in source):** `ModelService` by-id `GET`/`/full`/`DELETE` routed through
  `@Path("/{type:[A-Za-z\.]+}/...")`, whose `{type}` regex excluded digits, so digit-bearing types
  (`iso42001.*`) 404'd. Fixed to `[A-Za-z0-9\.]+`. Requires Service7 redeploy to take effect.
- **B-PATCH-ID:** `PATCH /rest/model` keyed by `objectId` alone (no numeric `id`/`groupId`) fails to
  resolve the record's group → `PolicyUtil "Group could not be found"` → silent `200 false`. UI
  workaround (`campaignsView`): send the full identity set (`id/objectId/urn/groupId/organizationId`).
  Remove the workaround once the backend resolves by `objectId` alone.
- **B-RUN-GROUP:** at launch, an `iso42001.testRun` reaches authorization serialized as just
  `{ "schema": "iso42001.testRun" }` (no `groupId`) → "Group could not be found". Runs should inherit
  the campaign's group at create time (`TestRunner` / `ISO42001ServiceFacade.runFromConfig`). Backend
  investigation needed.
- **B-CERTREQ-FOREIGN-ROLES (fixed in model, needs redeploy):** approve/deny/append on
  `iso42001.certificationRequest` denied for a legitimate `ISO42001Certifiers` member because the
  groupless record falls to field-level role checks and the foreign fields (`report`,
  `requestedCertifier`, `resultingCertification`) had no `update` roles. Fixed by adding field-level
  `access.roles` on those foreign fields. Requires Service7 redeploy and possibly re-provisioning.

## 6. Honest caveats

- **"Stop a campaign" is still not deliverable as pure UI** — runs are synchronous server-side; a
  truthful Stop requires the async+cancel backend work (§4.1).
- This refresh is **source review, not tested behavior.** Each remaining-gap item gets verified against
  the live backend (`localhost:8443`, `ensureSharedTestUser()` — never admin) when built.

---

## History

The **2026-07-01** original framed the state as "six views, throwaway config" with campaign CRUD and
Generate Report as outstanding P0s, and revoke / message-append / single-request-read as
backend-blocked. All of those are now built on both tiers. The original's Phase 1–2 are essentially
complete; its Phase 3–4 items are the §3 backlog above. See git history for the original text.
