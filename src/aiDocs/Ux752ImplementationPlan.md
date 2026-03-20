# Ux752 Implementation Plan

**Parent document:** `aiDocs/Ux752Plan.md` (standing orders, evidence analyst mode, guiding principles)

This is the concrete, file-by-file implementation plan. Every phase produces a testable deliverable. Stephen reviews and approves before the next phase begins.

---

## Project Structure

The Ux752 refactor touches **4 files** in the existing `AccountManagerUx752/` project. Everything else stays untouched.

```
AccountManagerUx752/src/
├── components/
│   ├── decorator.js          ← REBUILD (136 lines → ~450 lines)
│   └── pagination.js         ← MODIFY  (469 lines, ~15 lines changed)
├── views/
│   └── list.js               ← REBUILD (1696 lines → ~550 lines)
├── router.js                 ← MODIFY  (270 lines, ~30 lines changed)
└── main.js                   ← MODIFY  (1 line: wire decorator to page.components)

AccountManagerUx752/e2e/
├── listControl.spec.js       ← REWRITE (existing 490 lines → ~350 lines)
└── helpers/api.js            ← NO CHANGE

AccountManagerUx752/src/test/
└── phase11b.test.js          ← UPDATE  (add tests for new decorator + list functions)
```

### What does NOT change (95% of the codebase):
- `core/*` — all 12 modules untouched
- `components/*` — 33 of 34 files untouched (only decorator.js changes)
- `views/*` — 4 of 5 files untouched (only list.js changes)
- `features/*` — all 12 modules untouched
- `chat/*` — all 17 modules untouched
- `cardGame/*`, `magic8/*`, `games/*`, `workflows/*` — all untouched
- `e2e/*` — 37 of 38 spec files untouched (only listControl.spec.js changes)
- Build config, Tailwind config, Playwright config — all untouched

---

## Phase 1: Rebuild decorator.js

### Filled Prompt Template

```
TASK: Ux752 Phase 1 — Rebuild decorator.js

CONTEXT:
- Project: AccountManagerUx752 (new project, surgical refactor from Ux75 base, NOT a rewrite)
- Plan: aiDocs/Ux752Plan.md and aiDocs/Ux752ImplementationPlan.md (read BOTH first)
- All backend services LIVE at localhost:8443
- Ux7 reference: AccountManagerUx7/client/ (READ BEFORE CODING)

REFERENCE FILES TO READ BEFORE WRITING ANY CODE:
- AccountManagerUx7/client/decorator.js — FULL Ux7 decorator (557 lines, all rendering)
- AccountManagerUx7/client/view.js — am7view.selectObjectRenderer, isBinary
- AccountManagerUx7/client/view/list.js — getListController() interface (lines 454-480)
- AccountManagerUx752/src/components/decorator.js — current Ux75 stub (136 lines)
- AccountManagerUx752/src/components/objectViewRenderers.js — existing media renderers
- AccountManagerUx752/src/core/am7client.js — mediaDataPath() function (line 1201)

FILES TO MODIFY:
- AccountManagerUx752/src/components/decorator.js — REWRITE (136 → ~450 lines)
- AccountManagerUx752/src/main.js — ADD 1 line: wire decorator to page.components

WHAT THIS PHASE DOES:
Rebuild decorator.js as the FULL rendering module for list views, ported from Ux7's
decorator.js but using ES6 exports and Tailwind classes. This module handles ALL visual
rendering — tabular rows, grid cards, carousel items, thumbnails, icons, media preview.
The list.js controller will pass a controller interface object to these functions.

SPECIFIC REQUIREMENTS:
1. Port getIcon(item) from Ux7 decorator.js lines 55-103
2. Port getThumbnail(ctl, item) from Ux7 decorator.js lines 12-53
3. Port getFileTypeIcon(item) from Ux7 decorator.js lines 3-10
4. Port getFavoriteStyle(item) from Ux7 decorator.js lines 107-113
5. Port getHeadersView(ctl, map) from Ux7 decorator.js lines 210-261 — with sort indicators
6. Port getTabularRow(ctl, item, idx, map) from Ux7 decorator.js lines 263-332
7. Port tabularView(ctl, items) from Ux7 decorator.js lines 396-410
8. Port getGridListItem(ctl, item) from Ux7 decorator.js lines 412-447
9. Port gridListView(ctl) from Ux7 decorator.js lines 490-499
10. Port carouselItem(ctl) from Ux7 decorator.js lines 500-525
11. Port carouselView(ctl) from Ux7 decorator.js lines 527-536
12. Port displayIndicators(ctl) from Ux7 decorator.js lines 449-473
13. Port displayObjects(ctl) from Ux7 decorator.js lines 475-488
14. Add renderMediaPreview(item) — image/audio/video/PDF (approved by Stephen)
15. Port defaultHeaderMap from Ux7 decorator.js lines 188-201 EXACTLY AS WRITTEN
16. Port getHeaders(type, map) from Ux7 decorator.js lines 203-208 — filters by hasField
17. Port getCellStyle(ctl, h) and getFormattedValue(ctl, item, h) from Ux7 decorator.js
18. Export as am7decorator object AND as named exports for ES6 imports
19. Register on page.components.decorator in main.js
20. Add modelHeaderMap — optional per-model column overrides that feed INTO
    Stephen's getHeaders() filter, not replace it. Design:
    - defaultHeaderMap stays as the universal base (Stephen's design, untouched)
    - modelHeaderMap is an optional object keyed by model type
    - When present, replaces defaultHeaderMap as INPUT to getHeaders()
    - getHeaders() filter still runs — am7model.hasField() still validates
    - Special columns (_rowNum, _icon, _tags, _favorite) included in overrides
    - Only define overrides where universal set is clearly wrong for a type
    - Example: auth.group → ["_rowNum","_icon","name","type","path","_favorite"]
    - Example: data.data → ["_rowNum","_icon","name","contentType","description","_tags","_favorite"]
    - Example: olio.llm.chatConfig → ["_rowNum","_icon","name","model","rating","_favorite"]
    - getHeaders(type, map) becomes: let base = map || modelHeaderMap[type] || defaultHeaderMap;
    - This is 3 lines of code change to getHeaders() + a small lookup object

WHAT TO PORT FROM UX7:
- ALL rendering functions from decorator.js (items 1-17 above)
- defaultHeaderMap EXACTLY as Stephen wrote it (lines 188-201)
- getHeaders(type, map) filter function (lines 203-208)
- DnD integration: page.components.dnd.dragProps(item), doDragDecorate(item)
- Favorite toggle: page.favorite(item) onclick handler
- Tag rendering: _tags column with pill badges
- _rowNum, _icon, _favorite special column handling

WHAT TO ADD (approved by Stephen):
- renderMediaPreview(item) — standalone media renderer for image/audio/video/PDF

WHAT NOT TO ADD:
- Do NOT replace defaultHeaderMap — it stays as the universal fallback
- Do NOT replace getHeaders() — it stays as the filter, just add modelHeaderMap lookup
- Do NOT create a separate getColumns(type) function — getHeaders() already does this

WHAT TO KEEP FROM UX75:
- ES6 module export pattern
- Tailwind CSS classes (convert Ux7's CSS classes to Tailwind equivalents)

WHAT TO REMOVE:
- Current Ux75 decorator.js stub (tabularView, listItemView, getDisplayFields) — being replaced
- polarListView / polarListItem from Ux7 — dead code, never used

ACCEPTANCE CRITERIA:
1. `npx vite build` — no errors
2. `npx vitest run` — all existing tests still pass
3. decorator.js exports: map, icon, fileIcon, tabularView, gridListView,
   carouselView, carouselItem, renderMediaPreview, getColumns
4. page.components.decorator.map() returns the default header map
5. All rendering functions accept a controller interface object matching
   Ux7's getListController() shape
6. No rendering code remains in views/list.js after Phase 2 (verified then)

ANTI-PATTERNS TO AVOID:
- Do NOT inline rendering in list.js — ALL rendering goes in decorator.js
- Do NOT use transition-all CSS — use specific transition properties
- Do NOT create unnecessary state — decorator functions are stateless renderers
- Do NOT skip the DnD integration — Ux7 had it, we keep it
- Do NOT skip the tag rendering — Ux7 had it, we keep it
```

### Deliverable:
- `components/decorator.js` — ~450 lines, full rendering module
- `main.js` — 1 added line

---

## Phase 2: Rebuild list.js as Pure Controller

### Filled Prompt Template

```
TASK: Ux752 Phase 2 — Rebuild list.js as Pure Controller

CONTEXT:
- Project: AccountManagerUx752 (new project, surgical refactor from Ux75 base, NOT a rewrite)
- Plan: aiDocs/Ux752Plan.md and aiDocs/Ux752ImplementationPlan.md
- Phase 1 COMPLETE: decorator.js rebuilt with all rendering functions
- All backend services LIVE at localhost:8443

REFERENCE FILES TO READ BEFORE WRITING ANY CODE:
- AccountManagerUx7/client/view/list.js — FULL Ux7 list controller (871 lines)
- AccountManagerUx7/client/components/pagination.js — pagination patterns
- AccountManagerUx752/src/components/decorator.js — Phase 1 output (rendering module)
- AccountManagerUx752/src/components/pagination.js — current pagination
- AccountManagerUx752/src/router.js — how list routes are wired

FILES TO MODIFY:
- AccountManagerUx752/src/views/list.js — REWRITE (1696 → ~550 lines)

WHAT THIS PHASE DOES:
Rebuild list.js as a PURE CONTROLLER — state management, navigation, selection, toolbar,
keyboard handling. ALL rendering delegated to decorator.js via getListController() interface.
Port navigation logic (up/down/breadcrumb) from Ux7 completely, including parent-based
navigation that Ux75 deleted.

SPECIFIC REQUIREMENTS:
1. State variables match Ux7 (lean set from Ux752Plan.md Phase 2)
2. Port getListController() from Ux7 list.js lines 454-480
3. Port navigateUp() COMPLETELY from Ux7 list.js lines 195-249
   — including am7model.isParent() branch (lines 215-243)
4. Port navigateDown() from Ux7 list.js lines 344-368 PLUS Fix A
   (non-group items open carousel, not broken container lookup)
5. Port carousel functions from Ux7: openItem, openSelected, closeSelected,
   toggleCarousel, moveCarousel (WITH currentItem reset on page change),
   moveCarouselTo, getCurrentResults
6. Port navListKey() keyboard handler from Ux7 list.js lines 740-770
7. Port toolbar structure from Ux7 getActionButtonBar (lines 627-638)
   — all 8 button groups in correct order
8. Keep breadcrumb from Ux75 (loadGroupPath, renderGroupBreadcrumb)
   — but batch segment redraws into single m.redraw()
9. Keep context menu from Ux75 — Open calls openItem (not navigateDown)
10. Restore embeddedMode + embeddedController from Ux7
11. Restore pickerCancel from Ux7
12. Remove: childGroups/childGroupsLoading, searchQuery/searchTimer/searchActive,
    columnConfigCache/columnPickerOpen, lastVnode, infiniteScroll/infiniteLoading,
    picker state explosion (6 vars → pickerMode + pickerHandler + navContainerId)
13. gridMode is 0-2 (table, small, large). Carousel is separate boolean.
14. Adaptive record counts: table=10, small=40, large=10
15. Display routing: carousel → am7decorator.carouselView(ctl), gridMode>0 →
    am7decorator.gridListView(ctl), else → am7decorator.tabularView(ctl, items)

WHAT TO PORT FROM UX7:
- navigateUp() with FULL parent navigation (lines 195-249)
- navigateDown() (lines 344-368) + Fix A
- getListController() (lines 454-480)
- toggleGrid() (lines 407-423) — gridMode 0-2, not 0-3
- All carousel functions (lines 174-722)
- navListKey() (lines 740-770)
- getActionButtonBar structure (lines 627-638)
- embeddedMode support (lines 43-44, 83-88, 356-362)
- openSystemLibrary() (lines 266-322)
- openOlio(), openFavorites(), openPath() (lines 324-341)
- initParams() (lines 772-791)
- update() (lines 793-817)

WHAT TO KEEP FROM UX75:
- Breadcrumb (loadGroupPath, renderGroupBreadcrumb) — batched redraws
- Context menu (showListContextMenu) — Open → openItem
- Non-group navigation fix (Fix A)
- ES6 module pattern
- Picker mode support (consolidated state)

WHAT TO REMOVE:
- ALL rendering code (moved to decorator.js in Phase 1)
- childGroups separate loading (container mode handles this)
- Live debounced search (use Ux7 filter pattern)
- Column customization localStorage
- lastVnode global
- Infinite scroll toggle
- Select all button
- gridMode=3 gallery (carousel is separate boolean)

ACCEPTANCE CRITERIA:
1. `npx vite build` — no errors
2. `npx vitest run` — all pass
3. list.js under 600 lines
4. list.js contains ZERO rendering code (no m('table'), m('tr'), m('img'), etc.)
5. navigateUp() handles auth.group AND parent-model types
6. navigateDown() opens carousel for non-group items
7. Carousel: moveCarousel resets currentItem on page change
8. Keyboard: ArrowRight in carousel moves to next item, not next page
9. getListController() returns interface matching Ux7 shape
10. No console errors when loading a list page in Chrome

ANTI-PATTERNS TO AVOID:
- Do NOT put ANY rendering in list.js — delegate to decorator
- Do NOT use lastVnode — derive state from route params
- Do NOT add state variables not in the plan
- Do NOT skip parent-based navigateUp — this was a critical Ux75 regression
- Do NOT make carousel a gridMode — it's a separate boolean
```

### Deliverable:
- `views/list.js` — ~550 lines, pure controller

---

## Phase 3: Fix Router + Pagination Performance

### Filled Prompt Template

```
TASK: Ux752 Phase 3 — Fix Router + Pagination Performance

CONTEXT:
- Phase 1+2 COMPLETE: decorator.js rebuilt, list.js rebuilt as pure controller
- Firefox performance is the primary target of this phase

REFERENCE FILES TO READ BEFORE WRITING ANY CODE:
- AccountManagerUx752/src/router.js — current router (270 lines)
- AccountManagerUx752/src/components/pagination.js — current pagination (469 lines)
- AccountManagerUx7/client/components/pagination.js — Ux7 pagination for reference

FILES TO MODIFY:
- AccountManagerUx752/src/router.js — MODIFY (~30 lines changed)
- AccountManagerUx752/src/components/pagination.js — MODIFY (~15 lines changed)

WHAT THIS PHASE DOES:
Fix two performance problems: (1) router re-renders navigation/toast/dialog on every
list redraw, (2) pagination fires two m.redraw() calls per data load (count + list).

SPECIFIC REQUIREMENTS:
1. Router: Add onbeforeupdate to navigation component to skip re-render when
   nav state hasn't changed
2. Router: Add onbeforeupdate to contextMenuComponent, toast, dialog stack
3. Pagination: Remove m.redraw() from handleCount() — let chain complete
   to handleList() which does the single redraw
4. List breadcrumb: batch segment resolution — single m.redraw() after all
   segments resolve (pending counter pattern)
5. Decorator: add render memoization to tabularView and gridListView —
   cache last render, only rebuild when data/state changes

ACCEPTANCE CRITERIA:
1. `npx vite build` — no errors
2. `npx vitest run` — all pass
3. Loading a list page triggers exactly ONE m.redraw() per data load
   (verify via console.log counter temporarily)
4. Breadcrumb with 5 segments triggers ONE m.redraw() (not 5)
5. Toggling a toolbar button does NOT re-render the navigation component
6. No console errors in Chrome or Firefox

ANTI-PATTERNS TO AVOID:
- Do NOT remove m.redraw() from handleList() — that's the one we keep
- Do NOT break the pagination chain (count → search → display)
- Do NOT over-cache — memoization keys must include all state that affects render
```

### Deliverable:
- `router.js` — ~30 lines changed
- `pagination.js` — ~15 lines changed
- Render memoization in decorator.js — ~40 lines added

---

## Phase 4: Playwright Tests That Actually Test

### Filled Prompt Template

```
TASK: Ux752 Phase 4 — Rewrite Playwright Tests

CONTEXT:
- Phases 1-3 COMPLETE: decorator, list, router, pagination all rebuilt/fixed
- All backend services LIVE at localhost:8443

REFERENCE FILES TO READ BEFORE WRITING ANY CODE:
- AccountManagerUx752/e2e/helpers/api.js — setupTestUser, ensureSharedTestUser
- AccountManagerUx752/e2e/helpers/auth.js — login helper
- AccountManagerUx752/e2e/helpers/fixtures.js — console capture fixture
- AccountManagerUx752/e2e/list.spec.js — existing list tests (keep these too)

FILES TO MODIFY:
- AccountManagerUx752/e2e/listControl.spec.js — REWRITE
- AccountManagerUx752/src/test/phase11b.test.js — UPDATE

WHAT THIS PHASE DOES:
Write Playwright tests that exercise ACTUAL FUNCTIONALITY against the live backend.
Every test must verify a real user-visible behavior, not just check DOM presence.

SPECIFIC REQUIREMENTS:
1. All tests use setupTestUser() / ensureSharedTestUser(), NEVER admin
2. Test: Navigate group hierarchy (home → child → UP → back)
3. Test: Navigate into non-group item → carousel opens (not error)
4. Test: Carousel item navigation — ArrowRight changes ITEM INDEX
5. Test: Grid mode cycling — table → small → large → table
6. Test: Column sort — click header, verify reorder
7. Test: Filter — type + Enter, verify filtered results
8. Test: Container mode toggle — verify group items shown
9. Test: Delete — select, delete, confirm, verify removed
10. Test: Media preview — image in carousel shows <img>
11. Test: Breadcrumb — visible, segments clickable
12. Test: Pagination — >10 items shows page controls
13. Run in BOTH Chromium and Firefox
14. Update phase11b.test.js for new decorator + list function signatures

ACCEPTANCE CRITERIA:
1. `npx vite build` — no errors
2. `npx vitest run` — all pass
3. `npx playwright test e2e/listControl.spec.js --project=chromium` — all pass
4. `npx playwright test e2e/listControl.spec.js --project=firefox` — all pass
5. `npx playwright test e2e/list.spec.js --project=chromium` — all existing pass
6. Every test exercises real functionality (not just DOM presence checks)
7. No test uses admin user

ANTI-PATTERNS TO AVOID:
- Do NOT write tests that only check element existence — test behavior
- Do NOT use admin user
- Do NOT claim tests pass without running them
- Do NOT skip Firefox
```

### Deliverable:
- `e2e/listControl.spec.js` — ~350 lines, 12+ tests
- `src/test/phase11b.test.js` — updated for new APIs

---

## Phase 5: Manual Verification + Firefox Profiling

### Filled Prompt Template

```
TASK: Ux752 Phase 5 — Manual Verification + Firefox Profiling

CONTEXT:
- Phases 1-4 COMPLETE: all code rebuilt, all tests passing

WHAT THIS PHASE DOES:
Manual browser verification of every feature in both Chrome and Firefox.
Firefox DevTools performance profiling to verify no frame drops.

SPECIFIC REQUIREMENTS:
1. Open application in Chrome, navigate to list view
2. Verify: group navigation up/down/breadcrumb works
3. Verify: carousel opens, arrow keys navigate items
4. Verify: grid modes cycle correctly
5. Verify: sort, filter, delete, container mode work
6. Verify: no console errors
7. Repeat ALL of the above in Firefox
8. Firefox DevTools Performance tab: record while browsing list
9. Verify: no frames exceed 16ms (60fps target)
10. Verify: no cascade redraws visible in flame chart

ACCEPTANCE CRITERIA:
- All features work identically in Chrome and Firefox
- Firefox frame time < 16ms during normal list operations
- No console errors in either browser
- Stephen approves after reviewing screenshots/video

ANTI-PATTERNS TO AVOID:
- Do NOT skip Firefox — it is the primary performance target
- Do NOT claim "verified" without actually opening the browser
```

---

## Execution Order

| Phase | Depends On | Deliverable | Est. Lines |
|-------|-----------|-------------|------------|
| 1. Decorator rebuild | Nothing | decorator.js (~450 lines) | 450 new |
| 2. List.js rebuild | Phase 1 | list.js (~550 lines) | 550 new |
| 3. Router + pagination perf | Phase 2 | router.js, pagination.js changes | ~85 changed |
| 4. Playwright tests | Phase 3 | listControl.spec.js, phase11b.test.js | ~400 new |
| 5. Manual verification | Phase 4 | Screenshots, Firefox profile | 0 code |

**Total new/changed code: ~1500 lines across 6 files.**
**Untouched code: ~64,500 lines across 130+ files.**

---

## File-Level Change Register

| File | Action | Before | After | Notes |
|------|--------|--------|-------|-------|
| `src/components/decorator.js` | REWRITE | 136 | ~450 | Full rendering module ported from Ux7 |
| `src/views/list.js` | REWRITE | 1696 | ~550 | Pure controller, no rendering |
| `src/router.js` | MODIFY | 270 | ~300 | onbeforeupdate for nav/chrome perf |
| `src/components/pagination.js` | MODIFY | 469 | ~475 | Remove extra m.redraw() from handleCount |
| `src/main.js` | MODIFY | ~80 | ~81 | Wire decorator to page.components |
| `e2e/listControl.spec.js` | REWRITE | 490 | ~350 | Tests that exercise real functionality |
| `src/test/phase11b.test.js` | MODIFY | 116 | ~160 | Updated for new decorator + list APIs |

**Net code reduction: ~1100 lines removed** (1696+136+490 = 2322 before → ~1550+350+160 = ~1210 after, in the changed files)
