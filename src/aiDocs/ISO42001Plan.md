# ISO 42001 Compliance Dashboard — Design & Implementation Plan

**Parent project:** AccountManagerUx75
**Status:** DEFERRED — waiting on backend compliance endpoints
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

## 6. Design Decisions

- ISO 42001 UX development is decoupled from the main Ux75 refactor phases
- The current stub is functional for live monitoring and will remain as-is until backend work begins
- This plan will be revisited when the main ISO 42001 compliance effort starts on the backend
- All overcorrection directives and bias detection logic live on the server side — the UX is a reporting/monitoring layer only
