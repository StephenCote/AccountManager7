# ISO 42001 Compliance Dashboard — Design & Implementation Plan

**Parent project:** AccountManagerUx75
**Status:** PLANNED — tracked separately from `Ux75ImplementationPlan.md` (see Section 8 for full checklist)
**Current stub:** `AccountManagerUx75/src/features/iso42001.js` (live policy event monitor)

---

## 1. Overview

The ISO 42001 Compliance Dashboard provides visibility into AI system compliance evaluation, training bias detection, and overcorrection policy enforcement. It is a Ux75 feature that lazy-loads via the feature system (`features.js` → `iso42001`).

---

## 2. Current State (Stub)

`features/iso42001.js` provides:
- System status badges (chat/prompt/template/policy library initialization)
- Overcorrection policy info box (references CLAUDE.md 10-area directive)
- Live policy violation list with filter (fed by `LLMConnector.onPolicyEvent`)
- Single-page view at `/compliance` route

---

## 3. Planned Features

### 3.1 Tab Navigation
- **Overview** — summary cards, pass/fail rates, trend sparklines
- **Audit Log** — searchable/filterable evaluation history
- **Policy Templates** — view/edit prompt configs with overcorrection directives
- **Reports** — exportable compliance data (JSON/CSV)
- **Live Monitor** — current violation list (already implemented)

### 3.2 Backend Dependencies

| Feature | Backend Requirement | Model/Endpoint | Status |
|---------|-------------------|----------------|--------|
| **Audit Log** | Query `system.audit` records filtered by compliance-related actions | `system.audit` model exists in schema | Queryable now, but need to identify compliance-specific audit action types |
| **Overview metrics** | Aggregation of compliance evaluation results over time | No dedicated endpoint — would need count/group-by on audit records | Not available |
| **Policy Templates** | Read/write `prompt.config.json` and `compliance.json` | Files on server filesystem, not REST resources | Needs new endpoint |
| **Reports** | Export audit data as JSON/CSV | Client-side export from audit query results | Doable once audit works |
| **Chat indicators** | Inline compliance status per chat message | `LLMConnector.handlePolicyEvent` fires during evaluations | Available now |

### 3.3 Chat Integration
- Green/yellow/red badge per chat message based on compliance evaluation result
- Requires `LLMConnector.onPolicyEvent` to include message correlation ID
- Badge renders in chat message component (would need hook in chat feature)

---

## 4. Implementation Phases

### Phase A: Tab Navigation + Audit Log (no new backend)
- Add tab UI to compliance view
- Query `system.audit` for compliance-related records
- Display in searchable/filterable table
- Move current violation list into "Live Monitor" tab

### Phase B: Overview Dashboard (needs aggregation)
- Summary cards: total evaluations, pass rate, fail rate
- Time-series chart (last 7/30 days)
- Requires either: backend aggregation endpoint OR client-side rollup of audit records

### Phase C: Policy Templates (needs backend endpoint)
- Read prompt configs from server
- Display which call paths have overcorrection directives
- Edit/save prompt configs
- Requires new REST endpoint for prompt config CRUD

### Phase D: Reports + Chat Indicators
- CSV/JSON export of audit data
- Inline compliance badges in chat messages

---

## 5. Key Files

| File | Purpose |
|------|---------|
| `src/features/iso42001.js` | Current stub — compliance view component and routes |
| `src/features.js` | Feature manifest — iso42001 entry with deps on core + chat |
| `src/chat/LLMConnector.js` | Policy event system (`onPolicyEvent`, `handlePolicyEvent`, `checkLibrary`) |
| `src/core/modelDef.js` | Schema — `system.audit` model definition |

---

## 6. Backend: ComplianceService.java

**Effort:** Medium (2-3 days) | **Risk:** Low
**Location:** `AccountManagerService7/src/main/java/org/cote/rest/services/ComplianceService.java`

New REST service with the following endpoints:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/rest/compliance/summary?period=7d` | GET | Aggregate violation counts, pass rates, trend data |
| `/rest/compliance/violations?startRecord=0&recordCount=25&area={biasArea}` | GET | Paginated violation list with filters |
| `/rest/compliance/report` | POST | Generate compliance report JSON for date range |
| `/rest/compliance/patterns` | GET | Read biasPatterns.json |
| `/rest/compliance/patterns` | PUT | Update biasPatterns.json (admin only) |

**Backend work:** Query `system.audit` records filtered by policy-related actions, aggregate by bias area and time period. Straightforward query/aggregation against existing models.

**Testing:** Each endpoint needs a JUnit test in `AccountManagerService7/src/test/`.

---

## 7. Design Decisions

- ISO 42001 UX development is decoupled from the main Ux75 refactor phases
- The current stub is functional for live monitoring and will remain as-is until backend work begins
- This plan will be revisited when the main ISO 42001 compliance effort starts on the backend
- All overcorrection directives and bias detection logic live on the server side — the UX is a reporting/monitoring layer only

---

## 8. Implementation Checklist

**Goal:** Complete compliance dashboard with full backend service + multi-tab frontend.
**Effort:** High (5-7 days total: 2-3 backend, 3-4 frontend)

**8a: Backend — ComplianceService.java** (NEW)
- [ ] Create `ComplianceService.java` at `/rest/compliance`
- [ ] `GET /rest/compliance/summary?period=7d` — aggregate violation counts, pass rates, trend data by querying `system.audit` records filtered by compliance-related action types
- [ ] `GET /rest/compliance/violations?startRecord=0&recordCount=25&area={biasArea}` — paginated violation list with severity, area, timestamp, message
- [ ] `POST /rest/compliance/report` — generate compliance report JSON for date range (accepts `{startDate, endDate, format}`)
- [ ] `GET /rest/compliance/patterns` — read bias patterns from configuration (the 10 overcorrection areas + custom patterns)
- [ ] `PUT /rest/compliance/patterns` — update bias patterns (admin only)
- [ ] `GET /rest/compliance/prompts` — list prompt configs with overcorrection directive status per call path
- [ ] Register service in Jersey (auto-discovered via `@Path` in `org.cote.rest.services` package)
- [ ] JUnit tests: `TestComplianceService.java` — summary aggregation, violation query, report generation, pattern CRUD
- [ ] Wire compliance-specific audit action types into existing `system.audit` model (or use existing action types if suitable)

**8b: Frontend — Tab Navigation**
- [ ] Replace single-page view with tabbed layout: Overview | Audit Log | Policy Templates | Reports | Live Monitor
- [ ] Move current violation list into "Live Monitor" tab
- [ ] Add `am7client` methods for compliance endpoints (summary, violations, report, patterns, prompts)

**8c: Frontend — Overview Tab**
- [ ] Summary cards: total evaluations, pass rate, fail rate, violations by area
- [ ] Trend sparklines (last 7/30 days) — simple SVG line charts, no external charting library
- [ ] Area breakdown: horizontal bar chart showing violation count per overcorrection area
- [ ] Auto-refresh every 60s

**8d: Frontend — Audit Log Tab**
- [ ] Searchable/filterable table of compliance evaluation records
- [ ] Columns: timestamp, type, area, severity, message, actor
- [ ] Pagination via existing pagination component
- [ ] Filter by: severity (error/warn/info), area (10 overcorrection areas), date range
- [ ] Click row to expand details

**8e: Frontend — Policy Templates Tab**
- [ ] List all 5 LLM call paths with overcorrection directive status (present/missing/modified)
- [ ] Display prompt config content with syntax highlighting (pre-formatted text)
- [ ] Admin: edit/save prompt configs via compliance/prompts endpoint
- [ ] Validation: warn if overcorrection directive is missing or weakened

**8f: Frontend — Reports Tab**
- [ ] Date range picker (start/end date inputs)
- [ ] Generate report button → POST to `/rest/compliance/report`
- [ ] Display report as formatted summary + download as JSON/CSV
- [ ] Report includes: period summary, top violations, area breakdown, trend data

**8g: Frontend — Tests**
- [ ] Vitest unit tests: tab navigation, summary card rendering, filter logic, export formatting
- [ ] Playwright E2E test: compliance route loads, tabs render, library status badges
