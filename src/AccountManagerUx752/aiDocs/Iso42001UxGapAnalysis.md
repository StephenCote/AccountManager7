# ISO 42001 Ux Gap Analysis & Status (Ux752)

**Status refreshed:** 2026-10-07 (previous refresh 2026-09-01; original analysis 2026-07-01 — see "History" below).
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

## 2. Coverage table (as of 2026-10-07)

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
| Approve & sign | **yes** (2026-10-07) | `certificationView.js:86-103`; `iso42001Client.js` `approve(id, note, terms)` | Dialog sends `title` / `validityMonths` (integer, 1–60) / `notes`; server stamps `certifierTitle` / `expiryDate` / `notes` and 400s on an invalid period. Vitest `src/test/iso42001ApproveTerms.test.js`; JUnit `TestISO42001CertificationRequest`. Live 200 path on the Docker stack is blocked by the bcprov skew in §6 |
| Stop/cancel a running run | **no** | — | Genuine backend gap: no cancel endpoint (ISO42001Service.java:123-155); runs synchronous |
| `analysisProfile` (scoring profile) | **yes** | `GET /rest/iso42001/profiles` (`ISO42001Service.java:127-135`); picker `campaignsView.js:375-387`, FK attached by objectId (`:182-183`) | Profile **CRUD** UI still absent — the picker lists existing `iso42001.analysisProfile` records only |
| Results: per-group table / chart / raw-log download | **no** | `resultsBrowser.js:69-87` | §9A.6 depth unbuilt |
| Dashboard heat map / trend | **no** | `dashboard.js:62-75` | Cards only; §9A.4 unbuilt |
| Report section inline edit | **no** | read-only `reportViewer.js:147-157` | §9A.7 unbuilt |
| List pagination | **no** | fixed ranges, e.g. `iso42001Client.js:73` | `startRecord/recordCount` supported but hardcoded 0..50/100 |

## 3. Remaining true gaps (the current backlog)

Pure-UI unless noted:

1. **`analysisProfile` management** — ~~needs a backend endpoint + a picker~~ **picker + list endpoint
   done** (§2). Still open: a CRUD surface for `iso42001.analysisProfile` itself (today profiles are
   created outside the UI, e.g. via the generic `/rest/model` routes).
2. **Results depth** — per-group mean/stddev/refusal table, bar chart, raw-log JSON download, filters.
3. **Dashboard heat map + trend** — client-side aggregation first; escalate to a richer `/dashboard`
   payload only if needed (**backend + UI**).
4. **Report section inline edit** — PATCH `iso42001.reportSection` for Reporters while DRAFT/REVIEW
   (verify section access roles).
5. **List pagination** across all ISO views (client already supports `startRecord/recordCount`).
6. ~~**Approve & Sign validity-period input**~~ **Done 2026-10-07** — see §2 "Approve & sign".
7. **Stop/cancel a running run** — **backend-blocked** (see §4.1); runs are synchronous. Do not present
   a stop button that can't stop anything until the async+cancel work lands.
8. ~~**Cosmetic:** `reportViewer.js:213`~~ **Done 2026-10-07** — now reads "Generate a report from a
   campaign or from a run's results".

## 4. Backend items (from the ISO 42001 backend status review, 2026-09-01; re-checked 2026-10-07)

These sit behind the UX gaps or are correctness issues found in the engine:

1. **No run cancel endpoint; runs are synchronous** (`ISO42001Service.java:123-155`). Real "stop"
   needs background execution + a cancellable status flag. Larger change; may stay deferred.
2. ~~**`tier=0` ("both") silently runs Tier 1 only**~~ **Implemented** — `TestRunner.java:71-85` runs
   Tier 1 *and* Tier 2 for `tier=0`, embedding one `testResult` per tier and aggregating the rollup
   (class javadoc `:36-38`). Covered by `TestISO42001BiasAzureBothTiers` /
   `BiasModuleTestBase.runAndAssertBothTiers` (live-LLM, gated).
3. ~~**`controlAreas` hardcoded**~~ **Implemented** — `ReportGenerator.deriveControlAreas(data, runs)`
   (`:158`, `:212`) derives the Annex A areas from the modules actually exercised. Unit test
   `TestReportControlAreas`.
4. **Reserved (unwired) statistics/scoring code — tracked so it stays discoverable.**
   `StatisticalAnalyzer.kruskalWallis` / `fisherExactTwoSided` (`:68,164`) and the swap-test A3 path
   `SwapTestRunner` / `SwapPair` / `SwapDimension` are implemented and unit-tested but have **no
   caller** — reserved-but-unwired, marked only by javadoc in the source today. **Intended future
   use:** `kruskalWallis` / `fisherExactTwoSided` provide **non-parametric significance testing** for
   scoring; the `Swap*` classes provide a **swap-based bias A3 path**. Decide when picking this up: wire
   into the run pipeline or keep as an intentional reserve. Do **not** delete the code or its javadoc
   meanwhile — this entry exists so a future implementer can find them.
5. **Cross-model aggregation** — exists **at report level**: `ReportGenerator.aggregate(List<testRun>)`
   accepts any number of runs (one per model/config), tags each result row with its run's model and
   surfaces `modelsEvaluated` in the summary (`TestExecutor.java:22-26` javadoc corrected 2026-10-07 —
   `TestExecutor` never aggregates by design). What is still missing is the **per-model heat-map
   view** in the UI (§3.3), not the backend aggregate.
6. **Revocation is a status flag only** (no CRL) — acceptable for internal use; note it. Since
   2026-10-07 `revokeCertification` is explicitly gated to `ISO42001Administrators`
   (`ISO42001CertificationFactory.java:299-316`) because PBAC's owner shortcut had let the signing
   certifier revoke its own certification (caught by `TestISO42001Lifecycle`).
7. **REST auth is coarse** `@RolesAllowed({"user","admin"})` on every endpoint; fine-grained ISO RBAC is
   enforced downstream via model `access.roles`/PBAC. Worth a security pass, not a defect.

## 5. Backend backlog discovered during original Phase 1 (group resolution) — preserved

Defects surfaced while wiring campaign management. Statuses updated where known:

- **B-TYPE-REGEX (FIXED, verified live 2026-10-07):** `ModelService` by-id `GET`/`/full`/`DELETE`
  routed through `@Path("/{type:[A-Za-z\.]+}/...")`, whose `{type}` regex excluded digits, so
  digit-bearing types (`iso42001.*`) 404'd. Fixed to `[A-Za-z0-9\.]+`. Verified on the Docker stack as
  `e2etest_iso42001`: `GET /rest/model/iso42001.testConfig/{objectId}` → 200.
- **B-PATCH-ID (not reproducible, 2026-10-07):** `PATCH /rest/model` with **only**
  `{schema, objectId, description}` against an `iso42001.testConfig` returned `200 true` and the new
  description read back (fresh `cache:false` search) on the Docker stack as `e2etest_iso42001`; the app
  log shows `AUDIT PERMIT … MODIFY iso42001.testConfig` and no `Group could not be found` for the
  PATCH. The `campaignsView` full-identity workaround is harmless and can stay or go; nothing in the
  backend needs changing for this item.
- **B-RUN-GROUP (resolved on the ISO side; residual log noise is generic):** `TestRunner.run` copies
  `groupId` / `organizationId` / `ownerId` from the campaign onto the run at create time
  (`TestRunner.java:87-98`); live runs carry the campaign's `groupId` (verified 2026-10-07). The
  `PolicyUtil "Group could not be found"` + `{ "schema": "iso42001.testRun" }` ERROR still appears in
  the log — but it comes from `AccessPoint.list → authorizeQuery → PolicyUtil.getResourcePolicy` on
  any **org-wide list query without a `groupId` condition**, for *every* `data.directory`-derived
  model, and the query is then `AUDIT PERMIT`ted via the field/role path. It is an Objects7
  logging-level issue (core lane), not an ISO defect, and it does not deny. Clients that want a quiet
  log add a `groupId` condition to list queries.
- **B-CERTREQ-FOREIGN-ROLES (fixed; verified by JUnit 2026-10-07):** approve/deny/append on
  `iso42001.certificationRequest` denied for a legitimate `ISO42001Certifiers` member. The field-level
  `access.roles` on `report` / `requestedCertifier` / `resultingCertification` were part of it, but the
  live root causes were three: (1) `approveRequest`/`denyRequest` built their update record with the
  bare `RecordFactory.newInstance(model)` — which materialises **every** field — so the "minimal" update
  blanked unset columns; now `minimalUpdate` uses the field-name overload. (2) PBAC's role check
  (`MemberUtil.isMember(actor, role)`) is **direct membership + the role's `parentId` chain** — it never
  unwinds role→role nesting — so `ISO42001Certifiers ⊂ Approvers` granted nothing;
  `ISO42001Provisioning.syncApproverEntitlements` now enrols Certifiers/Administrators members directly
  into the system `Approvers` / `RequestUpdaters` roles (re-run per org at Service7 boot). (3) The
  request must transition to APPROVE *before* signing (the inherited `approvalStatus` rule gates the
  write); since 2026-10-07 a null/throwing signer reverts the request to REQUEST with an audit message
  (`TestISO42001ApproveRevert`). Covered by `TestISO42001CertificationRequest` and `TestISO42001Lifecycle`.

## 6. Honest caveats

- **"Stop a campaign" is still not deliverable as pure UI** — runs are synchronous server-side; a
  truthful Stop requires the async+cancel backend work (§4.1).
- **Approve & Sign returns HTTP 500 on the current Docker image** (measured 2026-10-07 as
  `e2etest_iso42001` at `https://127.0.0.1:9443`). Not an ISO defect: `src/Dockerfile` (`PREBUILT=1`
  branch) stages `src/docker/bcprov-jdk18on-1.76.jar` into `WEB-INF/lib` beside the WAR's
  `bcpkix-jdk18on-1.80.jar`, and the certifier's first keystore generation dies with
  `NoSuchFieldError: BCObjectIdentifiers.xmss_SHAKE128_512ph` (`JcaContentSignerBuilder.<clinit>` ←
  `CertificateUtil.generate` ← `KeyStoreUtil.getCreateStore` ← `resolveCertifierKeyStore`). The
  JUnit path is green because Maven's classpath is a consistent BC 1.80. **Dockerfile fixed later the
  same day** (`src/docker/bcprov-jdk18on-1.80.jar` staged, 1.76 removed, `PREBUILT=1` branch updated); a
  container built from the old image still carries 1.76 until rebuilt. Until the rebuild the 400
  validation path is verifiable live (61 / `"abc"` → 400, request untouched) but the 200 path is not.
  `TestISO42001Service` now covers the transport check (61 / -1 / `"twelve"` / 12.5 → 400 before the
  facade; 0 / 36 / absent reach it) — 12.5 had been silently truncated to 12 by `canConvertToInt()`
  until the test caught it. Since the
  2026-10-07 revert fix (ISO jar redeployed to the Docker stack the same day) a failed signing no
  longer strands the request: measured live, approve → 500, then
  `GET /certification/request/{id}` → `approvalStatus=request`, 3 messages (the last one "Signing
  failed (NoSuchFieldError); request returned to REQUEST"), no certification. The one request this
  had stranded earlier (`44d70bbd-…`, `/Development`) was reset to REQUEST by PATCH.
- The 2026-09-01 refresh was source review only. The 2026-10-07 refresh verified the §2 rows marked
  "2026-10-07" and the §5 items against the live Docker backend as the ISO test user
  (`ensureIso42001TestUser()` — never admin) or by the named JUnit/Vitest tests; everything else
  remains source review.

---

## History

The **2026-07-01** original framed the state as "six views, throwaway config" with campaign CRUD and
Generate Report as outstanding P0s, and revoke / message-append / single-request-read as
backend-blocked. All of those are now built on both tiers. The original's Phase 1–2 are essentially
complete; its Phase 3–4 items are the §3 backlog above. See git history for the original text.
