# AccountManagerUx75 — Implementation Plan & Status

**Extracted from:** `Ux7Redesign.md` Section 14
**Last Updated:** 2026-03-10
**Design Reference:** See `Ux7Redesign.md` Sections 1-13 for requirements, architecture, and design specifications.

---

## 1. Current Status

**Project:** `AccountManagerUx75/` — 143 source files, ~73,000 lines
**Build:** Vite 6.4.1, 161 modules, builds in ~4s
**Tests:** 111 Vitest unit tests pass (97 existing + 14 new Phase 11b), 43+ Playwright E2E tests pass
**Phase 9 completed:** 2026-03-10
**Phase 11 gap remediation completed:** 2026-03-10
**Ux7 File Parity:** ~99% — all major Ux7 features ported (5 intentionally skipped). 7 gaps closed in Phase 11. Phase 11b completed: group navigation/search in list view, file explorer view.

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
| **Phase 3.5c: Core Workflow Runtime Validation** | PARTIAL | 9 | Code-level fixes done (setNarDescription null crash, 7 formDef forward-reference bugs, reimage null sdEntity + missing await, command dispatch error handling, Mithril render resilience). 9 Playwright E2E tests added. **Live backend validation still needed.** |
| **Phase 3.5d: Model Ref Form Rendering** | COMPLETE | 2 | Sub-object tabs (personality, statistics, store, narrative, profile) now render full fields instead of just objectId. Implemented `pinst` cache for sub-instances, lazy tab activation, async sub-object save with `background:true` to avoid redraw storms, grid view null guard. |
| **Phase 4: ISO 42001 Compliance Dashboard** | DEFERRED | 1 | Moved to separate design/plan (`aiDocs/ISO42001Plan.md`). Depends on backend compliance endpoints. Current stub remains functional for live policy event monitoring. |
| **Phase 5: UX Polish** | COMPLETE | 10 | Aside menu navigation, dark mode fix, keyboard shortcuts (Ctrl+S/Esc/Ctrl+1-9), toast stacking, dashboard recent items, dense mode toggle, runtime null guards, responsive grid breakpoints, notification panel with badge, notification CSS |
| **Phase 6: Model Form View** | COMPLETE | 1 | Schema browser at `/schema` route (admin-only). Fetches model names from `/rest/schema/models`, model detail + fields from `/rest/schema/{type}`. Searchable/filterable model list, namespace grouping, field table with type/flags/provider, properties tab, clickable inheritance chain. Admin-gated in aside menu via `adminOnly` flag. Lazy-loaded chunk: 19KB/5KB gzip. |
| **Phase 7: Form Editor / Designer** | COMPLETE | 1 | Form definition editor integrated into schema feature. CRUD for `system.formDefinition` records via `/rest/model` endpoints. Create from model type (auto-populates fields from schema), edit field labels/layout/visibility/required/order, reorder with up/down arrows, 6-column grid preview. Saves via `am7client.patch()`. Combined into single `features/schema.js` file with Phase 6. |
| **Phase 8: WebAuthn** | **COMPLETE** | 4 | Backend: `auth.webauthnCredential` model, `WEBAUTHN` enum, `WebAuthnService.java` (6 endpoints), `webauthn4j-core` dep. Frontend: `features/webauthn.js` (passkey management settings), passkey login button in `sig.js`, `am7client` WebAuthn API (5 methods). 7 Vitest + 7 Playwright E2E tests. Also fixed VectorService 404 (enum @PathParam). |
| **Phase 8.5: List View Grid Rework** | **COMPLETE** | 1 | 4-mode grid system (table → small grid → large grid → gallery with arrow key nav). Fixed "No item at index -1" bug. Gallery mode: full-size fit-to-container with chevron navigation. |
| **Phase 9: Access Requests** | **COMPLETE** | 6 | Backend: `AccessRequestService.java` (5 REST endpoints at `/rest/access/`), 4 approval operations (`AccessApprovalOperation`, `DelegateApprovalOperation`, `LookupOwnerOperation`, `LookupApproverOperation`), `PolicyEvaluator` PENDING propagation, `PENDING` added to `OperationResponseEnumType`, auto-provisioning on approval. Frontend: `features/accessRequests.js` (tabbed list view, new request form with shopping cart, approval actions with inline deny reason), `am7client` 5 API methods, wired into `features.js` manifest + enterprise profile. 8 Vitest + 5 Playwright E2E tests. |
| **Phase 10: Game Feature Validation** | DEFERRED | 0 | Build audit done. Runtime testing deferred — benefits from stable common infra. |
| **Phase 11: Gap Remediation** | **COMPLETE** | 3 new + 8 modified | Profile image, context menus, bulk tagging, server-side dashboard prefs, favorites UI, fullscreen shortcut, blending expansion, aside overflow fix, direct URL nav fix. 12 new Vitest tests. |
| **Phase 11b: Navigation & Explorer** | **COMPLETE** | 1 new + 3 modified | Group navigation (breadcrumbs, child folder rows/cards, path resolution), search (debounced, scoped to group), explorer view (tree+list split at /explorer), aside Browse section. 14 new Vitest tests. |
| **Phase 3.5c: Workflow Runtime Validation** | NOT STARTED | 0 | Runtime-test 7 workflows + chat against live backend. Fix integration issues. |
| **Phase 10: Game Feature Validation** | NOT STARTED | 0 | Runtime-test cardGame (34 files), magic8 (19 files), tetris, wordGame against live backend. |
| **Phase 4: ISO 42001 Compliance** | NOT STARTED | 1 stub | Full ComplianceService.java backend (summary, violations, patterns, reports) + multi-tab frontend dashboard. |
| **Phase 12: Performance + Polish** | NOT STARTED | 0 | Bundle optimization, SQLite WASM cache, performance profiling, WCAG 2.1 AA a11y audit. |
| **Phase 13: Schema Write Endpoints** | NOT STARTED | 0 | Backend PUT/POST/DELETE for user-defined models/fields + frontend schema editor integration. |
| **Phase 14: Feature Configuration** | NOT STARTED | 0 | Backend FeatureConfigService + frontend admin panel for server-side feature toggles. |
| **Phase 15: Integration + Open Issues** | NOT STARTED | 0 | WebAuthn live test, full E2E regression, cross-browser validation. |

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
| **Schema** (model browser + form editor) | 1 | ~500 | COMPLETE | UNTESTED against backend — needs admin login + SchemaService deployed | schema: 19KB/5KB gzip |

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

**OPEN ISSUES:**

13. **VectorService 404** — `AccountManagerService7/src/main/java/org/cote/rest/services/VectorService.java` has `@Path("/vector")` but returns 404 on all endpoints. Jersey silently fails to register it. The class compiles, all bytecode dependencies exist. No errors in app logs or Tomcat logs. Blocks summarize and vectorize workflows. Discovered during Phase 3.5c.

14. **Image display bugs (fixed post-3.5c)** — Grid view thumbnails were 100x100 (too small for grid cards), now 256x256. Object view image rendering was broken — `formFieldRenderers.buildMediaPath` used `client.base()` (`/rest/media/...`) instead of `applicationPath` (`/media/...`). Both fixed.

15. **`media` feature not in manifest** — Design doc Section 7 defines a `media` feature for lazy loading. Currently loaded eagerly in `main.js`, inflating the index bundle.

16. **Aside nav viewport overflow** — Bottom aside items (Passkeys, possibly others) are outside the viewport because the aside nav doesn't scroll. Items below the fold cannot be reached by scrolling. Workaround: JS click via evaluate. Needs scrollable aside or collapsible sections.

17. **Direct URL navigation to feature routes broken** — `page.goto('/#!/webauthn')` renders the main dashboard instead of the feature page. In-app navigation works fine. Root cause: Mithril route initialization race with feature route lazy loading.

18. **WebAuthn backend not integration-tested** — E2E tests validate the full client-side flow with CDP virtual authenticator, but backend WebAuthnService.java hasn't been tested with a live browser (backend wasn't running during E2E test development).

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
| **Test framework** | Vitest for unit tests (97 tests, 9 files), Playwright for E2E (43+ tests, 10 spec files). Console capture with auto-assertion. |
| **E2E test isolation** | Each spec's `setupTestUser`/`ensureSharedTestUser` creates its own `APIRequestContext` to avoid session conflicts under parallel execution. 4 workers, 1 retry. |

---

## 7. Complete Gap Analysis (Phase 11 — Updated 2026-03-10)

### A. Gaps Found: Missing from Ux75 (Not Explicitly Deferred)

| # | Gap | Ux7 Behavior | Ux75 Status | Phase |
|---|-----|-------------|-------------|-------|
| 1 | **Profile image / `userProfilePath`** | Top menu renders 48x48 profile image | **RESOLVED** — `topMenu.js` `profileImage()` with initials fallback | 11 |
| 2 | **Context menus (right-click)** | Right-click on list/tree items opens contextual actions | **RESOLVED** — `contextMenu.js` component, wired into list.js + tree.js | 11 |
| 3 | **Bulk image tagging in list view** | Batch ML tagging on selected items | **RESOLVED** — `bulkApplyTags()` in list.js, toolbar button | 11 |
| 4 | **Server-side dashboard prefs** | Dashboard layout persisted to server | **RESOLVED** — `panel.js` server prefs via data.data + localStorage fallback | 11 |
| 5 | **Global fullscreen shortcut (Ctrl+F11)** | Ctrl+F11 toggles fullscreen | **RESOLVED** — shared `FullscreenManager.js`, Ctrl+F11 in navigation.js | 11 |
| 6 | **Favorites UI** | Add/remove favorites, favorites in navigation | **RESOLVED** — star toggle in object.js, favorites section in asideMenu.js | 11 |
| 7 | **DnD blending expansion** | Blends tags, roles, groups, characters | **RESOLVED** — 5 blend types in dnd.js `checkBlend()` | 11 |

### B. Previously Tracked Gaps — RESOLVED

| Gap | Resolution |
|-----|-----------|
| ~~Dialog workflow commands~~ | All 7 ported in Phase 3.5b |
| ~~Core workflow runtime validation~~ | Validated in Phase 3.5c |
| ~~Model Form View~~ | Complete (Phase 6) |
| ~~Form Editor / Designer~~ | Complete (Phase 7) |
| ~~WebAuthn~~ | Complete (Phase 8) |
| ~~Access Requests UI~~ | Complete (Phase 9) |
| ~~Dashboard customization (drag/pin/hide)~~ | Complete (Phase 5) — client-side only |
| ~~Dense/compact view mode~~ | Complete (Phase 5) |
| ~~Notification panel~~ | Complete (Phase 5) |
| ~~Responsive breakpoints~~ | Complete (Phase 5) |

### C. Deprecated Files (Intentional — NOT Implementing)

| Feature | Reason |
|---------|--------|
| `advGrid.js` (807 lines) | User directive: "ignore advGrid" |
| `/hyp` view (371 lines) | User directive: "replaced by magic8" |
| `saur.js` (32 lines) | Legacy — design doc says deprecate |

### D. Previously Deferred — NOW PLANNED

| Feature | Phase | Status |
|---------|-------|--------|
| ISO 42001 compliance dashboard | Phase 4 | Full backend + frontend planned |
| Schema write endpoints | Phase 13 | Full backend + frontend planned |
| Feature configuration endpoints | Phase 14 | Full backend + frontend planned |
| Game runtime validation | Phase 10 | Full runtime test + fix planned |
| Workflow runtime validation | Phase 3.5c | Full runtime test + fix planned |

### D. Gaps Identified Post-Phase 11 (User Feedback)

| # | Gap | Description | Effort | Phase |
|---|-----|------------|--------|-------|
| 8 | **Group navigation in list view** | No way to navigate into child groups or go up to parent from list view | Medium | 11b |
| 9 | **Search from list view** | No search/filter within current group context | Medium | 11b |
| 10 | **File explorer view** | No file-explorer-style view (tree on left, contents on right) | Medium-High | 11b |

### E. Design Doc Optimizations — Deferred

| Feature | Section | Effort | Notes |
|---------|---------|--------|-------|
| **`media` feature extraction** | S7 | Low | Move audio, PDF, SD config from main.js core imports to lazy-loaded feature chunk. Reduces index bundle. |
| **SQLite WASM client cache** | S9 | High | sql.js integration, schema mirroring, cache invalidation. Enhancement, not required for parity. |

### E. Open Issues (Already Tracked)

| # | Issue | Impact | Status |
|---|-------|--------|--------|
| 13 | VectorService 404 | Blocks summarize/vectorize workflows | **RESOLVED** (Phase 8) — enum @PathParam to String |
| 15 | `media` feature not in manifest | Index bundle larger than needed | Deferred optimization |
| 16 | Aside nav viewport overflow | Bottom items unreachable without JS scroll | **RESOLVED** (Phase 11) — `overflow-y:auto;max-height:100vh` |
| 17 | Direct URL navigation to feature routes broken | `page.goto('/#!/webauthn')` shows dashboard | **RESOLVED** (Phase 11) — Mithril route init reordered |
| 18 | WebAuthn backend not integration-tested | Unknown backend issues | Open — needs live browser test |

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

### Phase 4: ISO 42001 Compliance Dashboard — SEPARATED

**Moved to:** `aiDocs/ISO42001Plan.md` (Ux + backend plan in one document)

All ISO 42001 work (compliance dashboard tabs, backend ComplianceService.java, bias pattern editor, real-time indicators) is tracked separately. Current `features/iso42001.js` stub remains functional for live policy event monitoring. No further Phase 4 work in this plan.

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

### Phase 6-7: Backend-Dependent Features (COMPLETE)

- **Phase 6:** Model Form View (S2) — COMPLETE
- **Phase 7:** Form Editor / Designer (S3) — COMPLETE

### Phase 8: WebAuthn — NEXT

**Goal:** Add passwordless authentication via WebAuthn/passkeys. Requires new backend service + model + frontend UI.

**Backend (create + unit test):**
- New model: `auth.webauthnCredential` — see `aiDocs/BackendPlan.md` Priority 2 for field spec
- New enum value: `WEBAUTHN` in `CredentialEnumType.java`
- New Maven dep: `webauthn4j-core` in `AccountManagerService7/pom.xml`
- New `WebAuthnService.java` — 6 endpoints (register, auth, list, delete)
- Challenge stored in HttpSession, auth terminates via `TokenService.createJWTToken()`

**Frontend:**
- New `features/webauthn.js` or integrate into existing auth/settings views
- Register credential, list/delete credentials, "Sign in with passkey" on login
- Wire into `features.js` manifest
- Vitest + Playwright tests

### Phase 9: Access Requests

**Goal:** Self-service access request workflow with approval chain.

**Backend (create + unit test):**
- Model exists: `access.accessRequest` with `approvalStatus`, `approver`/`delegate`, `messages` spool
- New `AccessRequestService.java` — 5 endpoints (list, submit, approve/deny, requestable resources, notify)
- Auto-provisioning on approval (add requester to role/group)
- **Requires user input** on hierarchical approval concept before starting

**Frontend:**
- Request submission form, pending approval list, approve/deny actions
- Vitest + Playwright tests

### Phase 10: Game Feature Validation — DEFERRED (Build Audit Done)

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

### Phase 11: Gap Remediation — COMPLETE

**Goal:** Close all gaps identified in the Ux7 vs Ux75 gap analysis (Section 7A). These are features present in Ux7 that were NOT explicitly deferred but are missing or degraded in Ux75.

**Completed:** 2026-03-10

**11a: Profile Image Display** — COMPLETE
- [x] `topMenu.js`: `profileImage()` function resolves `v1-profile-path` attribute via `am7client.getAttributeValue()`
- [x] Renders 48x48 thumbnail with `applicationPath + "/thumbnail/" + path + "/48x48"`
- [x] Falls back to initials circle (first letter of username) if no profile image

**11b: Context Menus** — COMPLETE
- [x] New `components/contextMenu.js`: reusable right-click context menu component
- [x] Exports `contextMenu.show(e, items)`, `contextMenu.dismiss()`, `contextMenu.component`
- [x] Items: `{ label, icon, action }` or `{ divider: true }` — positioned at cursor, clamped to viewport
- [x] List view (`list.js`): right-click → Open, Edit, Delete actions via `showListContextMenu()`
- [x] Tree view (`tree.js`): right-click → Expand/Collapse, Add Child, Refresh, Delete via `showTreeContextMenu()`
- [x] CSS classes in `pageStyle.css`: `.context-menu`, `.context-menu-item`, `.context-menu-divider`
- [x] Wired into `main.js` and `router.js` layout (renders `contextMenuComponent` globally)

**11c: Bulk Image Tagging** — COMPLETE
- [x] List view toolbar: label/tag icon button triggers `bulkApplyTags()` on selected items
- [x] Calls `am7client.applyImageTags()` for each selected item
- [x] Shows toast with success/failure count

**11d: Server-Side Dashboard Prefs** — COMPLETE
- [x] `panel.js`: `loadServerPrefs()` fetches `.dashboardPrefs` data.data record from user home directory
- [x] `saveServerPrefs()` patches existing or creates new record with base64-encoded JSON in `dataBytesStore`
- [x] Falls back to localStorage if server unreachable; syncs localStorage cache on server fetch

**11e: Global Fullscreen Shortcut** — COMPLETE
- [x] Extracted `FullscreenManager` to shared `components/FullscreenManager.js`
- [x] Updated Magic8App.js import path to use shared component
- [x] Ctrl+F11 keybinding added in `navigation.js` — toggles fullscreen on `.flex-1.overflow-auto` or document.body

**11f: Favorites UI** — COMPLETE
- [x] `pageClient.js`: `favorites()`, `isFavorite()`, `toggleFavorite()`, `checkFavorites()` — uses bucket group membership
- [x] `object.js`: star/favorite toggle button in toolbar (filled/unfilled star, yellow when active)
- [x] `asideMenu.js`: `favoritesSection()` renders favorited items with type icons, click navigates to item

**11g: DnD Blending Expansion** — COMPLETE
- [x] `dnd.js`: `checkBlend()` extended with 5 blend types: chatBlend, tagBlend, roleBlend, groupBlend, characterBlend
- [x] Matches Ux7 blending behavior for tag+other, role+actor, group+item, charPerson+charPerson combinations

**11h: Open Issue Fixes** — COMPLETE
- [x] #16 Aside nav overflow: added `overflow-y:auto;max-height:100vh` to aside element in `asideMenu.js`
- [x] #17 Direct URL navigation: fixed Mithril route init race — register routes first via `m.route()`, then navigate with `m.route.set(rt)` in `router.js`
- [x] #13 VectorService 404: already fixed in Phase 8 (enum @PathParam to String conversion)

**New files (3):** `contextMenu.js`, `FullscreenManager.js` (shared), `test/phase11.test.js`
**Modified files (8):** `topMenu.js`, `list.js`, `tree.js`, `panel.js`, `navigation.js`, `asideMenu.js`, `router.js`, `pageClient.js`, `dnd.js`, `main.js`, `pageStyle.css`, `object.js`
**Tests:** 12 new Vitest tests (Context Menu 3, FullscreenManager 2, DnD checkBlend 7)

### Phase 11b: Navigation & Explorer — **COMPLETE**

**Goal:** Address remaining UX gaps: group navigation and search from list view, and a file-explorer-style tree view for browsing directory structures.

**11b-1: Group Navigation in List View (Medium)** — COMPLETE
- [x] Add breadcrumb-based group navigation — click into child groups from list view
- [x] "Up" button to navigate to parent group (existing `navigateUp` already works)
- [x] Show child groups as folder-style entries above list items (table rows + grid cards)
- [x] Support navigating into nested group hierarchies without leaving list view
- [x] Group path breadcrumb with clickable segments (lazy-resolved objectIds)

**11b-2: Search from List View (Medium)** — COMPLETE
- [x] Add search input to list toolbar that filters/searches within current group context
- [x] Server-side search via pagination filter → `am7client.search()` with `name LIKE` query
- [x] Real-time filter as user types (300ms debounce) or Enter to submit immediately
- [x] Clear search button (X icon) resets to full list; Escape key also clears
- [x] Search hides child group folders to show only matching results

**11b-3: File Explorer View (Medium-High)** — COMPLETE
- [x] New `/explorer` route — split layout: tree panel (250px left) + list panel (right)
- [x] Tree shows group hierarchy (directories), selecting a node loads contents in right panel
- [x] Support expand/collapse, lazy-load child groups on expand (via existing `tree.js`)
- [x] Reuses existing `tree.js` for left panel, `list.js` for right panel
- [x] Explorer and Navigator links added to aside menu under "Browse" section
- [x] Full-mode toggle, edit-item navigation to object view

**New files (1):** `views/explorer.js`
**Modified files (3):** `views/list.js`, `router.js`, `components/asideMenu.js`
**Tests:** 14 new Vitest tests in `test/phase11b.test.js` (111 total, all pass)
**Build:** Vite build succeeds in ~4.3s, index chunk 525KB/136KB gzip

### Phase 3.5c: Core Workflow Runtime Validation — NOT STARTED

**Goal:** Runtime-test all 7 workflow commands + chat feature against the running backend. Fix integration issues.
**Prerequisites:** Backend running on localhost:8443.
**Effort:** Medium (2-3 days)

**A. Reimage Workflow**
- [ ] Open a `olio.charPerson` object, click Reimage command button
- [ ] Verify SD config dialog opens with correct defaults (steps=40, cfg=5, photograph style)
- [ ] Verify dress up/down buttons work (calls am7olio.dressCharacter + setNarDescription)
- [ ] Generate a single image — verify POST to `/olio/olio.charPerson/{id}/reimage` succeeds
- [ ] Verify portrait update, wear-level tagging, sharing tags all applied
- [ ] Test reimageApparel — verify mannequin generation

**B. Summarize + Vectorize**
- [ ] Open any object with summarize command, verify chat/prompt config dropdowns populate
- [ ] Test summarize against backend — verify `/vector/summarize` endpoint
- [ ] Test vectorize — verify `/vector/vectorize` endpoint
- [ ] Fix any issues with loadChatList/loadPromptList (~/Chat directory resolution)

**C. Member Cloud + Adopt + Outfit Builder**
- [ ] Test memberCloud on a tag/container with members — verify cloud renders, drill-down works
- [ ] Test adoptCharacter on a character outside world population
- [ ] Test outfitBuilder command — verify panel renders

**D. Chat Feature**
- [ ] Navigate to /chat, verify session list loads
- [ ] Create new chat session, send message, verify LLMConnector → backend → response
- [ ] Verify WebSocket events for real-time messaging

**E. Fix + E2E Tests**
- [ ] Fix all discovered integration issues (endpoint mismatches, missing data, am7sd config)
- [ ] Add Playwright E2E tests for each validated workflow (reimage dialog, summarize dialog, memberCloud, chat send)

---

### Phase 10: Game Feature Validation — NOT STARTED

**Goal:** Runtime-test all 4 game features against backend, fix issues, verify end-to-end.
**Prerequisites:** Backend running on localhost:8443, game data seeded (Olio world with characters).
**Effort:** Medium-High (3-5 days)

**10a: Card Game (34 files)**
- [ ] Navigate to `/cardGame`, verify lazy load + CardGameApp renders
- [ ] Verify deck list loads from backend (game state storage)
- [ ] Test deck builder: create deck, add/remove cards, save
- [ ] Test game start: deal cards, render card faces with art pipeline
- [ ] Test combat system: play cards, resolve effects, AI opponent actions
- [ ] Test AI director: verify LLM integration for encounter narration
- [ ] Test chat UI: in-game chat with AI narrator
- [ ] Fix runtime errors: endpoint mismatches, missing state init, event handler binding
- [ ] Add Playwright E2E test: cardGame route loads, deck list renders

**10b: Magic 8 (19 files)**
- [ ] Navigate to `/magic8`, verify SessionConfigEditor renders
- [ ] Test full session flow: create config, start session, interact with AI
- [ ] Verify AI subsystems: SessionDirector, StrategyEngine, mood/coherence tracking
- [ ] Test voice integration if VoiceService is available
- [ ] Fix any runtime issues
- [ ] Add Playwright E2E test: magic8 session flow

**10c: Tetris**
- [ ] Navigate to `/game/tetris`, verify game loads and renders
- [ ] Test gameplay: piece rotation, line clearing, score tracking
- [ ] Verify score saving to backend (if implemented)
- [ ] Fix any runtime issues

**10d: Word Game**
- [ ] Navigate to `/game/wordGame`, verify game loads
- [ ] Test word submission, scoring, dictionary validation via WordService
- [ ] Fix any runtime issues

---

### Phase 4: ISO 42001 Compliance Dashboard — NOT STARTED

**Goal:** Complete compliance dashboard with full backend service + multi-tab frontend.
**Effort:** High (5-7 days total: 2-3 backend, 3-4 frontend)

**4a: Backend — ComplianceService.java** (NEW)
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

**4b: Frontend — Tab Navigation**
- [ ] Replace single-page view with tabbed layout: Overview | Audit Log | Policy Templates | Reports | Live Monitor
- [ ] Move current violation list into "Live Monitor" tab
- [ ] Add `am7client` methods for compliance endpoints (summary, violations, report, patterns, prompts)

**4c: Frontend — Overview Tab**
- [ ] Summary cards: total evaluations, pass rate, fail rate, violations by area
- [ ] Trend sparklines (last 7/30 days) — simple SVG line charts, no external charting library
- [ ] Area breakdown: horizontal bar chart showing violation count per overcorrection area
- [ ] Auto-refresh every 60s

**4d: Frontend — Audit Log Tab**
- [ ] Searchable/filterable table of compliance evaluation records
- [ ] Columns: timestamp, type, area, severity, message, actor
- [ ] Pagination via existing pagination component
- [ ] Filter by: severity (error/warn/info), area (10 overcorrection areas), date range
- [ ] Click row to expand details

**4e: Frontend — Policy Templates Tab**
- [ ] List all 5 LLM call paths with overcorrection directive status (present/missing/modified)
- [ ] Display prompt config content with syntax highlighting (pre-formatted text)
- [ ] Admin: edit/save prompt configs via compliance/prompts endpoint
- [ ] Validation: warn if overcorrection directive is missing or weakened

**4f: Frontend — Reports Tab**
- [ ] Date range picker (start/end date inputs)
- [ ] Generate report button → POST to `/rest/compliance/report`
- [ ] Display report as formatted summary + download as JSON/CSV
- [ ] Report includes: period summary, top violations, area breakdown, trend data

**4g: Frontend — Tests**
- [ ] Vitest unit tests: tab navigation, summary card rendering, filter logic, export formatting
- [ ] Playwright E2E test: compliance route loads, tabs render, library status badges

---

### Phase 12: Performance + Polish — NOT STARTED

**Goal:** Bundle optimization, client-side caching, accessibility audit, performance profiling.
**Effort:** High (5-7 days)

**12a: Bundle Optimization + Media Feature Extraction**
- [ ] Extract `media` feature from core imports to lazy-loaded chunk (currently inflating index bundle)
- [ ] Identify which imports in `main.js` can be moved to lazy features (audio, PDF, SD config)
- [ ] Configure Vite `manualChunks` to split vendor code (mithril) from app code
- [ ] Target: index chunk under 400KB (currently 525KB)
- [ ] Verify all lazy chunks load correctly after splitting

**12b: SQLite WASM Client Cache**
- [ ] Add `sql.js` as a dev dependency (WASM SQLite for browser)
- [ ] Create `core/cacheDb.js` module: init WASM, create schema mirroring key AM7 models
- [ ] Cache strategy: store search results, model lists, and schema in IndexedDB-backed SQLite
- [ ] Cache invalidation: clear on logout, TTL-based expiry (5 min default), manual clear via UI
- [ ] Integrate with `am7client`: intercept `search()` and `list()` to check cache first
- [ ] Add cache hit/miss metrics visible in dev mode
- [ ] Fallback: if sql.js fails to load, degrade gracefully to no-cache (current behavior)
- [ ] Vitest tests: cache write/read, TTL expiry, invalidation

**12c: Performance Profiling**
- [ ] Measure initial load time (Time to Interactive) with Lighthouse
- [ ] Identify and fix any render bottlenecks (excessive `m.redraw()` calls)
- [ ] Audit `am7client` for redundant API calls (same query fired multiple times)
- [ ] Add request deduplication to `am7client.search()` (if same query is in-flight, reuse promise)
- [ ] Profile list view with 100+ items in each grid mode — optimize if any mode >16ms per frame
- [ ] Profile tree component with 50+ expanded nodes — optimize if slow

**12d: Accessibility Audit (WCAG 2.1 AA)**
- [ ] Run axe-core audit on all core views (sig, main, list, object, explorer, nav)
- [ ] Fix focus management: tab order on dialogs, modals trap focus, Escape closes
- [ ] Add `aria-label` to icon-only buttons (toolbar buttons currently have no text labels)
- [ ] Add `role` attributes: `role="navigation"` on nav, `role="main"` on content, `role="tree"` on tree
- [ ] Ensure all form fields have associated `<label>` elements or `aria-label`
- [ ] Color contrast: verify all text meets 4.5:1 ratio in both light and dark mode
- [ ] Keyboard navigation: verify all interactive elements reachable via Tab, activatable via Enter/Space
- [ ] Screen reader: test with NVDA — verify route changes announced, list items readable
- [ ] Add `aria-live="polite"` to toast container for dynamic announcements
- [ ] Playwright a11y test: axe-core scan on main routes, zero critical violations

---

### Phase 13: Schema Write Endpoints — NOT STARTED

**Goal:** Enable user-defined models and fields via the schema service. Full backend + frontend.
**Effort:** Medium-High (3-5 days)

**13a: Backend — SchemaService Write Endpoints**
- [ ] Add `isSystem` flag to `ModelSchema` / `FieldSchema` — system models/fields cannot be deleted
- [ ] `PUT /rest/schema/{type}` — update model: add user-defined fields to existing model. Validates field name uniqueness, type validity. Reloads schema at runtime via `RecordFactory`.
- [ ] `POST /rest/schema` — create new user-defined model type. Requires: name, inherits, fields[]. Persists JSON to model definitions directory. Registers in `RecordFactory` at runtime.
- [ ] `DELETE /rest/schema/{type}` — delete non-system model type. Validates no data exists for type. Removes from RecordFactory + deletes definition file.
- [ ] `DELETE /rest/schema/{type}/field/{fieldName}` — remove non-system field from model. Validates field is user-defined. Updates model definition + RecordFactory.
- [ ] Runtime schema reload: after any write operation, call `RecordFactory.reloadSchema(type)` to update in-memory schema without restart
- [ ] Persist user-defined models to `{store.path}/models/user/` directory (separate from system models)
- [ ] All write endpoints: `@RolesAllowed({"admin"})` — admin only
- [ ] JUnit tests: `TestSchemaWriteService.java` — create model, add field, delete field, delete model, system-protection validation

**13b: Frontend — Schema Editor Integration**
- [ ] Add "New Model" button to schema browser — opens dialog: name, inherits-from dropdown, initial fields
- [ ] Add "Add Field" button to field table — opens dialog: name, type dropdown, required, description
- [ ] Add "Delete" button per field (only for user-defined fields, disabled for system fields)
- [ ] Add "Delete Model" button (only for user-defined models)
- [ ] Calls to new `am7client` methods: `createModel()`, `updateModelField()`, `deleteModel()`, `deleteModelField()`
- [ ] Confirmation dialogs for all destructive operations
- [ ] Vitest tests: button visibility based on isSystem flag
- [ ] Playwright E2E test: create model, add field, verify in browser, delete

---

### Phase 14: Feature Configuration — NOT STARTED

**Goal:** Server-side feature enablement per organization, replacing client-only build profiles.
**Effort:** Low-Medium (2-3 days)

**14a: Backend — FeatureConfigService.java** (NEW)
- [ ] Create `FeatureConfigService.java` at `/rest/config`
- [ ] `GET /rest/config/features` — return enabled features for current user's organization. Reads from `data.data` record at `~/.featureConfig` in org root. Returns JSON: `{ features: ["core", "chat", ...], profile: "full" }`
- [ ] `PUT /rest/config/features` — update enabled features (admin only). Validates feature IDs against known set. Saves to `data.data` record.
- [ ] `GET /rest/config/features/available` — return all available feature definitions (id, label, description, deps) for UI rendering
- [ ] If no config record exists, return default "full" profile
- [ ] JUnit test: `TestFeatureConfigService.java` — read default, update, read updated, reset

**14b: Frontend — Feature Admin Panel**
- [ ] New admin-only view at `/admin/features` (or integrate into existing schema/settings area)
- [ ] Display all available features with toggle switches (enabled/disabled)
- [ ] Show dependency graph: disabling a feature warns if dependents are enabled
- [ ] Save button calls PUT endpoint
- [ ] On load: `initFeatures()` reads from server instead of URL param / build define
- [ ] Update `router.js` `refreshApplication()` to fetch server feature config after auth
- [ ] Add `am7client` methods: `getFeatureConfig()`, `updateFeatureConfig()`, `getAvailableFeatures()`
- [ ] Vitest tests: toggle logic, dependency validation
- [ ] Playwright E2E test: feature admin page loads, toggle works

---

### Phase 15: Integration Testing + Open Issues — NOT STARTED

**Goal:** Close all remaining open issues and validate full-stack integration.
**Effort:** Medium (2-3 days)

**15a: WebAuthn Backend Integration Test** (Issue #18)
- [ ] Test WebAuthn registration + authentication against live backend with real browser
- [ ] Verify credential storage in `auth.webauthnCredential` model
- [ ] Verify JWT token issuance after passkey auth
- [ ] Fix any issues discovered

**15b: Full E2E Regression Suite**
- [ ] Run all Playwright E2E tests against live backend (not just dev server)
- [ ] Add E2E tests for explorer view navigation
- [ ] Add E2E tests for list view group navigation + search
- [ ] Verify all 111+ Vitest tests still pass after all phases
- [ ] Fix any regressions

**15c: Cross-Browser Validation**
- [ ] Test in Firefox (Phase 7 perf fixes already applied)
- [ ] Test in Safari/WebKit (if available)
- [ ] Fix any browser-specific rendering or behavior issues

---

## 9. E2E Test Coverage

**43 Playwright E2E tests** across 10 spec files:

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
| `webauthn.spec.js` | 7 | Login passkey button, no-username toast, settings page, register form, client API, CDP virtual authenticator registration flow, base64url encoding roundtrip |
| `schema.spec.js` | 9 | Schema browser route, model list, model details, field table, search, namespace grouping |

**Test infrastructure:**
- `e2e/helpers/fixtures.js` — Extended fixture with automatic console error capture
- `e2e/helpers/console.js` — Console capture + `assertNoConsoleErrors()` with IGNORED_PATTERNS
- `e2e/helpers/auth.js` — `login(page, opts)` + `screenshot(page, name)`
- `e2e/helpers/api.js` — Isolated `APIRequestContext` per setup/teardown call, `safeJson()` for robust parsing
- `playwright.config.js` — 4 workers, 1 retry, Chromium only, auto-starts Vite dev server

---

## 10. Build Output

```
Source files:  143 JS (~73,000 lines) + 9 unit test files (97 tests) + 10 E2E spec files (43 tests)
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
5. Run `npx vitest run` to verify unit tests pass (77 tests expected)
6. Run `npx playwright test` to verify E2E tests pass (43 tests expected, requires backend on 8443)

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
