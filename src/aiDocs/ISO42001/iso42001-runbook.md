# ISO 42001 — Operator / Analyst Runbook

A step-by-step playbook for using the ISO 42001 compliance feature **in the running app**: enable it,
configure it, create test campaigns, run them, read the results, generate and export a report, and
route it through certification — plus how to customize the rules (and what is only changeable by an
engineer with deploy access).

**Audience:** an operator/analyst driving the Ux752 web UI at the deployed URL (dev stack:
`https://localhost:8899` → Service7; the ISO feature is in the `full`, `enterprise`, and `compliance`
profiles). **This is a usage guide, not a design doc.**

- Concepts, clause/control coverage, statistical framework: [`iso42001.md`](iso42001.md)
- Authoritative build design (engine, scoring, reporting, certification, facade): [`iso42001-design.md`](iso42001-design.md)
- Bias test methodology & module specs: [`iso42001-bias.md`](iso42001-bias.md)
- Manual UAT checklist (what to click, screen by screen): [`iso42001-enduser-tests.md`](iso42001-enduser-tests.md)
- UX gap analysis & backend backlog (what is / isn't built): [`../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`](../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md)

> **Routes are hash routes.** Every route below is a Mithril hash route — prefix it with `#!` in the
> address bar (e.g. `#!/compliance`). "Endpoints" like `POST /rest/iso42001/run` are the REST calls the
> UI makes for you; you don't type those unless a step says so.

---

## Phase 0 — Prerequisites & enabling the feature

Two things must be true before any ISO menu item appears. Both are admin actions.

1. **The `iso42001` UX feature must be enabled for the organization.** ISO 42001 is one of the app's
   toggleable UX features (feature id `iso42001`). There are two ways to turn it on:
   - **At first-run setup:** the Setup wizard's **Features** step (core route `#!/setup`) picks the
     starting feature set — choose a profile that includes ISO 42001: `full`, `enterprise`, or the
     ISO-only **`compliance`** ("ISO 42001 only") profile.
   - **After setup:** an admin toggles it any time from the **Features** admin panel at
     `#!/admin/features` — either flip `iso42001` on directly, or click a Quick Profile such as
     `compliance`, `enterprise`, or `full`. Changes take effect immediately (no page reload).

   If the feature is off, the ISO menu items are absent for everyone regardless of role.
2. **The user must hold an ISO role.** The menu items are gated on having *any* ISO role (see Phase 1).
   A user with the feature enabled but no ISO role sees no ISO menu items.

You will also need at least one **LLM endpoint** for campaigns to target. Endpoints are
`chatConfig` / `system.connection` records managed **elsewhere in the app** — the generic list view at
`#!/list/system.connection` — **not** in the ISO views. Note the endpoint's name; you type it into the
campaign form later.

> Both the admin feature-toggle panel (`#!/admin/features`) and the first-run "ISO 42001 only"
> (`compliance`) deployment profile are now built. For the feature-flag system's design, dependency
> rules, and the full profile catalogue see [`../UxFeatureFlagDesign.md`](../UxFeatureFlagDesign.md).

---

## Phase 1 — Roles & access

ISO access is by membership in one of six system role groups. The client shows/hides controls by role;
**the server is the real boundary** (`@RolesAllowed` on `ISO42001Service` plus per-model `access.roles`
PBAC). Client gating is UX-only — a hidden button does not mean the server would allow the action, and
vice versa.

| System role group | Can do | Read-only areas |
|---|---|---|
| `ISO42001Testers` | Create/edit campaigns, launch runs | reports, certs |
| `ISO42001Reporters` | Generate reports, export PDF, request certification | dashboard, results |
| `ISO42001Certifiers` | Approve & sign / deny certification requests, verify | dashboard, reports |
| `ISO42001Readers` | View everything ISO | all (no create/run/export/approve) |
| `ISO42001Auditors` | View everything ISO (audit stance) | all (no create/run/export/approve) |
| `ISO42001Administrators` | All of the above + delete campaigns + revoke certifications | — |

Notes:
- The platform **admin** role also satisfies every ISO check.
- Role names are matched **case-insensitively** by the client (`iso42001testers`, etc.), but the
  canonical group names are as spelled above.
- **Being placed in the right role group is step one for an operator.** If you can't see a control this
  runbook says you should, check your role first, then confirm the feature is enabled.

---

## Phase 2 — Navigation map

With the feature enabled and an ISO role held, five items appear in the left drawer (the "aside" menu)
under the **Features** heading:

| Menu label | Route | Screen |
|---|---|---|
| Compliance | `#!/compliance` | ISO 42001 Compliance Dashboard (also reachable at `#!/iso42001`) |
| ISO Campaigns | `#!/iso42001/campaigns` | Campaign list / create / edit / launch |
| ISO Test Runs | `#!/iso42001/run` | Run list + launch a run against a saved campaign |
| ISO Reports | `#!/iso42001/report` | Report list / detail / export PDF / request certification |
| ISO Certifications | `#!/iso42001/cert` | Certification queue, request threads, certificates |

---

## Phase 3 — The dashboard (`#!/compliance`)

Click **Compliance**. The **ISO 42001 Compliance Dashboard** renders (data from
`GET /rest/iso42001/dashboard`):

- **Summary cards:** PASS / FLAG / FAIL counts + total Reports (0s are normal on a fresh system).
- **System Status badges:** Chat / Prompt / Template / Policy library readiness (green check = ready).
- **"Training Bias Overcorrection Active" banner** — an informational status banner.
- **Recent Reports** — click one to open its detail.
- **Pending Certification Requests** — shown only to Certifier/Admin; click to open the request.
- **Policy Violations** — a **live, session-scoped** monitor (with a filter box) that fills in only when
  policy events fire during a chat this session; it is not a historical query.
- **Refresh** reloads the dashboard; **Test Runner** (Tester/Admin only) jumps to `#!/iso42001/run`.

---

## Phase 4 — Creating campaigns

A **campaign is a persisted `iso42001.testConfig`** — a reusable bundle of module + endpoint + tier +
sample size + statistical knobs. You launch runs against it. Campaigns are stored under your
`~/ISO42001` group.

**Where:** `#!/iso42001/campaigns` → **New Campaign** (button visible to Tester/Admin). This opens an
editor modal.

### Field reference

| Field | Required | Default | Notes |
|---|---|---|---|
| Name | **yes** | — | Any label; also the validated field on save. |
| Description | no | — | Free text. |
| Test Module | no | `BIAS` | Select; options from `GET /rest/iso42001/modules`. `BIAS` is the only mapped module today. |
| Endpoint Type | no | `ollama` | One of `ollama` / `openai` / `anthropic` / `azure`. |
| LLM Endpoint (chatConfig name) | **yes** | — | Autocompletes from `GET /rest/iso42001/endpoints`. Must name a real endpoint (Phase 0). |
| Test IDs | no | empty | Comma-separated. **Empty = all tests in the module.** |
| Tier (0/1/2) | no | `1` | Tier 1 = system-prompt access; Tier 2 = conversation-only. (See caveat on Tier 0 in Known gaps.) |
| Samples/Group | no | `30` | Trials per demographic group. Bigger = slower but more statistical power. |
| Random Seed | no | `0` | `0` = auto. Set a value for reproducible sampling. |
| Temperature | no | `1.0` | Passed to the endpoint. |
| Significance α | no | `0.05` | Alpha for the verdict thresholds. |
| Chat Config Name | no | — | Optional override. |
| Prompt Config Name | no | — | Optional override. |
| Analysis Profile (scoring) | no | None (spec defaults) | Select an existing `iso42001.analysisProfile`, or "None". **You cannot create one here** — see Phase 8.2. |

Click **Create Campaign**. On success you get a "Campaign created." toast and land on the campaign
detail page (`#!/iso42001/campaigns/:configId`).

### Worked example A — quick Tier-1 BIAS smoke campaign

Use this for a fast first run that exercises the pipeline end to end without a long wait.

1. `#!/iso42001/campaigns` → **New Campaign**.
2. **Name:** `Smoke - BIAS Tier1`.
3. **Test Module:** `BIAS` (default). **Endpoint Type:** `ollama`.
4. **LLM Endpoint:** pick your configured endpoint from the autocomplete (e.g. your Ollama chatConfig).
5. **Test IDs:** leave empty (all tests in the module — the resolver runs the suite default).
6. **Tier:** `1`. **Samples/Group:** `5` (small, for speed). **Random Seed:** `42` (reproducible).
7. Leave Temperature `1.0`, α `0.05`, Analysis Profile "None".
8. **Create Campaign.**

### Worked example B — fuller campaign, specific Test IDs

Use this for a more statistically meaningful run over a chosen subset of tests.

1. `#!/iso42001/campaigns` → **New Campaign**.
2. **Name:** `BIAS Attr+Hire - n50`.
3. **Test Module:** `BIAS`. **Endpoint Type / LLM Endpoint:** your target endpoint.
4. **Test IDs:** enter a comma-separated subset, e.g. `BIAS-ATTR-002, BIAS-HIRE-001`
   (leave empty if you want every test in the module). Confirm the exact IDs against the module
   listing / [`iso42001-bias.md`](iso42001-bias.md).
5. **Tier:** `1`. **Samples/Group:** `50`. **Random Seed:** `7`.
6. **Create Campaign.**

You now have two campaigns to run.

---

## Phase 5 — Running (testing) a campaign

Runs are **synchronous** — the browser call returns when the run finishes — and there is **no cancel**:
once launched a run cannot be stopped from the UI (or the backend; it has no cancel endpoint). Runs call
a **live LLM endpoint**, so they take real time. This is why the smoke campaign (example A) uses a small
Samples/Group for a first pass.

**Two ways to launch — both call `POST /rest/iso42001/run { testConfigId }`:**

- **From a campaign:** on the campaign row click **Launch**, or on the campaign detail click
  **Launch Run** (Tester/Admin).
- **From the runner:** `#!/iso42001/run` → **New Run** → pick a campaign → **Launch Run**.

On success you get a **"Run launched."** toast and the app routes you to
`#!/iso42001/results/:runId`.

Notes:
- If the endpoint is unreachable, the run still persists but records verdict **`ERROR`** (by design) —
  that is not a crash.
- A launch failure toast means the config wasn't found, the endpoint didn't resolve, or access was
  denied (check your Tester role).

---

## Phase 6 — Viewing results

**Run list:** `#!/iso42001/run` lists all runs with Run / Endpoint / **Status** pill / **P/Fl/F** counts.
Click a run to open its results.

**Results browser:** `#!/iso42001/results/:runId` shows the run status and an
`N PASS · N FLAG · N FAIL` summary, then a per-test-result table:

| Column | Meaning |
|---|---|
| Test ID | The bias test that produced the result |
| Class | Protected class under test (`protectedClass`) |
| Verdict | PASS / FLAG / FAIL / ERROR badge |
| Effect | Effect size + its type (e.g. Cohen's d) |
| p (corr.) | Bonferroni-corrected p-value |

**Result detail:** click a row → `#!/iso42001/results/:runId/:resultId` shows the **Statistical Summary**
— test statistic, raw p-value → corrected p-value, effect size, and any notes.

Verdict badge meanings (thresholds defined in [`iso42001.md`](iso42001.md) §4.4): **PASS** (no
meaningful difference), **FLAG** (small–medium effect, needs justification), **FAIL** (medium–large
effect, needs mitigation + re-test), **ERROR** (run could not evaluate — e.g. unreachable endpoint).

---

## Phase 7 — Generating & exporting a report

Reports are generated **from a COMPLETED run** (Reporter/Admin):

1. On the results browser (or the campaign detail's run row) click **Generate Report**.
2. Enter a report name at the prompt. This calls
   `POST /rest/iso42001/report { name, reportType:'COMPLIANCE', testRunIds }`.
3. On success you land on the report detail at `#!/iso42001/report/:reportId`.

**Report detail** shows:
- Ordered **text sections** (Executive Summary, Methodology, Results, Mitigation, …), read-only.
- An **overall verdict** badge + **status** pill.
- A **Certification** block: green ✓ CERTIFIED (with signer) or NOT CERTIFIED.
- **Export PDF** (Reporter/Admin): calls `POST .../report/:id/export`, then opens
  `.../report/:id/pdf` in a new tab.
- **Request Certification** (Reporter, only while uncertified): opens a modal to enter a justification
  and optionally search/pick a certifier, then routes you to the new request.

> **Honest limitation — charts live only in the PDF.** There is **no interactive heat-map or trend
> chart in the UI today.** The dashboard shows summary cards only, and the report detail shows text
> sections only. The heat-map / trend / per-control breakdown is rendered **inside the exported PDF**
> (backend `ReportGenerator` / `PdfExporter`). To see charts, **Export PDF**. A richer in-UI dashboard
> is a known backlog item (see [`../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`](../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md)).

---

## Phase 8 — Certification (final stage)

The audit-grade sign-off lives at `#!/iso42001/cert`:

- **Queue:** lists certification requests with status.
- **Request detail** (`#!/iso42001/cert/request/:requestId`): justification + message thread; a
  Certifier/Admin sees **Approve & Sign** (prompts for a signing note, creates + signs the
  certification) and **Deny** (prompts for a reason).
- **Certificate detail** (`#!/iso42001/cert/view/:certId`): **Verify** returns a per-check result
  (status / cert / not-expired / hash / signature) with an overall valid/invalid badge; **Revoke** is
  Admin-only on a valid certificate.

A signed, verifiable certification on a COMPLIANCE report is the audit artifact the whole flow exists to
produce.

---

## Phase 9 — Customizing & configuring the rules

Be precise about the boundary: a few things are configurable per-campaign in the UI; most of the actual
rules are backend code/resources that require an engineer with deploy access.

### 9.1 Configurable in the UI (per-campaign only)

Everything here is a field on the campaign editor (Phase 4) and applies **only to that campaign's runs**:

- **Test Module** selection (`BIAS` today).
- **Test IDs** subset (which tests in the module run; empty = all).
- **Tier** (1 / 2).
- **Samples/Group**, **Random Seed**, **Temperature**, **Significance α**.
- **LLM Endpoint** (which model the campaign targets).
- **Analysis Profile** — *selecting* a pre-existing scoring profile (see 9.2).

There is **no global "rules" configuration screen** — configuration is per-campaign.

### 9.2 Analysis profiles — API-only today (known gap)

An `iso42001.analysisProfile` is a named scoring/statistics profile you can attach to a campaign. The
campaign editor's **Analysis Profile** picker **lists existing profiles only** — **there is no UI to
create or edit one.** The create endpoint exists (`createAnalysisProfile` →
`POST /rest/iso42001/profile`) but is not wired to any view.

**Current workaround — seed a profile via the REST API,** then it appears in the picker. Authenticated
as an ISO Tester/Admin (session cookie), POST the profile record to `POST /rest/iso42001/profile`
(schema `iso42001.analysisProfile`); confirm the field shape against
`AccountManagerISO42001/src/main/resources/models/iso42001/analysisProfileModel.json`. After creation,
re-open the campaign editor and select it from **Analysis Profile**; "None" keeps the engine's spec
defaults. A create/edit UI is a tracked backlog item — see
[`../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`](../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md) §3.1.

### 9.3 Backend-only (requires an engineer with deploy access — NOT the app UI)

Changing any of the following means editing source/resources in the `AccountManagerISO42001` /
`AccountManagerObjects7` modules and **rebuilding + redeploying** the WAR — it cannot be done from the
running app:

- **Annex-A control-area mappings are hardcoded.** In
  `AccountManagerISO42001/.../reporting/ReportGenerator.java`, `BIAS_CONTROL_AREAS` = A.5.4 / A.5.5 and
  `MODULE_CONTROL_AREAS` maps **only** `BIAS`; an unrecognized module id is recorded honestly as
  `UNMAPPED:<moduleId>` in the report rather than guessed. Broadening this mapping is a known backlog
  item.
- **The bias test suite is Java modules, not data.**
  `AccountManagerISO42001/.../engine/BiasModuleRegistry.java` instantiates a fixed **7-module** suite —
  `AttrModule`, `HireModule`, `RefusalModule`, `NarrModule`, `AssocModule`, `HealthModule`, `LoanModule`
  (under `engine/modules/`). Each module owns its verbatim prompt text and scoring. **Adding or altering
  a rule means editing/adding one of these Java classes and rebuilding + redeploying** — there is no
  data-driven rule editor.
- **Bias / ideology / compliance policy and prompt templates are backend runtime resources** under
  `AccountManagerObjects7/src/main/resources/olio/llm/` — e.g. `policy.bias.json`,
  `policy.rpg.bias.json`, `policy.general.json`, `prompts/biasPatterns.json`, `prompts/compliance.json`.
  These are editable **only via the filesystem + a rebuild/redeploy**, never through the UI. Per project
  policy, the bias/ideology content itself is enforced in code and prompt templates and is deliberately
  **not** reproduced in prose documentation — this runbook only tells you *where* it lives and that
  changing it requires a rebuild/redeploy. (These are also owned by the `Objects7` module — no ISO
  knowledge lives in Objects7; ISO reads these shared LLM resources.)
- **LLM endpoints** (chatConfig / `system.connection`) that campaigns target are managed at
  `#!/list/system.connection`, not in the ISO views (see Phase 0).

---

## Phase 10 — Known gaps & caveats (as of the current build)

Do not expect these; they are documented so you don't chase them as bugs (full list + status in
[`../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`](../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md)):

- **No run cancel** — runs are synchronous; there is no stop button and no backend cancel endpoint.
- **No analysis-profile create/edit UI** — API-only today (Phase 9.2).
- **No in-UI heat-map / trend / per-control chart** — dashboard is summary cards; charts are in the
  exported PDF only (Phase 7).
- **Results depth is basic** — no per-group mean/stddev table, bar chart, or raw-log download in the UI.
- **Reports are read-only in the UI** — no inline section editing.
- **List pagination is fixed-range** across ISO views (hardcoded ranges, not paged controls).
- **Approve & Sign validity period is static "1 year" text** — the certifier can't set a period yet.
- **Backend correctness note:** `Tier = 0` ("both") currently runs **Tier 1 only** — prefer an explicit
  `1` or `2` until this is resolved.

---

*Cross-references: [`iso42001.md`](iso42001.md) · [`iso42001-design.md`](iso42001-design.md) ·
[`iso42001-bias.md`](iso42001-bias.md) · [`iso42001-enduser-tests.md`](iso42001-enduser-tests.md) ·
[`../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md`](../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md) ·
[`../UxFeatureFlagDesign.md`](../UxFeatureFlagDesign.md)*
