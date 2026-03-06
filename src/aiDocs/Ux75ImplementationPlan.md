# AccountManagerUx75 — Implementation Plan & Status

**Extracted from:** `Ux7Redesign.md` Section 14
**Last Updated:** 2026-03-06
**Design Reference:** See `Ux7Redesign.md` Sections 1-13 for requirements, architecture, and design specifications.

---

## 1. Current Status

**Project:** `AccountManagerUx75/` — 135 source files, ~71,000 lines
**Build:** Vite 6.4.1, 160 modules, builds in ~5s
**Tests:** 64 Vitest unit tests pass, 35 Playwright E2E tests pass (all green)
**Phase 5 completed:** 2026-03-06
**Ux7 File Parity:** ~100% — all active Ux7 source files ported including dialog workflow commands (5 intentionally skipped)

---

## 2. Phase Completion Summary

| Phase | Status | Files | Notes |
|-------|--------|-------|-------|
| **Phase 0: Foundation** | COMPLETE | 25+ | Vite bundler, ESM conversion, 6 core libs ported, Vitest + Playwright configured, dev server on port 8899 with proxy to localhost:8443 |
| **Phase 1: Dialog System + Field Styling** | COMPLETE | 5+ | `dialogCore.js` (unified Dialog component), form field renderers, field styling in Tailwind, dialog stacking, confirm shorthand |
| **Phase 2: Main Panel** | COMPLETE | 3+ | `panel.js` (dashboard), `topMenu.js`, `asideMenu.js`, breadcrumb navigation |
| **Phase 3: Feature System** | COMPLETE | 8 | `features.js` manifest with 7 features (core, chat, cardGame, games, testHarness, iso42001, biometrics), 5 build profiles, lazy `import()` for feature routes |
| **Phase 3.5a: E2E Test Infrastructure** | COMPLETE | 7 | Playwright E2E tests (25 tests, 7 spec files), console error capture with IGNORED_PATTERNS, isolated API session helpers, test user setup/teardown. Fixed: biometrics spec selectors, Mithril key mismatch in biometrics.js, session conflicts in parallel workers. |
| **Phase 3.5b: Dialog Workflow Commands** | COMPLETE | 8 | Ported all 7 command handlers (summarize, vectorize, reimage, reimageApparel, memberCloud, adoptCharacter, outfitBuilder) as ESM workflow modules in `src/workflows/`. Wired into object.js command dispatch. 9 Vitest + 2 Playwright tests added. |
| **Phase 3.5c: Core Workflow Runtime Validation** | COMPLETE | 9 | Runtime-tested all 7 workflow handlers + chat against backend. Fixed: setNarDescription null crash, 7 formDef forward-reference bugs, reimage null sdEntity + missing await, command dispatch error handling, Mithril render resilience. 9 Playwright E2E tests added. |
| **Phase 3.5d: Model Ref Form Rendering** | COMPLETE | 2 | Sub-object tabs (personality, statistics, store, narrative, profile) now render full fields instead of just objectId. Implemented `pinst` cache for sub-instances, lazy tab activation, async sub-object save with `background:true` to avoid redraw storms, grid view null guard. |
| **Phase 4: ISO 42001 Compliance Dashboard** | DEFERRED | 1 | Moved to separate design/plan (`aiDocs/ISO42001Plan.md`). Depends on backend compliance endpoints. Current stub remains functional for live policy event monitoring. |
| **Phase 5: UX Polish** | COMPLETE | 10 | Aside menu navigation, dark mode fix, keyboard shortcuts (Ctrl+S/Esc/Ctrl+1-9), toast stacking, dashboard recent items, dense mode toggle, runtime null guards, responsive grid breakpoints, notification panel with badge, notification CSS |
| **Phase 6: Model Form View** | NOT STARTED | 0 | Requires backend schema endpoints. |
| **Phase 7: Form Editor / Designer** | NOT STARTED | 0 | Requires `system.formDefinition` model on backend. Depends on Phase 6. |
| **Phase 8: WebAuthn** | NOT STARTED | 0 | Requires `WebAuthnService.java` + `webauthn4j` on backend. |
| **Phase 9: Access Requests** | NOT STARTED | 0 | Backend model exists. No Ux. Must ask user about hierarchical approval concept first. |
| **Phase 10: Game Feature Validation** | NOT STARTED | 0 | Runtime-test cardGame (34 files), magic8, tetris, wordGame. Deferred — benefits from stable common infra. |
| **Phase 11: Performance + Polish** | NOT STARTED | 0 | SQLite WASM cache, a11y audit, performance profiling. |

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

## 4. Dialog Workflow Commands — RESOLVED (Phase 3.5b)

All 7 command handlers from Ux7 `dialog.js` have been ported to Ux75 as ESM workflow modules in `src/workflows/`. Each uses `Dialog.open()` from `dialogCore.js`.

| Command Function | Ux75 Location | What It Does | Status |
|------------------|--------------|-------------|--------|
| `summarize(entity, inst)` | workflows/summarize.js | Chat/prompt config selection, calls `/vector/summarize` | **PORTED** |
| `vectorize(entity, inst)` | workflows/vectorize.js | Chunk type/size selection, calls `/vector/vectorize` | **PORTED** |
| `reimage(entity, inst)` | workflows/reimage.js | SD config dialog, batch image gen, wear level tagging, portrait update | **PORTED** |
| `reimageApparel(entity, inst)` | workflows/reimageApparel.js | Mannequin image generation for apparel | **PORTED** |
| `memberCloud(modelType, containerId)` | workflows/memberCloud.js | Tag cloud visualization with drill-down to member grid | **PORTED** |
| `adoptCharacter(entity, inst)` | workflows/adoptCharacter.js | Adopt character into world population | **PORTED** |
| `outfitBuilder(entity, inst)` | workflows/outfitBuilder.js | Outfit builder panel delegation | **PORTED** |

**Wiring:** All handlers registered on `objectPage` in `views/object.js` (lines 358-368). Form command buttons now dispatch to the correct workflow.

**Shared utilities:** `reimage.js` exports `getOrCreateSharingTag()`, `applySharingTag()`, `socialSharingMap` for reuse by `reimageApparel.js`.

**Tests:** 9 Vitest unit tests (workflow exports + socialSharingMap), 2 Playwright E2E tests (handler registration + no command-not-found warnings).

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

12. **`Dialog.open()` adoption** — RESOLVED. 7 workflow modules now use `Dialog.open()` for all command workflows. See Section 4.

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
| ~~**Dialog workflow commands**~~ | S4.3 | ~~RESOLVED~~ — All 7 command handlers ported in Phase 3.5b | DONE |
| **Core workflow runtime validation** | N/A | Workflow commands ported but not runtime-tested against backend | MEDIUM — Phase 3.5c |
| **Card game runtime validation** | N/A | 34 agent-generated files never tested against backend | MEDIUM — deferred to Phase 10 |

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

### Phase 3.5b: Dialog Workflow Commands — COMPLETE

**Completed:** All 7 command handlers ported as ESM workflow modules using `Dialog.open()`.

**Files created (8):**
- [x] `src/workflows/index.js` — Re-exports all workflow handlers
- [x] `src/workflows/summarize.js` — Chat/prompt config dialog, `/vector/summarize` endpoint
- [x] `src/workflows/vectorize.js` — Chunk options dialog, `/vector/vectorize` endpoint
- [x] `src/workflows/reimage.js` — SD config dialog with dress up/down, batch generation, portrait update, sharing tags
- [x] `src/workflows/reimageApparel.js` — Mannequin image generation for apparel
- [x] `src/workflows/memberCloud.js` — Tag cloud visualization with drill-down member grid
- [x] `src/workflows/adoptCharacter.js` — Character adoption to world population
- [x] `src/workflows/outfitBuilder.js` — Outfit builder panel delegation

**Files modified (1):**
- [x] `src/views/object.js` — Added workflow imports + handler registration on objectPage

**Tests added:**
- [x] `src/test/workflows.test.js` — 9 Vitest tests (exports + socialSharingMap)
- [x] `e2e/workflows.spec.js` — 2 Playwright E2E tests (registration + no warnings)

### Phase 3.5c: Runtime Validation of Core Workflows — NEXT

**Goal:** Runtime-test workflow commands, sdConfig, olio operations, and chat against the running backend. Fix integration issues. This validates the common infrastructure that everything else depends on.

**Prerequisites:** Backend running on localhost:8443.

**A. SD Config + Reimage Workflow Validation**
- [ ] Open a `olio.charPerson` object, click Reimage command button
- [ ] Verify SD config dialog opens with correct defaults (steps=40, cfg=5, photograph style)
- [ ] Verify dress up/down buttons work (calls am7olio.dressCharacter + setNarDescription)
- [ ] Generate a single image — verify POST to `/olio/olio.charPerson/{id}/reimage` succeeds
- [ ] Verify portrait update, wear-level tagging, sharing tags all applied
- [ ] Test with smaller/faster SD model if available (user can configure)
- [ ] Open an `olio.apparel` object, click reimageApparel — verify mannequin generation
- [ ] Fix any runtime issues with am7sd.fetchTemplate, loadConfig, saveConfig

**B. Summarize + Vectorize Validation**
- [ ] Open any object with summarize command, verify chat/prompt config dropdowns populate
- [ ] Test summarize against backend — verify `/vector/summarize` endpoint call
- [ ] Test vectorize — verify `/vector/vectorize` endpoint call
- [ ] Fix any issues with loadChatList/loadPromptList (~/Chat directory resolution)

**C. Member Cloud + Adopt Validation**
- [ ] Test memberCloud on a tag or container with members
- [ ] Verify cloud renders, tag click drills to member grid, thumbnails load
- [ ] Test adoptCharacter on a character outside world population
- [ ] Verify POST to `/rest/game/adopt/{id}` works

**D. Outfit Builder Validation**
- [ ] Test outfitBuilder command on a charPerson
- [ ] Verify OutfitBuilderPanel / PieceEditorPanel components render (if am7olio exports them)
- [ ] Document any missing olio components that need porting

**E. Chat Feature Runtime Validation**
- [ ] Navigate to /chat, verify session list loads from backend
- [ ] Create a new chat session, send a message
- [ ] Verify LLMConnector, ConversationManager, AnalysisManager work end-to-end
- [ ] Verify WebSocket events (if chat uses real-time messaging)

**F. Add E2E Tests for Validated Workflows**
- [ ] E2E test: reimage dialog opens on charPerson
- [ ] E2E test: summarize dialog opens with config dropdowns
- [ ] E2E test: memberCloud renders cloud view

**Estimated effort:** MEDIUM — primarily integration debugging
**Risk:** Low-Medium — all code is ported, issues will be endpoint mismatches or missing data

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

### Phase 5: UX Polish — COMPLETE

**Completed:**
- [x] Aside menu category navigation (resolves group paths, navigates to list views)
- [x] Dark mode fix in pageLayout (replaced hardcoded `background:#fff` with Tailwind classes)
- [x] Keyboard shortcuts on object view: Ctrl+S (save), Escape (cancel), Ctrl+1-9 (tabs)
- [x] Toast system: fixed positioning, vertical stacking with gap, increased z-index
- [x] Dashboard recent items: trackRecent() in panel.js, quick-nav chips on dashboard
- [x] Dense mode toggle: 3-mode cycle (normal/compact/comfortable), persisted in localStorage
- [x] Runtime null guards: navigateUp path check, navigateDown results check, inst.api typeof check

**Files changed (7):** asideMenu.js, router.js, object.js, list.js, panel.js, topMenu.js, main.css
**Tests:** 64 Vitest pass (panel test updated for new structure)

**Phase 5 continuation (same session):**
- [x] Responsive field grid breakpoints: added `641px-1024px` medium breakpoint with 3-col grid
- [x] Notification panel: new `notifications.js` component — polls `message.spool`, badge count, dismiss, auto-refresh
- [x] Notification wired into topMenu + router (startPolling on auth, stopPolling on logout)
- [x] Notification CSS: badge, panel dropdown, item list with dismiss button

**Remaining client-only items (deferred):**
- [ ] Dashboard drag-to-reorder, pinned items, save/load preferences via `data.data` JSON
- [ ] Extract `media` feature from core imports to lazy chunk (medium effort — many integration points)

### Phase 6-9: Backend-Dependent Features

- **Phase 6:** Model Form View (S2) — requires schema REST endpoints
- **Phase 7:** Form Editor / Designer (S3) — requires Phase 6 + `system.formDefinition`
- **Phase 8:** WebAuthn (S8) — requires `WebAuthnService.java`
- **Phase 9:** Access Requests (S11) — requires user input on approval concept

### Phase 10: Game Feature Validation — IN PROGRESS (Build Audit)

**Goal:** Runtime-test agent-generated game features. Deferred to benefit from stable common infrastructure.

**Build/Import Audit Results (2026-03-06):**
- [x] Vite build succeeds — all chunks compile (CardGameApp 392KB, Magic8App 171KB, tetris 9KB, wordGame 23KB)
- [x] No broken imports — all `from` paths resolve correctly
- [x] No `window.CardGame.*` / `window.Magic8.*` global remnants in active code
- [x] `g_application_path` — only used as local variable alias for `getAppPath()` in artPipeline.js/characters.js (works correctly)
- [x] `uwm` imports — all from correct `am7client.js` path
- [x] Only 1 `document.querySelector` in cardGame (chatUI scroll — legitimate)
- [x] magic8, games directories clean of legacy patterns
- [x] **Fixed:** CardGameApp.js now wires late-binding setters for overlays.js and cardFace.js (circular dep resolution was missing)
- [x] **Fixed:** `features/games.js` — hardcoded `background:#fff` replaced with Tailwind dark mode classes, added null guard on `dialog` in `layout()`
- [x] **Fixed:** `magic8/ai/SessionDirector.js` — replaced `applicationPath` import with `am7client.base()` (was bypassing late-binding pattern)

**Remaining (requires running backend on localhost:8443):**
- [ ] Test `/cardGame` — verify lazy load, game initialization, card rendering (34 agent-generated files)
- [ ] Test `/game` — verify Tetris and Word Game load and run
- [ ] Fix runtime errors (endpoint mismatches, missing state, event handler issues)
- [ ] Runtime-test magic8 full session flow against backend
- [ ] Verify CSS class references render correctly in all game views

### Phase 11: Performance + Polish

- [ ] SQLite WASM client cache (sql.js integration)
- [ ] Performance profiling and bundle optimization
- [ ] Accessibility audit (WCAG 2.1 AA)

---

## 9. E2E Test Coverage

**27 Playwright E2E tests** across 8 spec files:

| Spec | Tests | What's Covered |
|------|-------|----------------|
| `login.spec.js` | 4 | Page load, valid login, invalid login error, logout |
| `panel.spec.js` | 5 | Category cards, navigate to list, home button, dark mode, feature buttons |
| `list.spec.js` | 4 | List/empty state, toolbar, breadcrumb, filter input |
| `object.spec.js` | 3 | Add button, form fields, double-click opens view |
| `chat.spec.js` | 3 | Menu navigation, page content, library status check |
| `biometrics.spec.js` | 3 | Magic 8 navigation, Start Session button, config description |
| `testHarness.spec.js` | 3 | Menu navigation, UI components, Run Tests + categories |
| `workflows.spec.js` | 2 | Workflow handler registration on objectPage, no command-not-found warnings |

**Test infrastructure:**
- `e2e/helpers/fixtures.js` — Extended fixture with automatic console error capture
- `e2e/helpers/console.js` — Console capture + `assertNoConsoleErrors()` with IGNORED_PATTERNS
- `e2e/helpers/auth.js` — `login(page, opts)` + `screenshot(page, name)`
- `e2e/helpers/api.js` — Isolated `APIRequestContext` per setup/teardown call, `safeJson()` for robust parsing
- `playwright.config.js` — 4 workers, 1 retry, Chromium only, auto-starts Vite dev server

---

## 10. Build Output

```
Source files:  135 JS (71,000 lines) + 6 unit test files (64 tests) + 8 E2E spec files (27 tests)
Styles:        2 CSS files (main.css, pageStyle.css) -> 84KB bundled

Build output (dist/):
  index.js           410 KB / 110 KB gzip  (core + components + views + workflows)
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

## 11. Conversation Handoff Protocol

**MANDATORY:** At the end of every conversation that completes work on the Ux75 refactor:

1. **Update this file** — Mark completed tasks, update phase status, adjust test counts, note any new known issues.
2. **Update MEMORY.md** — Reflect current phase, key discoveries, and any new API gotchas.
3. **Provide a summary** — Brief recap of what was done, files changed, tests added/passing.
4. **Generate a kickoff prompt** — Write a ready-to-paste prompt the user can use to start the next phase in a new conversation. The prompt should include:
   - Which phase to work on and what it involves
   - Prerequisites (backend running, etc.)
   - Sub-tasks in priority order
   - Key context the new conversation needs (API gotchas, patterns, file locations)
   - The standing test requirements: "Make sure all Playwright tests pass, all browser console warnings and errors are addressed, and watch server logs for unexpected errors."

---

## 12. Quick Start Guide

**To resume Ux75 development in a new conversation:**

1. Read this file (`aiDocs/Ux75ImplementationPlan.md`) for current status and next phase
2. Read `Ux7Redesign.md` for design requirements (Sections 1-13)
3. Read `~/.claude/projects/.../memory/MEMORY.md` for API patterns and gotchas
4. Run `cd AccountManagerUx75 && npm run dev` to start Vite dev server (port 8899)
5. Run `npx vitest run` to verify unit tests pass (64 tests expected)
6. Run `npx playwright test` to verify E2E tests pass (27 tests expected, requires backend on 8443)

**Key files to read first:**
- `src/main.js` — Entry point, wires all modules
- `src/router.js` — Route definitions, `layout()` and `pageLayout()` helpers
- `src/features.js` — Feature manifest, `loadFeatureRoutes()`
- `src/core/pageClient.js` — App state, auth, WebSocket
- `src/core/am7client.js` — REST client, `uwm` login helper
- `src/views/object.js` — Object view with command dispatch + workflow handler registration
- `src/workflows/` — All 7 workflow modules (Phase 3.5b complete)

**For Phase 3.5c (Core Workflow Runtime Validation):**
- Backend must be running on localhost:8443
- Test workflow commands via browser: open charPerson → click Reimage, Summarize, etc.
- Test chat feature: navigate to /chat, create session, send message
- Fix integration issues (endpoint mismatches, missing data, am7sd config)
- Key reference: Ux7 `client/components/dialog.js` for expected behavior
- SD models: user can help select smaller/faster models for testing
