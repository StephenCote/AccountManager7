# AccountManagerUx75 — Implementation Plan & Status

**Extracted from:** `Ux7Redesign.md` Section 14
**Last Updated:** 2026-03-06
**Design Reference:** See `Ux7Redesign.md` Sections 1-13 for requirements, architecture, and design specifications.

---

## 1. Current Status

**Project:** `AccountManagerUx75/` — 127 source files, ~69,000 lines
**Build:** Vite 6.4.1, 152 modules, builds in ~5s
**Tests:** 55 Vitest unit tests pass, 25 Playwright E2E tests pass (all green)
**Ux7 File Parity:** ~99% — all active Ux7 source files ported (5 intentionally skipped)

---

## 2. Phase Completion Summary

| Phase | Status | Files | Notes |
|-------|--------|-------|-------|
| **Phase 0: Foundation** | COMPLETE | 25+ | Vite bundler, ESM conversion, 6 core libs ported, Vitest + Playwright configured, dev server on port 8899 with proxy to localhost:8443 |
| **Phase 1: Dialog System + Field Styling** | COMPLETE | 5+ | `dialogCore.js` (unified Dialog component), form field renderers, field styling in Tailwind, dialog stacking, confirm shorthand |
| **Phase 2: Main Panel** | COMPLETE | 3+ | `panel.js` (dashboard), `topMenu.js`, `asideMenu.js`, breadcrumb navigation |
| **Phase 3: Feature System** | COMPLETE | 8 | `features.js` manifest with 7 features (core, chat, cardGame, games, testHarness, iso42001, biometrics), 5 build profiles, lazy `import()` for feature routes |
| **Phase 3.5a: E2E Test Infrastructure** | COMPLETE | 7 | Playwright E2E tests (25 tests, 7 spec files), console error capture with IGNORED_PATTERNS, isolated API session helpers, test user setup/teardown. Fixed: biometrics spec selectors, Mithril key mismatch in biometrics.js, session conflicts in parallel workers. |
| **Phase 3.5b: Dialog Workflow Commands** | **NEXT** | 0 | Port 6 missing command handlers from Ux7 dialog.js. See Section 4. |
| **Phase 3.5c: Feature Route Validation** | NOT STARTED | 0 | Runtime test card game, magic8, games against backend. Expect errors in agent-generated files. |
| **Phase 4: Compliance Dashboard** | STUB ONLY | 1 | `features/iso42001.js` has basic violation list. Missing: Overview, Audit Log, Policy Templates, Report tabs, real-time chat indicators. |
| **Phase 5: UX Polish** | NOT STARTED | 0 | Dashboard customization, dense mode, notifications. |
| **Phase 6: Model Form View** | NOT STARTED | 0 | Requires backend schema endpoints. |
| **Phase 7: Form Editor / Designer** | NOT STARTED | 0 | Requires `system.formDefinition` model on backend. Depends on Phase 6. |
| **Phase 8: WebAuthn** | NOT STARTED | 0 | Requires `WebAuthnService.java` + `webauthn4j` on backend. |
| **Phase 9: Access Requests** | NOT STARTED | 0 | Backend model exists. No Ux. Must ask user about hierarchical approval concept first. |
| **Phase 10: E2E + Performance** | NOT STARTED | 0 | SQLite WASM cache, a11y audit, performance profiling. |

---

## 3. Feature Port Status (Ux7 to Ux75)

| Feature | Files | Lines | Port Status | Runtime Status | Lazy Chunk Size |
|---------|-------|-------|-------------|----------------|-----------------|
| **Core** (am7client, model, view, pageClient, formDef, modelDef) | 11 | ~20,500 | COMPLETE | E2E TESTED — login, panel, list, object, nav all work | index: 385KB/103KB gzip |
| **Components** (dialog, form, picker, dnd, tree, tab, etc.) | 30 | ~12,000 | COMPLETE | E2E TESTED — dialog, form fields, pagination, breadcrumb work | (in index bundle) |
| **Views** (sig, list, object, navigator) | 4 | ~2,200 | COMPLETE | E2E TESTED — all core routes verified | (in index bundle) |
| **Chat** (LLMConnector, ConversationManager, AnalysisManager, etc.) | 16 | ~8,500 | COMPLETE | E2E TESTED — route loads, session list renders, library check works | chat: 28KB/8KB gzip |
| **Card Game** (CardGameApp + 33 subsystem files) | 34 | ~16,000 | COMPLETE (agent-generated) | UNTESTED — expect runtime errors | CardGameApp: 392KB/115KB gzip |
| **Magic 8** (Magic8App + 18 subsystem files) | 19 | ~8,500 | COMPLETE (agent-generated) | E2E TESTED — route loads, SessionConfigEditor renders | Magic8App: 171KB/46KB gzip |
| **Games** (Tetris, Word Game) | 2 | ~1,600 | COMPLETE | UNTESTED against backend | tetris: 9KB, wordGame: 23KB |
| **Test Harness** (LLM test suite, framework, registry) | 3 | ~5,200 | COMPLETE | E2E TESTED — route loads, Run Tests button + categories render | llmTestSuite: 119KB/33KB gzip |
| **ISO 42001** | 1 | ~120 | STUB ONLY | N/A | iso42001: 3KB |
| **Biometrics** | 1 | ~70 | ROUTE ONLY (routes to Magic8) | E2E TESTED — lazy load + config screen works | biometrics: 1KB |

### Deprecated Ux7 Files (Intentionally NOT Ported)

| File | Lines | Reason |
|------|-------|--------|
| `components/advGrid.js` | 807 | Obsolete — user directive: "ignore advGrid" |
| `view/hyp.js` | 371 | Replaced by Magic8 — user directive: "/hyp should be replaced by magic8" |
| `view/saur.js` | 32 | Legacy saurian view — design doc says deprecate |
| `codemirror.jslint.js` | 32 | Utility, not needed |
| `view/bak/*.js` | ~15,000 | Backup files, not active code |

---

## 4. CRITICAL GAP: Dialog Workflow Commands NOT Ported

The Ux7 `dialog.js` (2,044 lines) contained 6 command handler functions that were invoked by `object.js` when users clicked form command buttons. **These were NOT ported to Ux75.** The `dialogCore.js` replacement is a clean Dialog component, but the business logic that was embedded in the old `dialog.js` is missing.

**Impact:** In Ux75, clicking any of these command buttons on an object form logs `"Command function not found: <fn>"` and does nothing.

| Command Function | Ux7 Location | Lines | What It Does | Ux75 Status |
|------------------|-------------|-------|-------------|-------------|
| `reimage(object, inst)` | dialog.js:777 | ~485 | Opens SD reimaging dialog — model/lora selection, prompt preview, batch reimaging, progress tracking | **MISSING** |
| `reimageApparel(object, inst)` | dialog.js:1262 | ~293 | Reimages apparel items — iterates wearables, generates SD prompts per wear level | **MISSING** |
| `summarize(object, inst)` | dialog.js:137 | ~58 | Opens summarization dialog — creates/updates context summary for chat sessions | **MISSING** |
| `vectorize(object, inst)` | dialog.js:195 | ~582 | Opens vectorization dialog — creates vector embeddings for RAG context | **MISSING** |
| `memberCloud(modelType, containerId)` | dialog.js:1555 | ~301 | Renders membership visualization — cloud/grid of member objects with thumbnails | **MISSING** |
| `adoptCharacter(object, inst)` | dialog.js:1856 | ~188 | Opens character adoption dialog — copy character to user's population | **MISSING** |

**Additional dialog.js responsibilities NOT ported:**
- Outfit builder integration (triggered via `outfitBuilder` command on charPerson forms)
- Social sharing tag management
- Batch progress tracking with progress bar dialogs
- Policy template loading dialogs
- Chat settings workflow (partially covered by `ChatConfigToolbar.js` and `ChatSetupWizard.js`)

**Resolution:** These must be implemented as separate workflow modules in Ux75 using `Dialog.open()` from `dialogCore.js`. See Phase 3.5b in Section 8.

---

## 5. Known Issues and Import Mismatches

**FIXED (document for reference):**

1. **`uwm` export location** — `uwm` is exported from `core/am7client.js`, NOT from `base64.js` or `config.js`. Several agent-generated magic8 files had wrong import paths. All fixed.

2. **Top-level `await`** — `SessionConfigEditor.js` used top-level await for optional `am7sd` import. Fixed with lazy init pattern (`_ensureSd()` in `oninit`).

3. **Circular dependencies** — Solved in Phase 0 via late-binding: `am7model._view`, `am7model._page`, `am7model._client` wired in `main.js`. Pattern: `function getPage() { return am7model._page; }` in subsystem files.

4. **Mithril fragment key mismatch** — `biometrics.js` passed `key: 'magic8'` to the magic8App component. Since `layout()` wraps content in `[content, toast, dialogs]`, this created a mixed keyed/unkeyed vnode array causing Mithril rendering errors. Fixed by removing the key attribute. (Phase 3.5a)

5. **E2E session conflicts** — Parallel Playwright workers shared admin API sessions, causing login/search failures. Fixed by giving each `setupTestUser`/`ensureSharedTestUser` call its own `APIRequestContext` via `pwRequest.newContext()`. (Phase 3.5a)

6. **Console error false positives** — E2E console capture was too strict, failing tests on expected HTTP errors (REST 4xx/5xx, WebSocket errors, REFACTOR markers). Fixed with comprehensive IGNORED_PATTERNS. (Phase 3.5a)

**POTENTIAL ISSUES (not yet validated at runtime):**

7. **Agent-generated card game files not runtime-tested** — Card game (34 files) ported by background agents. Builds successfully but has NOT been tested against a running backend. Expect runtime issues with incorrect function signatures, missing state initialization, event handler binding differences.

8. **`page` vs `getPage()` consistency** — Some agent-generated files may still reference `page` directly instead of using the `getPage()` late-binding pattern.

9. **`g_application_path` to `am7client.base()` conversion** — Agent-generated files may have missed some instances.

10. **CSS class references** — Agent-generated files may reference Ux7-specific CSS classes that don't exist in Ux75's `main.css`/`pageStyle.css`.

11. **`window.Magic8.*` / `window.CardGame.*` remnants** — IIFE modules used global namespace objects. Some may remain in agent-generated code.

12. **`Dialog.open()` adoption** — Only 2 production files use `Dialog.open()`. All command workflows from dialog.js are missing entirely. See Section 4.

13. **`media` feature not in manifest** — Design doc Section 7 defines a `media` feature for lazy loading. Currently loaded eagerly in `main.js`, inflating the index bundle.

---

## 6. Architecture Decisions

| Decision | Resolution |
|----------|-----------|
| **ESM module pattern** | All files use named exports + default export. Components export the component object directly. |
| **Late-binding for circular deps** | `am7model._page`, `am7model._client`, `am7model._view`, `am7model._olio`, `am7model._sd` wired in `main.js` |
| **Feature routes** | Lazy-loaded via `import()` in `features.js`. Each feature file exports `{ routes }`. Router merges feature routes after auth. |
| **Build chunking** | Vite auto-splits lazy imports into separate chunks: CardGameApp, Magic8App, chat, tetris, wordGame, llmTestSuite, pdf |
| **Component registration** | `page.components.*` pattern preserved from Ux7. Components registered in `main.js` after import. |
| **Vite dev server** | Port 8899, proxies `/rest/*` and `/media/*` to `https://localhost:8443` (Service7) |
| **Test framework** | Vitest for unit tests (55 tests), Playwright for E2E (25 tests, 7 spec files). Console capture with auto-assertion. |
| **E2E test isolation** | Each spec's `setupTestUser`/`ensureSharedTestUser` creates its own `APIRequestContext` to avoid session conflicts under parallel execution. 4 workers, 1 retry. |

---

## 7. Complete Gap Analysis

### A. Critical Gaps (Functional regressions from Ux7)

| Gap | Design Section | Impact | Effort |
|-----|---------------|--------|--------|
| **Dialog workflow commands** (reimage, summarize, vectorize, memberCloud, adoptCharacter, outfitBuilder) | S4.3 | Object form command buttons do nothing in Ux75 | HIGH — ~1,900 lines of business logic to port as ESM workflow modules using Dialog.open() |
| **Card game runtime validation** | N/A | 34 agent-generated files never tested against backend | MEDIUM — requires running backend, systematic testing |

### B. Design Doc Features — Backend Required

| Feature | Section | Blocking Backend Work |
|---------|---------|----------------------|
| **Model Form View** | S2 | Schema REST endpoints (`/rest/schema/*`), `userDefined` flag on models/fields |
| **Form Editor / Designer** | S3 | `system.formDefinition` + `system.formField` models, `formLoader` DB integration |
| **WebAuthn** | S8 | `WebAuthnService.java`, `webauthn4j` dependency, `CredentialEnumType.WEBAUTHN`, challenge management |
| **Compliance data aggregation** | S10 | Server endpoint for violation counts, pass rates over time periods |
| **Access Requests UI** | S11 | Approval workflow endpoints, auto-provisioning logic |

### C. Design Doc Features — Client-Only (Can Build Now)

| Feature | Section | Effort | Notes |
|---------|---------|--------|-------|
| **Compliance dashboard tabs** (Overview, Violations detail, Audit Log, Policy Templates, Report) | S10.2-10.5 | Medium | Current stub only shows violation list. Design calls for 5 tabs with charts, filters, export. Backend events already work. |
| **Compliance real-time indicators in chat** | S10.4 | Low | WebSocket policyEvent/autotuneEvent already fire. Need badge + inline panel in chat view. |
| **Bias Pattern Editor** | S10.6 | Medium | View/edit biasPatterns.json. Includes pattern test tool. |
| **Dashboard customization** (pin/reorder/hide categories) | S6 | Medium | Preferences stored as `data.data` JSON. Panel.js needs edit mode, drag-to-reorder. |
| **Dense/compact view mode** | S5 | Low | CSS density classes + localStorage preference toggle. |
| **Notification panel** | S11.C | Medium | Unified notification component using `message.spool` + WebSocket events. Badge in top menu. |

### D. Design Doc Optimizations — Deferred

| Feature | Section | Effort | Notes |
|---------|---------|--------|-------|
| **`media` feature extraction** | S7 | Low | Move audio, PDF, SD config from main.js core imports to lazy-loaded feature chunk. Reduces index bundle. |
| **SQLite WASM client cache** | S9 | High | sql.js integration, schema mirroring, cache invalidation. Enhancement, not required for parity. |
| **Responsive breakpoints on field grid** | S5 | Low | `sm:grid-cols-1 md:grid-cols-3 lg:grid-cols-6` on form fields. Currently fixed 6-col. |

---

## 8. Revised Phase Plan

Phases 0-3 and 3.5a are COMPLETE. The next phase is 3.5b (Dialog Workflow Commands).

### Phase 3.5b: Dialog Workflow Commands — NEXT

**Goal:** Port the 6 missing command handlers from Ux7 `dialog.js` into Ux75 as separate ESM workflow modules. Each workflow should use `Dialog.open()` from `dialogCore.js`.

**Prerequisites:**
- Backend running on localhost:8443 (needed for runtime testing the workflows)
- Read Ux7 `client/components/dialog.js` for reference implementation

**Tasks:**
- [ ] Create `src/workflows/summarize.js` — Summarization dialog (~58 lines from dialog.js:137-194)
  - Register as `objectPage.summarize` in `views/object.js`
- [ ] Create `src/workflows/vectorize.js` — Vectorization dialog (~582 lines from dialog.js:195-776)
  - Register as `objectPage.vectorize`
- [ ] Create `src/workflows/reimage.js` — SD reimaging dialog (~485 lines from dialog.js:777-1261)
  - Model/lora selection, prompt preview, batch reimaging, progress tracking
  - Register as `objectPage.reimage`
- [ ] Create `src/workflows/reimageApparel.js` — Apparel reimaging (~293 lines from dialog.js:1262-1554)
  - Register as `objectPage.reimageApparel`
- [ ] Create `src/workflows/memberCloud.js` — Member cloud visualization (~301 lines from dialog.js:1555-1855)
  - Register as `objectPage.memberCloud`
- [ ] Create `src/workflows/adoptCharacter.js` — Character adoption (~188 lines from dialog.js:1856-2043)
  - Register as `objectPage.adoptCharacter`
- [ ] Port `outfitBuilder` command handler (referenced in charPerson form commands)
- [ ] Wire all workflow modules in `views/object.js` so command buttons dispatch correctly
- [ ] Add Vitest tests for workflow module registration
- [ ] Add Playwright E2E test for at least one workflow (summarize — simplest)

**Estimated effort:** HIGH — ~1,900 lines of business logic
**Estimated files changed:** 6-8 new workflow files + `views/object.js` wiring
**Risk:** Medium — Ux7 source is the reference implementation

### Phase 3.5c: Feature Route Validation

**Goal:** Runtime-test all feature routes against a running backend. Fix agent-generated code errors.

- [ ] Test `/cardGame` — verify lazy load, game initialization, card rendering
- [ ] Test `/game` — verify Tetris and Word Game load and run
- [ ] Fix all agent-generated code runtime errors (expect significant work for cardGame)
- [ ] Verify `g_application_path` → `am7client.base()` conversion complete
- [ ] Verify no `window.Magic8.*` / `window.CardGame.*` remnants
- [ ] Verify CSS class references resolve (no Ux7-only classes referenced)

**Estimated effort:** MEDIUM — primarily debugging agent-generated cardGame files
**Risk:** Medium — card game has 34 files that were never runtime-tested

### Phase 4: Compliance Dashboard Buildout (Client-Focused)

**Goal:** Build the full 5-tab compliance dashboard described in Ux7Redesign.md Section 10.

- [ ] Expand `features/iso42001.js` from stub to full tabbed dashboard
- [ ] Tab 1: Overview — aggregate violation stats, pass rates, trend chart
- [ ] Tab 2: Violations Detail — searchable/filterable list, mark-as-reviewed
- [ ] Tab 3: Audit Log — query `system.audit` records with filters, CSV/JSON export
- [ ] Tab 4: Policy Templates — view/edit policy template JSON
- [ ] Tab 5: Report — generate compliance reports
- [ ] Real-time compliance indicator badge in chat view toolbar
- [ ] Bias Pattern Editor (view/edit biasPatterns.json)
- [ ] Create compliance data aggregation endpoint on backend

### Phase 5: Dashboard Customization + UX Polish (Client-Only)

- [ ] Dashboard customization: edit mode, drag-to-reorder, pinned items, recent items
- [ ] Dashboard preferences: save/load via `data.data` JSON
- [ ] Dense/compact view mode with CSS density toggle
- [ ] Responsive field grid breakpoints
- [ ] Notification panel with `message.spool` data + badge
- [ ] Extract `media` feature from core imports to lazy chunk

### Phase 6-9: Backend-Dependent Features

- **Phase 6:** Model Form View (S2) — requires schema REST endpoints
- **Phase 7:** Form Editor / Designer (S3) — requires Phase 6 + `system.formDefinition`
- **Phase 8:** WebAuthn (S8) — requires `WebAuthnService.java`
- **Phase 9:** Access Requests (S11) — requires user input on approval concept

### Phase 10: Performance + Polish

- [ ] SQLite WASM client cache (sql.js integration)
- [ ] Performance profiling and bundle optimization
- [ ] Accessibility audit (WCAG 2.1 AA)

---

## 9. E2E Test Coverage

**25 Playwright E2E tests** across 7 spec files:

| Spec | Tests | What's Covered |
|------|-------|----------------|
| `login.spec.js` | 4 | Page load, valid login, invalid login error, logout |
| `panel.spec.js` | 5 | Category cards, navigate to list, home button, dark mode, feature buttons |
| `list.spec.js` | 4 | List/empty state, toolbar, breadcrumb, filter input |
| `object.spec.js` | 3 | Add button, form fields, double-click opens view |
| `chat.spec.js` | 3 | Menu navigation, page content, library status check |
| `biometrics.spec.js` | 3 | Magic 8 navigation, Start Session button, config description |
| `testHarness.spec.js` | 3 | Menu navigation, UI components, Run Tests + categories |

**Test infrastructure:**
- `e2e/helpers/fixtures.js` — Extended fixture with automatic console error capture
- `e2e/helpers/console.js` — Console capture + `assertNoConsoleErrors()` with IGNORED_PATTERNS
- `e2e/helpers/auth.js` — `login(page, opts)` + `screenshot(page, name)`
- `e2e/helpers/api.js` — Isolated `APIRequestContext` per setup/teardown call, `safeJson()` for robust parsing
- `playwright.config.js` — 4 workers, 1 retry, Chromium only, auto-starts Vite dev server

---

## 10. Build Output

```
Source files:  127 JS (69,000 lines) + 5 unit test files (55 tests) + 7 E2E spec files (25 tests)
Styles:        2 CSS files (main.css, pageStyle.css) -> 84KB bundled

Build output (dist/):
  index.js           385 KB / 103 KB gzip  (core + components + views)
  CardGameApp.js     392 KB / 115 KB gzip  (card game lazy chunk)
  Magic8App.js       171 KB /  46 KB gzip  (magic8 lazy chunk)
  pdf.js             448 KB / 133 KB gzip  (PDF.js library)
  llmTestSuite.js    119 KB /  33 KB gzip  (test harness)
  chat.js             28 KB /   8 KB gzip  (chat feature route)
  wordGame.js         23 KB /   8 KB gzip  (word game)
  AnalysisManager.js  37 KB /  11 KB gzip  (chat analysis)
  LLMConnector.js     12 KB /   4 KB gzip  (LLM connector)
  + 10 smaller chunks (< 11 KB each)
```

---

## 11. Quick Start Guide

**To resume Ux75 development in a new conversation:**

1. Read this file (`aiDocs/Ux75ImplementationPlan.md`) for current status and next phase
2. Read `Ux7Redesign.md` for design requirements (Sections 1-13)
3. Read `~/.claude/projects/.../memory/MEMORY.md` for API patterns and gotchas
4. Run `cd AccountManagerUx75 && npm run dev` to start Vite dev server (port 8899)
5. Run `npx vitest run` to verify unit tests pass (55 tests expected)
6. Run `npx playwright test` to verify E2E tests pass (25 tests expected, requires backend on 8443)

**Key files to read first:**
- `src/main.js` — Entry point, wires all modules
- `src/router.js` — Route definitions, `layout()` and `pageLayout()` helpers
- `src/features.js` — Feature manifest, `loadFeatureRoutes()`
- `src/core/pageClient.js` — App state, auth, WebSocket
- `src/core/am7client.js` — REST client, `uwm` login helper
- `src/views/object.js` — Object view with command dispatch (lines 295-317, where workflow commands are dispatched)

**For Phase 3.5b (Dialog Workflow Commands):**
- Read Ux7 `AccountManagerUx7/client/components/dialog.js` — reference implementation for all 6 workflows
- Read `src/components/dialogCore.js` — the Ux75 Dialog component to use for workflow UIs
- Read `src/views/object.js` lines 295-317 — command dispatch where `objectPage[fn]` handlers need registering
