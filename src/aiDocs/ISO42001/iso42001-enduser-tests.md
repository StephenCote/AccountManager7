# ISO 42001 — End-User Test Checklist (Phase 7/8 UAT)

Manual walkthrough for Stephen. Log issues inline on each test's **Issue:** line (leave blank if it passes). These cover the Ux752 ISO 42001 feature views + the role-gated REST/MCP flows behind them.

## Environment / accounts
- **App:** Vite dev server (https://localhost:8899) → Service7 on Tomcat. ISO feature is in the `full`, `enterprise`, and `compliance` profiles.
- **ISO-role user (all working roles):** `e2etest_iso42001` / `password`, org `/Development` — member of ISO42001 Testers/Reporters/Certifiers/Administrators (provisioned by the e2e helper; persistent).
- **No-ISO-role user (negative checks):** `e2etest_shared` / `password`, org `/Development`.
- **Live LLM endpoint** for actual test runs: DGX Spark `192.168.1.42` (beelink is gone). A run with an unreachable endpoint records verdict `ERROR` (by design) rather than failing.

> Note: to exercise per-role gating precisely (e.g. "Reporter cannot launch a run"), create single-role users via the Features/Access-Requests UI or reuse the provisioning helper with a specific `roles` list. `e2etest_iso42001` has all roles, so it sees every control.

---

## A. Feature availability & profile carve-out

**A1. Feature appears for an ISO user** — Role: any ISO
Log in as `e2etest_iso42001`. Confirm a **Compliance** item (policy icon) appears in the left/aside menu.
Issue:

**A2. Dashboard route loads** — Role: any ISO
Click **Compliance** (or go to `#!/compliance`). The **ISO 42001 Compliance Dashboard** renders.
Issue:

**A3. compliance profile carve-out** — Admin
In **Features** (admin) click the **Compliance** quick profile, save. Confirm only Core/Chat/Compliance/Access Requests/Feature Config remain; card game, games, media, schema, etc. are hidden. (Re-select your normal profile afterward.)
Issue:

**A4. Feature hidden without it** — any
With a profile that excludes `iso42001` (e.g. Standard), confirm the **Compliance** menu item is absent.
Issue:

---

## B. Dashboard (`/compliance`)

**B1. Summary cards** — any ISO
Confirm PASS / FLAG / FAIL / Reports cards show counts (0s are fine on a fresh system).
Issue:

**B2. System Status badges** — any ISO
Chat/Prompt/Template/Policy library badges render (green check / grey based on backend state).
Issue:

**B3. Overcorrection banner** — any ISO
The "Training Bias Overcorrection Active" banner is present.
Issue:

**B4. Recent Reports list** — any ISO
Recent reports list shows (empty state OK); clicking a report navigates to its detail.
Issue:

**B5. Pending Certification Requests (certifier-only)** — Certifier/Admin vs others
As `e2etest_iso42001` the "Pending Certification Requests" section appears. As `e2etest_shared` (after granting only a reader role) or a non-certifier, it must NOT appear.
Issue:

**B6. Test Runner button (tester-only)** — Tester/Admin vs others
The **Test Runner** button appears for `e2etest_iso42001`. A non-tester user should not see it.
Issue:

**B7. Refresh** — any ISO
Clicking **Refresh** reloads the dashboard data without error.
Issue:

**B8. Policy violations monitor** — any ISO
The Policy Violations panel renders; the filter input works. (Live entries only appear when policy events fire during a chat.)
Issue:

---

## C. Test Runner (`/iso42001/run`)

**C1. Run list** — Tester/Admin
The Test Runs table lists existing runs (empty OK), with Run / Endpoint / Status / P/Fl/F columns.
Issue:

**C2. New Run modal** — Tester/Admin
Click **New Run**. The modal shows Module, LLM Endpoint, Tier, Samples/Group, Random Seed.
Issue:

**C3. Launch a run (live LLM)** — Tester
Set Endpoint to a configured chat config name pointing at DGX Spark, small Samples/Group (e.g. 2), Launch. It should create the config + run and navigate to the results view. (May take time; an unreachable endpoint → ERROR verdict, still persists.)
Issue:

**C4. Non-tester is read-only** — non-Tester
As a user without the Tester role, the **New Run** button is hidden and a notice says runs are read-only. Attempting create via the API returns 403 (already covered by automated tests).
Issue:

---

## D. Results Browser (`/iso42001/results/:runId`)

**D1. Results list** — any ISO
From a completed run, the results table shows Test ID / Class / Verdict / Effect / p(corr.).
Issue:

**D2. Result detail** — any ISO
Click a result row. Detail shows the statistical summary (test statistic, p raw→corrected, effect size) and notes.
Issue:

**D3. Empty/unknown run** — any ISO
Navigating to a run with no results shows a graceful "No results for this run." (no crash).
Issue:

---

## E. Report Viewer (`/iso42001/report`)

**E1. Report list** — Reader/Reporter/Certifier/Admin
The Compliance Reports table lists reports with verdict + status.
Issue:

**E2. Report detail sections** — any ISO read
Open a report. Ordered sections (Executive Summary, Methodology, Results, Mitigation) render; the Certification block shows CERTIFIED (green) or NOT CERTIFIED.
Issue:

**E3. Export PDF (reporter)** — Reporter/Admin
Click **Export PDF**. A PDF is generated and opens in a new tab (`%PDF`, charts, cert/NOT-CERTIFIED block).
Issue:

**E4. Request Certification (reporter, uncertified only)** — Reporter
**Request Certification** appears only for a Reporter on an uncertified report; entering a justification creates a request and navigates to it. Button is hidden once certified.
Issue:

**E5. Export button hidden for non-reporter** — Reader
A Reader (no Reporter role) does not see Export/Request buttons.
Issue:

---

## F. Certification (`/iso42001/cert`)

**F1. Queue** — Certifier/Admin
The Certification Queue lists requests with status.
Issue:

**F2. Request detail** — Reporter/Certifier/Admin
Open a request. Justification + (read-only) message thread render.
Issue:

**F3. Approve & Sign (certifier)** — Certifier/Admin
On a REQUEST-status request, **Approve & Sign** prompts for a signing note, creates+signs the certification, and navigates to the certification detail. Hidden when status ≠ REQUEST.
Issue:

**F4. Deny (certifier)** — Certifier/Admin
**Deny** prompts for a reason and marks the request denied.
Issue:

**F5. Certification detail + Verify** — any ISO
Open a certification. **Verify Now** returns a per-check result (status/cert/notExpired/hash/signature) and an overall valid/invalid badge.
Issue:

**F6. Revoke (admin only)** — Admin vs others
The **Revoke** control appears only for an Administrator on a VALID certification. (Revoke is currently surfaced as an admin server action notice in the UI.)
Issue:

**F7. Non-certifier cannot approve** — non-Certifier
A user without Certifier/Admin does not see Approve/Deny on a request (and the API rejects it).
Issue:

---

## G. RBAC swap checks (negative)

**G1. No-role user** — `e2etest_shared`
Logged in as the no-ISO-role user: dashboard still renders (read), but creating a test config / launching a run / approving a cert are all denied (no buttons and/or server 403).
Issue:

**G2. Reader is read-only** — Reader
A Reader can view dashboard/results/reports/certifications but sees no create/run/export/approve/revoke controls.
Issue:

---

## H. MCP (optional, for agent/audit-tool users)

**H1. Tools listed** — any ISO (via MCP client)
An MCP `tools/list` includes `iso42001_run_test`, `iso42001_test_status`, `iso42001_report_summary`, `iso42001_certify` alongside the `am7_*` tools. (Automated test covers this.)
Issue:

**H2. Resource read** — any ISO (via MCP client)
Reading `am7://{org}/iso42001/report/{objectId}` returns the report JSON (RBAC-gated). 
Issue:

---

## Known limitations (not bugs — by design this phase)
- Heat-map / charts on the dashboard are simplified to summary cards (richer charts deferred).
- Certification message thread is **read-only** in the UI (message-append is not in the Phase-7 REST shim yet).
- Revoke is an admin server action; the UI currently shows a notice rather than a full revoke dialog.
- A run against an unreachable LLM endpoint records verdict `ERROR` (intended), not a failure.
