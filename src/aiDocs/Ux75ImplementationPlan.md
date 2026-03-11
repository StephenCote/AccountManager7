# AccountManagerUx75 — Implementation Plan & Status

**Extracted from:** `Ux7Redesign.md` Section 14
**Last Updated:** 2026-03-10
**Design Reference:** See `Ux7Redesign.md` Sections 1-13 for requirements, architecture, and design specifications.

---

## 1. Current Status

**Project:** `AccountManagerUx75/` — 143 source files, ~73,000 lines
**Build:** Vite 6.4.1, 161 modules, builds in ~4s
**Tests:** 147 Vitest unit tests pass, 48+ Playwright E2E tests pass
**Phase 9 completed:** 2026-03-10
**Phase 11 gap remediation completed:** 2026-03-10
**Phase 15 completed:** 2026-03-11
**Ux7 File Parity:** ~99% — all major Ux7 features ported (5 intentionally skipped). 7 gaps closed in Phase 11. Phase 11b completed: group navigation/search in list view, file explorer view. Phase 15 complete: all E2E regression + cross-browser validation done.

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
| **Phase 3.5c: Core Workflow Runtime Validation** | **COMPLETE** | 11 E2E | Code-level fixes (setNarDescription null crash, 7 formDef forward-reference bugs, reimage null sdEntity + missing await, command dispatch error handling, Mithril render resilience). 11 Playwright E2E tests in `workflowRuntime.spec.js`: handler registration, command buttons, reimage dialog (steps/dress buttons/generate), summarize dialog (chunk select), adopt dialog (realm/location), outfit builder, chat route. `setupWorkflowTestData` helper (charPerson + data.data test data). VectorService 404 already fixed in Phase 8. |
| **Phase 3.5d: Model Ref Form Rendering** | COMPLETE | 2 | Sub-object tabs (personality, statistics, store, narrative, profile) now render full fields instead of just objectId. Implemented `pinst` cache for sub-instances, lazy tab activation, async sub-object save with `background:true` to avoid redraw storms, grid view null guard. |
| **Phase 4: ISO 42001 Compliance Dashboard** | REMOVED | — | Moved out of this plan entirely. Tracked separately in `aiDocs/ISO42001Plan.md`. |
| **Phase 5: UX Polish** | COMPLETE | 10 | Aside menu navigation, dark mode fix, keyboard shortcuts (Ctrl+S/Esc/Ctrl+1-9), toast stacking, dashboard recent items, dense mode toggle, runtime null guards, responsive grid breakpoints, notification panel with badge, notification CSS |
| **Phase 6: Model Form View** | COMPLETE | 1 | Schema browser at `/schema` route (admin-only). Fetches model names from `/rest/schema/models`, model detail + fields from `/rest/schema/{type}`. Searchable/filterable model list, namespace grouping, field table with type/flags/provider, properties tab, clickable inheritance chain. Admin-gated in aside menu via `adminOnly` flag. Lazy-loaded chunk: 19KB/5KB gzip. |
| **Phase 7: Form Editor / Designer** | COMPLETE | 1 | Form definition editor integrated into schema feature. CRUD for `system.formDefinition` records via `/rest/model` endpoints. Create from model type (auto-populates fields from schema), edit field labels/layout/visibility/required/order, reorder with up/down arrows, 6-column grid preview. Saves via `am7client.patch()`. Combined into single `features/schema.js` file with Phase 6. |
| **Phase 8: WebAuthn** | **COMPLETE** | 4 | Backend: `auth.webauthnCredential` model, `WEBAUTHN` enum, `WebAuthnService.java` (6 endpoints), `webauthn4j-core` dep. Frontend: `features/webauthn.js` (passkey management settings), passkey login button in `sig.js`, `am7client` WebAuthn API (5 methods). 7 Vitest + 7 Playwright E2E tests. Also fixed VectorService 404 (enum @PathParam). |
| **Phase 8.5: List View Grid Rework** | **COMPLETE** | 1 | 4-mode grid system (table → small grid → large grid → gallery with arrow key nav). Fixed "No item at index -1" bug. Gallery mode: full-size fit-to-container with chevron navigation. |
| **Phase 9: Access Requests** | **COMPLETE** | 6 | Backend: `AccessRequestService.java` (5 REST endpoints at `/rest/access/`), 4 approval operations (`AccessApprovalOperation`, `DelegateApprovalOperation`, `LookupOwnerOperation`, `LookupApproverOperation`), `PolicyEvaluator` PENDING propagation, `PENDING` added to `OperationResponseEnumType`, auto-provisioning on approval. Frontend: `features/accessRequests.js` (tabbed list view, new request form with shopping cart, approval actions with inline deny reason), `am7client` 5 API methods, wired into `features.js` manifest + enterprise profile. 8 Vitest + 5 Playwright E2E tests. |
| **Phase 10: Game Feature Validation** | **COMPLETE** | 9 E2E | Route loads, lazy chunk verification, game interactions (tetris start → active piece, word game board, card game builder/deck-list), no uncaught JS errors across all 4 routes, Magic8 config sections, Magic8 JS error check. |
| **Phase 11: Gap Remediation** | **COMPLETE** | 3 new + 8 modified | Profile image, context menus, bulk tagging, server-side dashboard prefs, favorites UI, fullscreen shortcut, blending expansion, aside overflow fix, direct URL nav fix. 12 new Vitest tests. |
| **Phase 11b: Navigation & Explorer** | **COMPLETE** | 1 new + 3 modified | Group navigation (breadcrumbs, child folder rows/cards, path resolution), search (debounced, scoped to group), explorer view (tree+list split at /explorer), aside Browse section. 14 new Vitest tests. |
| **Phase 3.5c: Workflow Runtime Validation** | **COMPLETE** | 11 E2E | Runtime-tested 7 workflows + chat. `workflowRuntime.spec.js` (11 tests). VectorService 404 fixed. |
| **Phase 10: Game Feature Validation** | **COMPLETE** | 9 E2E | Runtime-test cardGame (34 files), magic8 (19 files), tetris, wordGame against live backend. |
| **Phase 12: Performance + Polish** | **COMPLETE** | 26 Vitest + 5 E2E | Bundle optimization (526→451KB), SQLite WASM cache (`cacheDb.js`), request dedup, parallel bulkApplyTags, WCAG 2.1 AA a11y audit (ARIA labels, roles, aria-live, keyboard nav). |
| **Phase 13: Schema Write Endpoints** | **COMPLETE** | 19 Vitest + 6 JUnit | Backend PUT/POST/DELETE for user-defined models/fields + frontend schema editor integration. |
| **Phase 14: Feature Configuration** | **COMPLETE** | 17 Vitest + 4 JUnit + 5 E2E | Backend `FeatureConfigService.java` (3 endpoints at `/rest/config`), frontend admin panel at `/admin/features`, server-side feature config in `router.js`, `am7client` 3 API methods, dependency graph UI. |
| **Phase 15: Integration + Open Issues** | **COMPLETE** | 0 Vitest + 21 E2E new | 15a: WebAuthn REST API integration tests (6 tests, `webauthn.spec.js`). 15b: Explorer view E2E (`explorer.spec.js`, 7 tests), list group nav + search (`list.spec.js`, +9 tests). 15c: Firefox project added to `playwright.config.js`; CDP-only test skip-annotated. 173 Vitest pass. |
| **Phase 16: Picture Book** | **PLANNED** | — | LLM scene extraction, work-scoped charPerson creation + outfit, scene image generation, picture book viewer. See Section 8 for full spec. |

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

13. **VectorService 404** — RESOLVED (Phase 8). Changed enum `@PathParam` to `String` with `ChunkEnumType.valueOf(str.toUpperCase())`. `RestServiceConfig` classpath scan (`packages("org.cote.rest.services")`) now registers VectorService correctly.

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
| Schema write endpoints | Phase 13 | Full backend + frontend planned |
| Feature configuration endpoints | Phase 14 | Full backend + frontend planned |
| Game runtime validation | Phase 10 | Full runtime test + fix planned |
| ~~Workflow runtime validation~~ | Phase 3.5c | **COMPLETE** (11 E2E tests, workflowRuntime.spec.js) |

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

### Phase 3.5c: Runtime Validation of Core Workflows — COMPLETE (2026-03-11)

**Goal:** Runtime-test workflow commands, sdConfig, olio operations, and chat against the running backend. Fix integration issues. This validates the common infrastructure that everything else depends on.

**Prerequisites:** Backend running on localhost:8443.

**A. SD Config + Reimage Workflow Validation**
- [x] SD config dialog opens with correct defaults (steps=20, cfg=5, photograph style)
- [x] Dress Down / Dress Up buttons render on charPerson (call am7olio.dressCharacter + setNarDescription)
- [x] Generate button present; POST to `/olio/olio.charPerson/{id}/reimage` (best-effort, requires SD backend)
- [x] Portrait update, wear-level tagging, sharing tags wired in generate action
- [x] reimageApparel command wired (mannequin generation path)
- [x] Fixed: null guard on sdEntity when fetchTemplate returns null
- [x] Fixed: missing `await` on fetchTemplate call

**B. Summarize + Vectorize Validation**
- [x] Summarize dialog opens with chunk type `select` (always rendered)
- [x] Chat/prompt config dropdowns populate if `~/Chat` directory has configs
- [x] loadChatList/loadPromptList handle missing ~/Chat gracefully (return [])
- [x] VectorService endpoints registered — fixed in Phase 8 (enum @PathParam → String)
- [x] `/vector/summarize` and `/vector/vectorize` endpoints route correctly

**C. Member Cloud + Adopt Validation**
- [x] adoptCharacter dialog opens with realm `select` and "Adopt" button
- [x] "Current location" text rendered from character.groupPath
- [x] memberCloud command wired
- [x] POST to `/game/adopt/{id}` wired in confirm action

**D. Outfit Builder Validation**
- [x] outfitBuilder dialog opens (renders "not loaded" message if am7olio absent, graceful)
- [x] OutfitBuilderPanel / PieceEditorPanel delegated to am7olio when available

**E. Chat Feature Runtime Validation**
- [x] `/chat` route loads without crash
- [x] Session list or empty state visible on load
- [x] Library check on oninit does not redirect to error

**F. E2E Tests — COMPLETE (`e2e/workflowRuntime.spec.js`, 11 tests)**
- [x] all workflow handlers registered on objectPage
- [x] charPerson shows all expected command buttons
- [x] data.data shows reimage command button
- [x] adoptCharacter dialog best-effort (click + dialog check)
- [x] reimage dialog best-effort
- [x] outfitBuilder dialog best-effort
- [x] chat page loads
- [x] reimage dialog fields (steps=20, Dress Down/Up, Generate)
- [x] summarize dialog (chunk select + Summarize button)
- [x] adoptCharacter dialog content (realm select, Adopt button, Current location)
- [x] no command-not-found warnings on charPerson/data.data

**Estimated effort:** MEDIUM — primarily integration debugging
**Risk:** Low-Medium — all code is ported, issues will be endpoint mismatches or missing data

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

### Phase 3.5c: Core Workflow Runtime Validation — COMPLETE (2026-03-11)

**Goal:** Runtime-test all 7 workflow commands + chat feature against the running backend. Fix integration issues.

**Code-level fixes applied:**
- setNarDescription null crash guard (am7olio not loaded)
- 7 formDef forward-reference bugs (commands referencing undefined handlers)
- reimage null sdEntity + missing await on fetchTemplate
- Command dispatch error handling (try/catch + toast on handler failure)
- Mithril render resilience (null guard on renderContent)
- VectorService 404: already fixed in Phase 8 (enum `@PathParam` → String with `.valueOf()`)

**A. Reimage Workflow**
- [x] SD config dialog opens (steps=20, cfg=5, photograph style defaults)
- [x] Dress Down / Dress Up buttons render on charPerson dialog
- [x] Generate button present in dialog
- [x] POST to `/olio/olio.charPerson/{id}/reimage` (best-effort, requires SD backend)
- [x] reimageApparel command wired (mannequin path)

**B. Summarize + Vectorize**
- [x] Summarize dialog opens with chunk type `select` element
- [x] Summarize action button visible in dialog
- [x] Chat/prompt config dropdowns populate if `~/Chat` directory exists
- [x] VectorService endpoints registered (fixed Phase 8)
- [x] loadChatList/loadPromptList handle missing ~/Chat gracefully (return [])

**C. Member Cloud + Adopt + Outfit Builder**
- [x] adoptCharacter dialog opens with realm `select` and "Adopt" button
- [x] "Current location" text rendered from `character.groupPath`
- [x] outfitBuilder dialog opens (shows "component not loaded" if am7olio absent)
- [x] memberCloud command wired

**D. Chat Feature**
- [x] `/chat` route loads without crash
- [x] Chat page shows session list or empty state
- [x] Library check runs without redirect to error

**E. E2E Tests — COMPLETE**
- [x] `e2e/workflowRuntime.spec.js` — 11 tests:
  - all workflow handlers registered on objectPage
  - charPerson shows all expected command buttons
  - data.data shows reimage command button
  - adoptCharacter dialog (best-effort)
  - reimage dialog (best-effort)
  - outfitBuilder dialog (best-effort)
  - chat page loads
  - reimage dialog fields (steps=20, dress buttons, generate button)
  - summarize dialog (chunk select + Summarize button)
  - adoptCharacter dialog content (realm select, Adopt button, Current location)
  - no command-not-found warnings on charPerson/data.data
- [x] `helpers/api.js` — `setupWorkflowTestData()` creates charPerson + data.data test objects

---

### Phase 10: Game Feature Validation — COMPLETE (2026-03-11)

**Goal:** Runtime-test all 4 game features against backend, fix issues, verify end-to-end.

**10a: Route + Build Verification (done in prior audit)**
- [x] All 4 game routes load without console errors (lazy chunks load correctly)
- [x] CardGameApp (392KB), Magic8App (171KB), tetris (9KB), wordGame (23KB) all compile
- [x] No broken imports, no `window.CardGame.*` globals, no `page.member()` calls
- [x] Fixed: CardGameApp.js late-binding setters for overlays/cardFace
- [x] Fixed: games.js dark mode classes + null guard on dialog
- [x] Fixed: Magic8App SessionDirector `am7client.base()` instead of applicationPath import

**10b: Card Game E2E**
- [x] Navigate to `/cardGame`, lazy load + CardGameApp renders ("Card Game" header visible)
- [x] After backend deck-list call: shows builder step tabs (no decks) or "New Deck" (decks exist)
- [x] No uncaught JS errors during card game load

**10c: Magic 8 E2E**
- [x] Navigate to `/magic8`, SessionConfigEditor renders ("Magic8 Session" heading)
- [x] "Start Session" button visible
- [x] "Configure your immersive experience" description visible
- [x] "Biometric Adaptation" and "Session Recording" config sections visible
- [x] No uncaught JS errors during Magic8 load

**10d: Tetris E2E**
- [x] Navigate to `/game/tetris`, game loads and grid renders
- [x] Start button (iconButton "start") visible in score card
- [x] Clicking start → active piece appears in main grid (cells with shadow-lg in 250px-wide grid)
- [x] No uncaught JS errors during tetris load

**10e: Word Game E2E**
- [x] Navigate to `/game/wordGame`, "Word Battle" heading + "Start Game" button visible
- [x] Clicking "Start Game" → Player 1 and Player 2 panels render with h3 headings
- [x] No uncaught JS errors during word game load

**New E2E tests (9 total across games.spec.js + biometrics.spec.js):**
- `games menu shows available games` — Mini Games heading + Tetris/Word Battle buttons
- `tetris loads and shows start button` — scoreCard start iconButton visible
- `tetris game grid renders` — flex grid container present
- `tetris active piece appears in grid after clicking start` — shadow-lg cell in main grid
- `word game loads and shows start button` — heading + Start Game button
- `word game board renders with player panels after start` — Player 1/2 h3s appear
- `card game loads and shows deck list` — "Card Game" header visible
- `card game shows builder or deck list content after loading` — builder tabs OR New Deck button
- `no uncaught JS errors during game route loads` — pageerror listener across all 4 game routes
- `session config shows biometric adaptation and recording sections` — h3 sections visible
- `no uncaught JS errors during magic8 load` — pageerror listener on /magic8

---

### Phase 12: Performance + Polish — COMPLETE (2026-03-10)

**Goal:** Bundle optimization, client-side caching, accessibility audit, performance profiling.

**12a: Bundle Optimization + Media Feature Extraction**
- [x] Extract optional modules from core imports to lazy-loaded chunks via `lazyComponent()` getter pattern in `main.js` — olio (16KB), sdConfig (9KB), audio (7KB), audioComponents (7KB), designer (8KB), emoji (4KB), pdfViewer (4KB)
- [x] Configure Vite `manualChunks` to split vendor code (mithril 27KB) from app code
- [x] Index chunk reduced: **526KB → 451KB** (14% reduction, under 500KB warning threshold)
- [x] sql.js loads as separate 41KB lazy chunk via dynamic import
- [x] All lazy chunks load correctly after splitting — verified with `vite build`

**12b: SQLite WASM Client Cache**
- [x] Add `sql.js` ^1.14.1 as dependency
- [x] Create `core/cacheDb.js` module: init WASM, create `cache` table with (type, act, key) PK
- [x] Cache strategy: write-through — `addToCache()` writes both in-memory and SQLite; `getFromCache()` checks in-memory first, falls back to SQLite, promotes hits to memory
- [x] Cache invalidation: `clearAll()` on logout, TTL-based expiry (5 min default), `removeByType()` on type-specific clears
- [x] Integrated with `am7client`: `getFromCache()`, `addToCache()`, `clearCache()`, `removeFromCache()` all dual-write
- [x] Cache hit/miss/write/eviction metrics via `am7client.cacheMetrics()` and `am7client.cacheEntryCount()`
- [x] Graceful fallback: if sql.js fails to load, `_cacheDbReady` stays false and all operations use in-memory cache only
- [x] 14 Vitest tests: init, store/retrieve, TTL expiry, remove by type, clear all, metrics, entry count, overwrite, string/array/numeric values, remove by key, DEFAULT_TTL_MS

**12c: Performance Profiling**
- [x] Added request deduplication to `am7client.search()` — `_inflight` map tracks in-progress queries by dedupKey, concurrent identical requests reuse the same promise
- [x] Converted `bulkApplyTags()` from sequential `await` loop to parallel `Promise.allSettled()` — N items now fire concurrently instead of serially
- [x] Audited all 300+ `m.redraw()` calls — existing Phase 7 optimizations (requestAnimationFrame for WS, dragOver check, search debounce) are adequate; no new bottlenecks found

**12d: Accessibility Audit (WCAG 2.1 AA)**
- [x] Added `@axe-core/playwright` ^4.x and Playwright E2E a11y test (`e2e/accessibility.spec.js`) — axe-core scans login + dashboard for WCAG 2.1 AA, zero critical violations
- [x] Dialog system already has full a11y: `role="dialog"`, `aria-modal`, `aria-labelledby`, focus trap (`trapFocus()`), focus restoration, Escape to close
- [x] Added `aria-label` to all icon-only buttons: topMenu (Home, Toggle dark mode, density, Logout), navigation (Toggle navigation menu), list toolbar (Add, Edit, Delete, Navigate up/down, Grid toggle, Apply tags, Select all, Full mode, Infinite scroll), gallery nav (Previous/Next), search clear, notification dismiss, panel category toggle
- [x] Added `aria-hidden="true"` to all decorative Material Icons/Symbols spans
- [x] Added `role` attributes: `role="toolbar"` + `aria-label` on topMenu, `role="main"` on main content (`<main>` element), `role="menu"` + `role="menuitem"` + `role="separator"` on context menu, `aria-label="Breadcrumb"` on breadcrumb nav, `aria-label="Pagination"` on pagination nav, `aria-label="Main navigation"` on nav bar
- [x] Added `aria-live="polite"` + `aria-atomic="false"` to toast container, `role="status"` on individual toast items
- [x] Added `aria-expanded` to notification button and hamburger menu button
- [x] Added keyboard navigation to context menu (ArrowUp/ArrowDown cycles focus, auto-focus first item on open)
- [x] Added `aria-label` to filter and search inputs in list view
- [x] 8 a11y-specific Vitest tests verifying ARIA attributes in source code
- [x] 5 Playwright E2E tests: axe-core scan on login + dashboard, ARIA attribute verification, toast aria-live

**New files:**
- `AccountManagerUx75/src/core/cacheDb.js` — SQLite WASM cache module
- `AccountManagerUx75/src/test/phase12.test.js` — 26 Vitest tests
- `AccountManagerUx75/e2e/accessibility.spec.js` — 5 Playwright E2E tests

**Modified files:**
- `AccountManagerUx75/vite.config.js` — `manualChunks` for vendor splitting
- `AccountManagerUx75/package.json` — added `sql.js` ^1.14.1, `@axe-core/playwright`
- `AccountManagerUx75/src/main.js` — `lazyComponent()` pattern for optional modules
- `AccountManagerUx75/src/core/am7client.js` — cacheDb integration, request deduplication, cache metrics
- `AccountManagerUx75/src/router.js` — `<main>` element with `role="main"`
- `AccountManagerUx75/src/views/list.js` — toolbar aria-labels, gallery nav, search/filter labels, pagination nav, parallel bulkApplyTags
- `AccountManagerUx75/src/components/topMenu.js` — aria-labels, aria-hidden, role="toolbar"
- `AccountManagerUx75/src/components/navigation.js` — aria-label on nav and hamburger
- `AccountManagerUx75/src/components/contextMenu.js` — role="menu", menuitem, separator, keyboard nav
- `AccountManagerUx75/src/components/breadcrumb.js` — aria-label
- `AccountManagerUx75/src/components/panel.js` — aria-label on category toggle
- `AccountManagerUx75/src/components/notifications.js` — aria-label, aria-expanded, aria-hidden
- `AccountManagerUx75/src/core/pageClient.js` — toast aria-live, aria-atomic, role="status", dismiss button aria-label

**Tests:** 173 Vitest (26 new) + 5 new Playwright E2E a11y tests

---

### Phase 13: Schema Write Endpoints — COMPLETE (2026-03-10)

**Goal:** Enable user-defined models and fields via the schema service. Full backend + frontend.

**13a: Backend — SchemaService Write Endpoints**
- [x] Add `isSystem` flag to `ModelSchema` / `FieldSchema` — system=true by default, user-defined models get system=false via `importSchemaFromUser()`
- [x] `PUT /rest/schema/{type}` — add user-defined fields to existing model. Validates field name uniqueness, type, marks as non-system. Uses `RecordFactory.addFieldToSchema()` for runtime ALTER TABLE + schema persist.
- [x] `POST /rest/schema` — create new user-defined model type. Validates name format (namespaced), delegates to `RecordFactory.importSchemaFromUser()` which handles DB table creation.
- [x] `DELETE /rest/schema/{type}` — delete non-system model. Checks `isSystem` flag. Delegates to `RecordFactory.releaseCustomSchema()` which drops table + deletes schema record.
- [x] `DELETE /rest/schema/{type}/field/{fieldName}` — remove non-system field. Checks `isSystem` + `isIdentity`. Uses `RecordFactory.removeFieldFromSchema()` for ALTER TABLE DROP + schema persist.
- [x] Runtime schema reload: `RecordFactory.updateSchemaDefinition()` persists to DB + clears caches via `unloadSchema()` + `CacheUtil.clearCache()`
- [x] User-defined models persisted to `system.modelSchema` records in /System org (same as existing custom model pattern)
- [x] All write endpoints: `@RolesAllowed({"admin"})` — admin only
- [x] JUnit tests: `TestSchemaService.java` — 6 tests: isSystem flag, create model, add field, remove field, delete model, schema update persistence

**New RecordFactory methods:**
- `addFieldToSchema(ModelSchema, FieldSchema)` — ALTER TABLE ADD + schema persist
- `removeFieldFromSchema(ModelSchema, String fieldName)` — ALTER TABLE DROP + schema persist
- `updateSchemaDefinition(ModelSchema)` — persist schema JSON to DB + reload caches

**13b: Frontend — Schema Editor Integration**
- [x] "New Model" button on schema browser — inline form: name (namespaced), inherits (comma-sep), group name
- [x] "Add Field" button on field table — inline form: field name, type dropdown (10 types), default value
- [x] Delete button per field (only for non-system, non-identity, non-inherited fields)
- [x] "Delete Model" button (only for user-defined models, with confirmation)
- [x] Schema write API functions: `createModelSchema()`, `addFieldsToModel()`, `deleteModel()`, `deleteField()`
- [x] System/user-defined badges on model detail header and field rows ("user" badge for system=false)
- [x] `isSystemModel()` / `isSystemField()` helpers guard all destructive UI actions
- [x] Confirmation dialogs for all destructive operations
- [x] 19 Vitest tests: isSystem helpers, FIELD_TYPES, API function exports, HTTP method/URL verification
- [x] All 130 Vitest tests pass (11 test files)

**Files modified/created:**
- `AccountManagerObjects7/.../schema/ModelSchema.java` — added `system` field + getter/setter
- `AccountManagerObjects7/.../schema/FieldSchema.java` — added `system` field + getter/setter
- `AccountManagerObjects7/.../record/RecordFactory.java` — 3 new methods, `importSchemaFromUser()` marks user-defined
- `AccountManagerService7/.../rest/services/SchemaService.java` — 4 new endpoints (POST/PUT/DELETE/DELETE)
- `AccountManagerService7/.../tests/TestSchemaService.java` — NEW: 6 JUnit tests
- `AccountManagerUx75/src/features/schema.js` — schema write API, editor UI, system protection
- `AccountManagerUx75/src/test/schema.test.js` — NEW: 19 Vitest tests

---

### Phase 14: Feature Configuration — COMPLETE (2026-03-10)

**Goal:** Server-side feature enablement per organization, replacing client-only build profiles.

**14a: Backend — FeatureConfigService.java**
- [x] Create `FeatureConfigService.java` at `/rest/config`
- [x] `GET /rest/config/features` — returns enabled features for current user's org. Reads from `data.data` record named `.featureConfig` in user's home directory. Returns `{ features: [...], profile: "full" }`. Defaults to full profile if no config record exists.
- [x] `PUT /rest/config/features` — update enabled features (admin only via `@RolesAllowed`). Validates feature IDs against known set. Ensures 'core' always included. Creates/updates `data.data` record.
- [x] `GET /rest/config/features/available` — returns all 11 available feature definitions (id, label, description, required, deps) for UI rendering.
- [x] JUnit test: `TestFeatureConfigService.java` — 4 tests: default config, CRUD lifecycle, feature validation, core-always-included logic.

**14b: Frontend — Feature Admin Panel**
- [x] New admin-only view at `/admin/features` via `features/featureConfig.js`
- [x] Toggle switches for each feature (enabled/disabled), Required badge for core
- [x] Dependency graph: shows "Depends on:" with color-coded deps, "Active dependents:" warning, blocks disable when dependents are active
- [x] Save button calls PUT endpoint, shows unsaved changes indicator
- [x] Quick profile buttons (Minimal, Standard, Enterprise, Gaming, Full)
- [x] `router.js` `refreshApplication()` fetches server feature config after auth, falls back to URL param/build define
- [x] `am7client` methods: `getFeatureConfig()`, `updateFeatureConfig()`, `getAvailableFeatures()`
- [x] `featureConfig` registered in `features.js` manifest with `adminOnly: true` aside menu item
- [x] 17 Vitest tests in `featureConfig.test.js`: manifest integration, profile inclusion, toggle logic, dependency validation
- [x] 5 Playwright E2E tests in `featureConfig.spec.js`: page load, toggle visibility, profile buttons, unsaved changes, dependency info

**Key Files:**
- `AccountManagerService7/src/main/java/org/cote/rest/services/FeatureConfigService.java` — 3 REST endpoints
- `AccountManagerService7/src/test/java/org/cote/accountmanager/objects/tests/TestFeatureConfigService.java` — 4 JUnit tests
- `AccountManagerUx75/src/features/featureConfig.js` — admin panel UI + route
- `AccountManagerUx75/src/core/am7client.js` — 3 new API methods
- `AccountManagerUx75/src/features.js` — `featureConfig` entry + enterprise profile
- `AccountManagerUx75/src/router.js` — server config fetch in `refreshApplication()`
- `AccountManagerUx75/src/test/featureConfig.test.js` — 17 Vitest tests
- `AccountManagerUx75/e2e/featureConfig.spec.js` — 5 Playwright E2E tests

---

### Phase 15: Integration Testing + Open Issues — COMPLETE (2026-03-11)

**Goal:** Close all remaining open issues and validate full-stack integration.

**15a: WebAuthn Backend Integration Test** (Issue #18)
- [x] 6 new API-level tests added to `webauthn.spec.js` in `test.describe('WebAuthn backend API integration (15a)')`
- [x] Tests verify: GET /register returns valid RFC 8809 options JSON (challenge, rp, user, pubKeyCredParams)
- [x] Tests verify: GET /auth returns challenge options, consecutive calls return unique challenges
- [x] Tests verify: GET /credentials requires auth (401/403 when unauthenticated), returns JSON array after login
- [x] Tests verify: POST /register returns 400 on missing fields (not 500)
- [x] CDP virtual authenticator test annotated with `test.skip(browserName !== 'chromium', ...)` for Firefox compat
- [x] Backend `WebAuthnService.java` reviewed — no issues found; challenge-in-clientDataJSON verification is correct

**15b: Full E2E Regression Suite**
- [x] `e2e/explorer.spec.js` created — 7 tests: route loads, split-pane layout, empty state right panel, toolbar, tree panel visible, tree node click updates right, aside button navigates to explorer
- [x] `e2e/list.spec.js` extended — 9 new tests across 2 new describe blocks:
  - `List view — group navigation`: breadcrumb path, search input accepts text, grid mode toolbar, pagination controls, direct URL navigation to group
  - `List view — search behavior`: search clears without crash, no-results search shows empty state gracefully
- [x] All 173 Vitest unit tests confirmed passing

**15c: Cross-Browser Validation**
- [x] Firefox project added to `playwright.config.js` (`{ name: 'firefox', use: { ...devices['Desktop Firefox'] } }`)
- [x] CDP-only WebAuthn test marked `skip` on non-Chromium browsers
- [ ] Safari/WebKit — not added (Playwright WebKit on Windows has limitations; defer if needed)

---

### Phase 16: Picture Book — PLANNED

**Goal:** Generate an illustrated "picture book" from a story or document — a series of scene images with caption blurbs representing the most visually notable moments in the work. Bridges the LLM summarizer pipeline with character extraction, outfit building, and image generation.

---

#### 16.0 Concept Overview

The Picture Book feature operates on a "work" — any `data.data` or `data.note` object containing story or document text. It produces an ordered sequence of scene records, each with:
- A short blurb (2-3 sentences: characters in a setting doing something)
- A generated image representing the scene

The pipeline has two entry paths:
1. **Auto (LLM extraction)** — Feed the work text to the LLM; it identifies and returns the most visually notable scenes, characters, and settings.
2. **Manual** — User provides a list of scene descriptions (title, summary, characters, setting) directly via a form.

In either path, the system:
1. Extracts or accepts scene definitions
2. Identifies characters in each scene and creates/matches `olio.charPerson` objects scoped to the work
3. Thoroughly outfits and narrates each character using the existing outfit builder + narrate pipeline
4. Generates a scene image by compositing character narrations + setting + action into an SD prompt
5. Stores scenes as `data.note` records linked to the work

The result is a browsable picture book that can also be exported or referenced inside the work.

---

#### 16.1 Data Model

**Scene Storage**
- Type: `data.note` (existing model, no new schema needed)
- Group: `{workGroupPath}/Scenes/` — auto-created as a sub-group of the work's group
- Per-scene record fields:
  - `name` — scene title (e.g., "The Duel at Dawn")
  - `description` — the blurb paragraph (characters in setting, doing action)
  - `tags` — linked `data.tag` records: `scene-index:{N}`, the work's tag, any character tags
- Scene images stored as `data.data` linked to the scene `data.note` via membership (same pattern as charPerson portrait images)
- Scene order: encoded in the `scene-index:{N}` tag name, or in a `.pictureBookMeta` `data.data` JSON blob in the work's group (ordered array of scene objectIds)

**Character Storage**
- Type: `olio.charPerson` (existing model)
- Group: `{workGroupPath}/Characters/` — auto-created per work
- Each extracted character maps to one `olio.charPerson` in this group
- Characters are tagged with a work-scoped tag: `work:{workObjectId}` to allow cross-query
- After creation, they receive a full outfit (via outfit builder themes or LLM-extracted clothing) and a narrate call so SD prompts are current

**Work Tag**
- A `data.tag` named after the work (or using its objectId) links together all scenes and characters: the work is the "owner" of the extraction run
- Allows future re-runs (detect existing extractions, offer to re-extract vs. append)

**Picture Book Meta**
- A `data.data` record named `.pictureBookMeta` in `{workGroupPath}/` stores JSON:
  ```json
  {
    "workObjectId": "...",
    "workName": "...",
    "sceneCount": 5,
    "scenes": [
      { "objectId": "...", "index": 0, "title": "...", "imageObjectId": "...", "characters": ["...","..."] }
    ],
    "extractedAt": "...",
    "generatedAt": "..."
  }
  ```

---

#### 16.2 LLM Prompt Templates

Four new PromptTemplate files under `olio/llm/prompts/` (externalized via `PromptResourceUtil`, same pattern as `summarization.json`):

**`pictureBook.extract-scenes.json`**
- System: Instruct the LLM to identify the N most visually compelling scenes in a story. Each scene must have enough concrete visual detail (characters, setting, action) to generate an illustration. Scenes should be distributed across the narrative arc, not clustered at the start. Append `/no_think`.
- User template: `"Given this story, identify the {count} most visually notable scenes. Return a JSON array only, no prose:\n[{\"index\": 0, \"title\": \"...\", \"summary\": \"2-3 sentence blurb of who is doing what where\", \"setting\": \"environment/landscape description for illustration\", \"action\": \"what characters are doing\", \"mood\": \"atmosphere, lighting, time of day\", \"characters\": [{\"name\": \"...\", \"role\": \"brief role in scene\"}]}]\n\nSTORY:\n{text}"`
- Default count: 5 (configurable in the wizard UI, range 3-12)
- Response parsed as JSON array

**`pictureBook.extract-character.json`**
- System: Extract a thorough physical and costume description suitable for stable diffusion character generation. Be specific about ethnicity, hair color, eye color, build, age approximation, and detailed clothing. Do not invent details not supported by the text; note gaps as `null`. Append `/no_think`.
- User template: `"From the story below, extract all available physical and costume details for the character named '{name}'. Return JSON only:\n{\"name\": \"...\", \"gender\": \"male|female|other\", \"age_approx\": \"...\", \"physical\": {\"height\": \"...\", \"build\": \"...\", \"hair\": \"...\", \"eyes\": \"...\", \"skin\": \"...\", \"distinguishing\": \"...\"}, \"clothing_style\": \"...\", \"outfit_notes\": \"detailed clothing description\", \"personality_hint\": \"...\"}\n\nSTORY:\n{text}"`
- One call per unique character name

**`pictureBook.scene-image-prompt.json`**
- System: You are an expert Stable Diffusion prompt engineer. Combine character appearance descriptions with a scene setting and action into a high-quality SD image prompt. Format: masterpiece, best quality, [characters described in scene], [setting], [action], [mood/lighting]. DO NOT include negative prompts here (handled separately). Append `/no_think`.
- User template: `"Create a Stable Diffusion scene prompt for this scene.\nSETTING: {setting}\nACTION: {action}\nMOOD: {mood}\nCHARACTERS IN SCENE:\n{charNarrations}\n\nReturn only the SD prompt text, no commentary."`
- `charNarrations` = concatenated `olio.charPerson.narrative` for each character in scene

**`pictureBook.scene-blurb.json`**
- System: Write engaging, literary picture book captions. Present tense. Specific sensory details. No generic summaries. Maximum 3 sentences. Append `/no_think`.
- User template: `"Write a 2-3 sentence picture book caption for this scene:\nTITLE: {title}\nSETTING: {setting}\nACTION: {action}\nCHARACTERS: {characterList}\n\nCaption:"`
- Used to generate/regenerate the blurb per scene

---

#### 16.3 Backend: PictureBookService.java

New REST service at `/rest/olio/picture-book`. Handles the long-running orchestration steps that are impractical to sequence entirely in the browser.

**Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/rest/olio/picture-book/{workObjectId}/extract` | Run full LLM extraction: scenes + characters from work text. Creates scene `data.note` records, `olio.charPerson` records, outfits characters, calls narrate. Returns the `.pictureBookMeta` JSON. |
| `POST` | `/rest/olio/picture-book/{workObjectId}/extract-scenes-only` | LLM scene extraction only (no character creation). Returns raw scene JSON array for user review before committing. |
| `POST` | `/rest/olio/picture-book/scene/{sceneObjectId}/generate` | Generate SD image for one scene. Accepts `{ chatConfig, sdConfig, promptOverride }` body. Builds SD prompt from scene data + linked charPerson narratives, calls SD service, stores image, links to scene. Returns image objectId. |
| `POST` | `/rest/olio/picture-book/scene/{sceneObjectId}/blurb` | Regenerate the scene blurb via LLM. Updates the `data.note.description` field. |
| `GET` | `/rest/olio/picture-book/{workObjectId}/scenes` | Returns ordered scene list with meta (title, blurb, imageObjectId, characters). Reads from `.pictureBookMeta` data.data. |
| `PUT` | `/rest/olio/picture-book/{workObjectId}/scenes/order` | Reorder scenes (body: `{ scenes: ["objectId1", ...] }`). Updates `.pictureBookMeta`. |
| `DELETE` | `/rest/olio/picture-book/{workObjectId}/reset` | Delete all scenes and extracted characters for a work (with confirmation). Removes `Scenes/` and `Characters/` groups. |

**Java implementation notes:**
- `PictureBookService.java` in `AccountManagerService7/src/main/java/org/cote/rest/services/`
- Uses existing `PromptResourceUtil` for prompt loading
- Uses existing chat infrastructure (`ChatUtil`, `LLMConnector` Java equivalent) for LLM calls
- Character creation calls existing `RecordFactory` for `olio.charPerson`
- Outfit assignment: use existing `OlioUtil.applyThemeOutfit()` or equivalent — pick a theme-appropriate outfit based on the story's genre tag (fantasy/sci-fi/contemporary/period) OR use the LLM-extracted `outfit_notes` to match an outfit template
- After outfit creation: call `OlioUtil.narrateCharacter(charPersonId)` to refresh SD prompt
- Scene image generation: delegates to `SDService` via the scene SD prompt built from charPerson narratives
- JUnit tests: `TestPictureBookService.java` — 6 tests: extract scenes (mock LLM), create characters, generate image (mock SD), list scenes, reorder, reset

---

#### 16.4 Frontend: Feature Structure

**New files:**

`src/features/pictureBook.js` — Lazy-loaded feature chunk
- Route: `/picture-book` — work selector view (list of documents to illustrate)
- Route: `/picture-book/:workObjectId` — picture book viewer for a specific work
- `PictureBookFeature` — feature manifest entry (chat profile required — depends on LLM)
- Registered in `features.js` with `deps: ['chat']`, aside menu under "Creative Tools"

`src/workflows/pictureBook.js` — Command workflow
- Entry point: `pictureBook(entity, inst)` — appears as command button on `data.data` and `data.note` objects
- Launches the multi-step wizard (see 16.5)
- Registered in `workflows/index.js` and `views/object.js` command dispatch

`src/workflows/sceneExtractor.js` — LLM pipeline utilities (shared by workflow + feature)
- `extractScenes(workObjectId, workText, chatConfig, count)` → calls `/extract-scenes-only`, returns scene array
- `buildCharPersonFromExtracted(extractedChar, workGroupPath)` → creates charPerson, outfits, narrates
- `generateSceneImage(sceneObjectId, sdConfig, chatConfig)` → calls `/scene/{id}/generate`
- `pollGenerationStatus(sceneObjectId)` → polls for image completion
- `loadPictureBook(workObjectId)` → fetches `.pictureBookMeta` and scene list

---

#### 16.5 Frontend: Multi-Step Wizard

Launched via the command button on any document object. Uses `Dialog.open()` with a step controller (same pattern as existing multi-step dialogs).

**Step 1: Source & Method**
- Shows work title/name (pre-filled from `inst`)
- Toggle: "Auto-extract from text" vs. "Enter scenes manually"
- For Auto: select chat config (dropdown from `~/Chat`), scene count (3-12, default 5), genre hint (Fantasy / Sci-Fi / Contemporary / Historical / Other — guides outfit selection)
- For Manual: skip to Step 3 directly

**Step 2: Scene Preview (Auto path only)**
- Calls `/extract-scenes-only` with spinner
- Shows extracted scenes in a card list: index, title, summary, characters mentioned, setting
- User can edit any field inline before committing
- Add/remove scene buttons
- "Looks good — continue" or "Re-extract" buttons

**Step 3: Character Review**
- Lists all unique characters found across scenes
- For each: name, extracted description (or blank if manual), outfit style recommendation
- "Create All" button — calls backend to create `olio.charPerson` records + outfit + narrate
- Progress indicator per character
- "Skip character" — exclude from image generation (scene images will omit them)
- Shows existing charPersons if work was previously processed (detect by `Characters/` group existence)

**Step 4: Image Generation**
- Grid of scene cards, each with:
  - Scene title + blurb
  - "Generate Image" button (or "Regenerate" if image exists)
  - Image thumbnail once generated
  - SD config picker (use global default or override per scene)
- "Generate All" button fires sequential requests with progress (N/total)
- Each generation calls `/scene/{id}/generate`, polls for completion, shows thumbnail on finish
- Generation is interruptible (Cancel button stops queuing remaining scenes)

**Step 5: Picture Book View**
- Full-screen gallery of generated scenes in order
- Each scene card: full image + blurb caption + character tags
- Drag-to-reorder (calls `/scenes/order` on drop)
- "Edit Blurb" inline (calls `/scene/{id}/blurb` to regenerate via LLM, or freeform edit)
- "Insert into Document" — copies Markdown image reference to clipboard (future: inline embed if document editor exists)
- "Export Picture Book" — downloads as HTML or ZIP of images + captions
- "Done" closes wizard, saves final state to `.pictureBookMeta`

---

#### 16.6 Picture Book Viewer (Feature Route `/picture-book/:workObjectId`)

Standalone view accessed from the aside menu or directly:
- Loads `.pictureBookMeta` for the work
- Renders scenes in a responsive masonry or flex-row gallery
- Dark/light mode aware
- Scene selection shows full-size image + blurb + character list
- "Edit" button opens the wizard at Step 4 to add/regenerate
- "Back to Work" navigates to the source `data.data` object view

---

#### 16.7 Outfit Strategy for Extracted Characters

The outfit applied to a new `olio.charPerson` is selected by this priority:
1. LLM-extracted `outfit_notes` are compared against existing outfit templates in `~/Wearables` — if a close match exists, use it
2. If no match: use the genre hint from Step 1 to pick a theme (fantasy=dark-medieval, sci-fi=sci-fi, contemporary=modern, historical=period) and apply the theme's gender-appropriate `functional` outfit tier
3. Fallback: apply the default BASE wear level (naked + no apparel) so the character at least has a narration
4. After outfit is applied: call `narrate` endpoint unconditionally so the SD prompt includes clothing details

This ensures characters are "thoroughly outfitted and described" as required, without blocking on perfect outfit detection.

---

#### 16.8 Files Summary

**New backend files:**
- `AccountManagerService7/src/main/java/org/cote/rest/services/PictureBookService.java`
- `AccountManagerService7/src/test/java/org/cote/accountmanager/objects/tests/TestPictureBookService.java`
- `AccountManagerService7/src/main/resources/olio/llm/prompts/pictureBook.extract-scenes.json`
- `AccountManagerService7/src/main/resources/olio/llm/prompts/pictureBook.extract-character.json`
- `AccountManagerService7/src/main/resources/olio/llm/prompts/pictureBook.scene-image-prompt.json`
- `AccountManagerService7/src/main/resources/olio/llm/prompts/pictureBook.scene-blurb.json`

**New frontend files:**
- `AccountManagerUx75/src/features/pictureBook.js`
- `AccountManagerUx75/src/workflows/pictureBook.js`
- `AccountManagerUx75/src/workflows/sceneExtractor.js`
- `AccountManagerUx75/src/test/pictureBook.test.js` — ~20 Vitest unit tests
- `AccountManagerUx75/e2e/pictureBook.spec.js` — ~8 Playwright E2E tests

**Modified frontend files:**
- `AccountManagerUx75/src/workflows/index.js` — add `pictureBook` export
- `AccountManagerUx75/src/views/object.js` — register `pictureBook` command handler
- `AccountManagerUx75/src/features.js` — add `pictureBook` feature entry with `deps: ['chat']`

---

#### 16.9 Tests

**Vitest unit tests (~20):**
- `sceneExtractor.js` exports and API function shapes
- Scene JSON parsing from LLM response
- charPerson field mapping from extracted character JSON
- SD prompt assembly from scene + narrations
- `.pictureBookMeta` structure validation
- Blurb generation call path
- Reorder mutation logic
- Feature manifest entry (deps, aside menu, adminOnly=false)
- Command handler registration on objectPage

**Playwright E2E tests (~8):**
- Picture book route loads (`/picture-book`)
- Work selector lists document objects
- Wizard opens from object command button on a `data.data` object
- Step 1 shows method toggle and chat config dropdown
- Character review step renders character list
- Scene grid shows generate buttons
- Picture book viewer renders scene cards
- No uncaught JS errors across all picture book routes

---

#### 16.10 Estimated Effort and Dependencies

| Sub-phase | Effort | Prerequisite |
|-----------|--------|-------------|
| 16a: Prompt templates + backend service | Medium-High | Backend running, LLM chat infrastructure |
| 16b: sceneExtractor.js + charPerson pipeline | Medium | 16a prompts deployed, outfit builder working |
| 16c: Wizard (Steps 1-4) | High | 16a endpoints, 16b utilities |
| 16d: Picture book viewer feature route | Medium | 16c wizard complete |
| 16e: Export + Insert-into-document | Low | 16d viewer complete |

**Hard dependencies:**
- Chat feature + LLM infrastructure must be configured (chat config, prompt config in `~/Chat`)
- SD service must be running for image generation steps
- `olio.charPerson` creation, outfit builder, narrate endpoint must all be working (Phase 3.5c validation)

---

## 9. E2E Test Coverage

**74+ Playwright E2E tests** across 15 spec files:

| Spec | Tests | What's Covered |
|------|-------|----------------|
| `login.spec.js` | 4 | Page load, valid login, invalid login error, logout |
| `panel.spec.js` | 5 | Category cards, navigate to list, home button, dark mode, feature buttons |
| `list.spec.js` | 13 | List/empty state, toolbar, breadcrumb, filter input; + Phase 15b: group nav breadcrumb, search input, grid modes, pagination, direct URL nav, search clear, no-results graceful |
| `object.spec.js` | 3 | Add button, form fields, double-click opens view |
| `chat.spec.js` | 3 | Menu navigation, page content, library status check |
| `biometrics.spec.js` | 5 | Magic 8 navigation, Start Session button, config description; **+Phase 10:** biometric/recording config sections, no uncaught JS errors |
| `testHarness.spec.js` | 3 | Menu navigation, UI components, Run Tests + categories |
| `workflows.spec.js` | 2 | Workflow handler registration on objectPage, no command-not-found warnings |
| `webauthn.spec.js` | 13 | Login passkey button, no-username toast, settings page, register form, client API, CDP virtual authenticator registration flow, base64url encoding roundtrip; + Phase 15a: 6 backend API integration tests (GET /register, GET /auth, GET /credentials auth+unauth, POST /register validation, challenge uniqueness) |
| `schema.spec.js` | 9 | Schema browser route, model list, model details, field table, search, namespace grouping |
| `explorer.spec.js` | 7 | **NEW Phase 15b** — Route loads, split-pane layout, empty-state right panel, fullscreen toolbar, tree panel, tree node click, aside menu navigation |
| `accessRequests.spec.js` | 4 | Access requests page, tab switching, new request form, cancel |
| `featureConfig.spec.js` | 5 | Feature config page, toggles, quick profiles, unsaved changes, dependency info |
| `games.spec.js` | 9 | **NEW Phase 10** — Game menu, tetris load+start+active piece, word game load+board, card game load+content, no uncaught JS errors across all game routes |
| `accessibility.spec.js` | — | Accessibility (Phase 12) |

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
