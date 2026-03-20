# Open Issues — Bug Fix Sprint (2026-03-17)

## Issue #10: Chat Prompt Config Selector
**Status:** Complete
- [x] Picker fields for Chat Config, Prompt Config, Prompt Template
- [x] Picker field layout: text label + separate right-aligned icon-only buttons
- [x] Picker default group = model default path (`~/Chat`)
- [x] openLibrary passes userContainerId, libraryContainerId, favoritesContainerId to `open()`
- [x] `open()` uses opts values instead of ignoring them when containerId is provided
- [x] Admin/library button (`admin_panel_settings`) in toolbar — both modes
- [x] Favorites button in toolbar — both modes
- [x] Playwright test: verify system library, favorites, home buttons in picker — PASSING (admin user, wizard dismiss)
- [x] End-to-end test: create session via dialog against live backend

## Issue #11: Chat — Auto-start conversation for system-start sessions
**Status:** Complete
- [x] When creating a new chat where `startMode === "system"` and conversation has no messages, send empty string via `doAutoStart()` to trigger server-side conversation start
- Auto-start fires after `doPeek()` completes in `pickSession`

## Issue #12: Chat portraits — Ux7 style
**Status:** Complete
- [x] Portrait thumbnails now use 96x96 (matching Ux7)
- [x] Gender-based fallback icons: `man`/`woman` (material-icons-outlined), matching Ux7
- [x] Both user and system character portraits use same rendering logic
- [x] Fallback to `smart_toy` icon only when no character data available

## Issue #13: Chat auto-icons — emoji rendering
**Status:** Complete
- [x] Session list icons no longer use `material-symbols-outlined` class
- [x] Icons render as plain text (works for emojis, text, or any character)
- [x] Metadata icon preview also updated
- [x] Placeholder text changed from "(material icon name)" to "(emoji)"

## Issue #14: Memory construction problems / Ollama infinite loops
**Status:** Investigation complete, fixes applied (2026-03-18)
- [x] Full investigation plan: `aiDocs/MemoryOllamaInvestigation.md`
- [x] **Phase 1**: TestMemExtract passes (8/8), TestMemoryPhase2 passes (11/11), TestKeyframeMemory 2 failures (2/4)
- [x] **Phase 3 Root cause**: TestKeyframeMemory failures caused by model `qwen3-coder:30b` — a code-focused model that doesn't produce valid JSON for memory extraction. `analyzeModel` is null so extraction uses the main model. Not a code bug — model appropriateness issue.
- [x] **Existing safeguards verified**: 120s `analyzeTimeout` on extraction calls, no retry loop, `memoryExtractionMaxPerSegment=1` default, V2 prompt used by default, JSON parsing returns empty list on failure
- [x] **Fix 1**: Improved extraction failure logging — logs first 500 chars of failed response (up from 200), full response at DEBUG level for diagnosis
- [x] **Fix 2**: Added total conversation memory cap (100 default, configurable via `maxConversationMemories` on chatConfig) — prevents unbounded memory growth
- [x] **Recommendation**: Configure `analyzeModel` on chatConfig to use an instruction-following model (e.g., `qwen3:8b` or `llama3.1:8b-instruct`) instead of the code model for extraction

## Issue #15: Aside menu — clearCache and cleanup buttons
**Status:** Complete
- [x] "System" section added to aside menu with Clear Cache and Cleanup buttons
- [x] Clear Cache calls `am7client.clearCache(0, false)` (clears both client and server cache)
- [x] Cleanup calls `am7client.cleanup()` (server-side orphan cleanup)
- [x] Toast confirmation on both actions

## Issue #16: Chat config info in sidebar
**Status:** Complete
- [x] `ChatConfigToolbar` removed from main chat area
- [x] Config info now in sidebar accordion (conversations panel, bottom)
- [x] Accordion shows: Chat Config name, Prompt name, Model, Rating
- [x] Config names are clickable — opens ObjectPicker to change config
- [x] Expand/collapse toggle with arrow icon

## CRITICAL BUG: ChatTokenRenderer TypeError
**Status:** Fixed (2026-03-17)
- `escapeHtmlAttr` now converts non-string values to String before calling `.replace()`
- `processImageTokens` and `processAudioTokens` guard against non-string content
- `processContent` ensures content is string before processing pipeline

## BUG: Delete session UI artifacts + toolbar title mismatch
**Status:** Fixed (2026-03-17)
- `onDelete` handler now always calls `doClear()` + `autoSelectFirst()` unconditionally
- Toolbar reads `ConversationManager.getSelectedSession()` for `chatTitle` + `chatIcon`, matching sidebar display

## Issue #17: Firefox slowness
**Status:** Fixed (2026-03-17)
- [x] Profiled bottlenecks: streaming redraws per-token, synchronous scrollToBottom layout thrashing, serial async API calls in ConversationManager.load()
- [x] Debounced streaming redraws via `requestAnimationFrame` (max ~30fps)
- [x] Debounced `scrollToBottom` via RAF to prevent synchronous layout recalculation
- [x] Parallelized `ConversationManager.loadSessions()` — directory lookups + config/session loads run concurrently via `Promise.all`

## Issue #18: Picker field label onclick broken
**Status:** Fixed (2026-03-17)
- [x] Made entire config row clickable (icon + label + value) — extracted onclick into shared `openConfigPicker` function
- [x] Added hover state and cursor-pointer to row

## Issue #19: Auto-start submit missing pending animation
**Status:** Fixed (2026-03-17)
- [x] Added `requestAnimationFrame` yield between `chatCfg.pending = true` + `m.redraw()` and the `streamChat` call, ensuring pending indicator renders before stream connects

## Issue #20: Unauthorized stop chat button changes
**Status:** Closed (2026-03-17) — non-issue
- [x] Compared current stop button code (lines 678-685) against pre-sprint (HEAD~5) — identical, no unauthorized changes

## Issue #21: Chat display — use character name instead of "You"
**Status:** Fixed (2026-03-17)
- [x] Both edit mode and normal mode labels now show `chatCfg.user.name` when a user character is assigned, falling back to "You" when not

## Issue #22: Chat portrait images not matching Ux7
**Status:** Fixed (2026-03-17)
- [x] Added `onclick: page.imageView(pp)` to portrait images (matching Ux7)
- [x] Added margin classes matching Ux7 (`mr-2` for system, `ml-2` for user) instead of generic `w-8 h-8`
- [x] Added Ux7-style portrait header bar above messages (system left, user right with names)
- [x] Gender fallback icons use `material-icons-outlined` (matching Ux7)

## Issue #23: Image and audio tokens not implemented
**Status:** Fixed (2026-03-17)
- [x] Fixed token processing pipeline: `formatContent` (HTML-escapes markdown) now runs BEFORE token processing (which inserts `<img>`/`<button>` HTML)
- [x] `renderMessage` now passes `characters`, `sessionId`, `msgRole`, `msgIndex` to `processImageTokens`/`processAudioTokens`
- [x] Image tokens resolve via `am7imageTokens` library (objectId lookup → thumbnail URL → `<img>`)
- [x] Audio tokens resolve via `am7audioTokens` library (text → play button with click delegation)
- [x] Same fix applied to `renderStreamingMessage`

## Issue #24: Editing chat message doesn't persist changes
**Status:** Fixed (2026-03-18)
- [x] Root cause: `saveEdits()` read textarea values and updated local state but never patched the server, then immediately overwrote local edits by calling `getHistory()` from server
- [x] Fix: Ported Ux7 `saveEditsAndDeletes()` pattern — fetch full server session via uncached query, calculate offset for system/template messages, apply textarea edits to server messages, remove deleted messages, patch server, clear cache, re-peek

## Issue #25: Image tokens don't trigger image generation
**Status:** Fixed (2026-03-18)
- [x] Full image token pipeline ported from Ux7 to `imageTokens.js`:
  - `parse()` — enhanced with UUID detection for `${image.UUID.tags}` and `${image.UUID}` formats
  - `findImageForTags()` — character name tag + image tag intersection search via `am7client.members()`
  - `generateImageForTags()` — calls `/olio/{type}/{charId}/reimage` with SD config, wear level targeting, tag application, portrait restoration
  - `resolve()` — UUID lookup → find-by-tags → generate pipeline with caching
- [x] Added `patchChatImageToken()` and `resolveChatImages()` in chat.js — one-at-a-time token resolution with server-side patching
- [x] Wired `resolveChatImages` as `onResolve` callback in `renderMessage` (streaming messages skip resolution)
- [x] Dependencies resolved: `getOrCreateSharingTag` from `workflows/reimage.js`, `am7model._sd.fetchTemplate()` via `components/sdConfig.js`, tag lookup via `am7client.newQuery("data.tag")`

## Issue #30: Audio tokens don't render or work
**Status:** Fixed (2026-03-18)
- [x] Root cause: `ChatTokenRenderer.processAudioTokens` generated buttons with `data-audio-id` but **missing `data-audio-text`** attribute. The click delegation in `audioTokens.js` requires BOTH attributes (`if (btnId && text) play(btnId, text)`) — clicks silently failed because `text` was null.
- [x] Fix: Added `data-audio-text` attribute to buttons in `ChatTokenRenderer.processAudioTokens`
- [x] Click delegation in `audioTokens.js` already correctly handles `[data-audio-id]` + `[data-audio-text]` with `play(btnId, text)`

## Issue #29: Auto-start pending animation STILL not showing
**Status:** Fixed (2026-03-18)
- [x] Root cause: `renderPendingIndicator()` checked `!chatCfg.pending || chatCfg.streaming` — when `onchatstart` fired immediately, `streaming` was set to true, hiding the pending indicator. But `renderStreamingMessage` only shows when `streamText` is non-empty. Gap between streaming=true and first token = nothing visible.
- [x] Fix: Changed `renderPendingIndicator()` to show when `pending=true` AND no stream text has arrived yet, regardless of streaming state. Once `streamText` is non-empty, `renderStreamingMessage` takes over.
- [x] Also ensured `onchatcomplete` and `onchaterror` clear `pending` flag

## Issue #28: Picker field label click STILL doesn't open list view
**Status:** Fixed (2026-03-18)
- [x] Root cause identified: The conversations panel wrapper had `overflow-hidden` which clipped the config accordion when `ConversationManager.SidebarView` used `flex-1` to take all available space, pushing the config accordion out of view
- [x] Fix: Changed panel layout to `flex flex-col h-full` with the SidebarView in a `flex-1 overflow-y-auto min-h-0` container, keeping the config accordion always visible at the bottom
- [x] Also added `e.stopPropagation()` to the click handler and error logging with `.catch()` on `ObjectPicker.openLibrary` so failures are surfaced instead of swallowed
- [ ] **MUST verify in browser** — this fix addresses the layout clipping issue but browser testing is required

## Issue #27: Firefox performance still abysmal
**Status:** Fixed (2026-03-18)
- [x] Root cause: Every `m.redraw()` re-ran `renderMessage()` for ALL messages, executing 10+ regex operations per message (pruning, formatting, image/audio token processing). With 50+ messages = 500+ regex ops per redraw. Firefox's regex engine is significantly slower than Chrome's V8.
- [x] Fix: Added `_formattedCache` memoization Map — caches formatted HTML output keyed by `role:idx:content`. Only recomputes when message content changes. Cache cleared on history reload, edit mode toggle, session change.
- [x] No `transition-all` CSS found in codebase — confirmed not a factor
- [ ] **Virtualization** for very long histories (100+ messages) is still a potential improvement but not blocking
- [ ] **MUST profile in Firefox DevTools** after this fix to verify improvement

## Issue #26: SD config forms inconsistent and incomplete across features
**Status:** Fixed (2026-03-18)
- [x] Created `components/SdConfigPanel.js` — shared Mithril component rendering ALL SD config fields: model, refinerModel, refinerSteps, refinerCfg, style, steps, cfg, denoisingStrength, sampler, scheduler, width, height, seed, hires, bodyStyle, imageAction, imageSetting
- [x] Updated `SceneGenerator.js` to use `SdConfigPanel` instead of its own inline field rendering. Also aligned field names (`cfgScale`→`cfg`, added missing fields)
- [x] `SdConfigPanel` supports `compact` mode (fewer fields) for constrained UI contexts
- [x] `pictureBook` uses `DEFAULT_SD_CONFIG` from `sceneExtractor.js` (no form rendering needed — uses defaults)
- [x] Reimage workflow uses `am7model._sd` + formDef system (already consistent via `forms.sdConfig`)

## Issue #32: Picker fields render as text inputs instead of labels
**Status:** Fixed (2026-03-18)
- [x] New session dialog picker fields (Chat Config, Prompt Config, Prompt Template) had text-input styling (border, background). Changed to plain label with cursor-pointer and blue hover. Clicking label opens picker same as search icon.

## Issue #33: No page toasts for Clear Cache and Cleanup
**Status:** Fixed (2026-03-18)
- [x] Root cause: Aside drawer stayed open when buttons clicked — toast rendered behind overlay or was visually obscured
- [x] Fix: Both "Clear Cache" and "Cleanup" onclick handlers now close the aside drawer (`page.navigable.drawer(true)`) before showing the toast
- [x] Added try/catch around `clearCache(0, false)` to prevent silent failures from blocking the toast
- [x] Toast z-index (z-50) confirmed higher than aside (z-20) — no CSS issue
- [x] 8 Vitest tests added in `bugfix3.test.js` covering source verification

## Issue #34: List Control — Incomplete Ux7 Port (2026-03-20)
**Status:** OPEN — Analysis complete, fixes NOT started

### 34a: Firefox performance — list view specifically
**Severity:** HIGH
**Root causes identified:**
1. **32+ `m.redraw()` calls** in list.js — cascade redraws from async operations + pagination double-redraws. Example chain: `doSearch()` → `pagination.new()` → `m.redraw()` → `initParams()` → `updatePagination()` → `pagination.update()` → `doCount()` → `handleCount()` → `m.redraw()` — at least 2 redraws for one search action
2. **Breadcrumb lazy-loading loop** — `buildBreadcrumbFromPath()` calls `am7client.find()` per segment, each callback calls `m.redraw()` individually. 5-segment path = 5 rapid redraws
3. **Pagination double-redraws** — `handleCount()` calls `m.redraw()`, then `doSearchPage()` → `updatePage()` → `handleList()` → `m.redraw()`. Two redraws per data load
4. **No render memoization** — `displayList()` rebuilds entire table/grid DOM on every redraw. 100+ tr/td nodes recreated every time
5. **Full-page re-render** — router wraps listControl.renderContent() in `layout(pageLayout(...))` which re-renders navigation, picker overlay, toast, dialog stack on every list redraw
6. **Dynamic event handler binding** — `onscroll: infiniteScroll && !carousel ? checkScrollPagination : undefined` forces Mithril to re-evaluate on every render

**Proposed fixes (priority order):**
- Batch pagination redraws: single redraw after both count + list APIs complete
- Memoize list/carousel renders: cache output, rebuild only when data changes
- Static scroll handler: bind once, check flags internally
- Extract navigation/picker to persistent views outside route renders
- Debounce breadcrumb redraws: batch segment updates into single redraw

### 34b: Group navigation broken — can't go up, can't go adjacent
**Severity:** HIGH
**Problems identified:**
1. **navigateUp() only handles `auth.group` type** — logs warning and does nothing for all other types. Ux7 has full parent-based navigation for `am7model.isParent()` types (data.directory, etc.) — Ux75 is completely missing this branch
2. **UP button goes only one level** — `lastIndexOf('/')` gives parent, not ancestors. Multi-level up requires repeated clicks
3. **No adjacent navigation** — can go into child groups but no sibling navigation
4. **Child groups mixed in** — shown as a separate section above results, which is better than Ux7, but navigating into them and then back up is broken because navigateUp doesn't handle all types
5. **Path resolution weaker than Ux7** — Ux7 uses `page.navigateToPath()` which validates type; Ux75 uses raw `page.findObject()` which silently fails on invalid paths

### 34c: Carousel navigation broken — navigates pages, not results
**Severity:** HIGH
**Root cause:** `moveCarousel()` calls `pagination.next(pickerMode)` when index exceeds page length, which loads a new page but **never resets `pg.currentItem` to 0**. The carousel viewer displays stale index on new page data. Same bug exists in `navListKey()` — when `carousel` is false OR shift is held, arrow keys call `pagination.next/prev()` (page navigation) instead of `moveCarousel()` (item navigation).
**Fix needed:** After `pagination.next()`, set `pg.currentItem = 0`. After `pagination.prev()`, `getCurrentResults()` already handles via `wentBack` flag but needs verification.

### 34d: Example media content for carousel testing
**Available test content:**
- **AccountManagerObjects7/media/**: CardFox.pdf (33pg), YoureFired.mp4, speaker_0.mp3, airship.jpg, anaconda.jpg (4000x3000), antikythera.jpg, railplane.png, shark.webp, steampunk.png, sunflower.jpg (4000x3000), CardFox.txt
- **AccountManagerObjects7/media/tiles/**: 26 PNG terrain tiles (512x512)
- **AccountManagerUx7/media/**: Logo PNGs, character model PNGs (male/female at 512/1000px), SVGs, game JSON configs
- These should be uploaded to a test group and used for carousel media preview verification

### 34e: Design Analysis — What Ux75 Added vs Ux7 (2026-03-20)

**Context:** Ux75 list.js is a complete rewrite from Ux7, not a port. This analysis catalogs every difference and honestly assesses whether each is an improvement, a regression, or unnecessary.

#### Genuinely Better Than Ux7

| Feature | What It Does | Why It's Better |
|---------|-------------|-----------------|
| **Breadcrumb navigation** | Clickable path segments above the list showing full group path. Each ancestor is clickable to navigate directly. | Ux7 had NO breadcrumb at all — users had to navigate up one level at a time or use the tree view. This is a real UX improvement for deep directory structures. |
| **Per-model column defaults** | `MODEL_COLUMNS` map with sensible defaults for auth.group, data.data, olio.charPerson, etc. Falls back to model's query fields. | Ux7 hardcoded a single `defaultHeaderMap` for ALL types: `["_rowNum", "_icon", "id", "name", "modifiedDate", "age", "gender", "_tags", "_favorite"]`. This showed age/gender columns on data.data rows, which makes no sense. Ux75's per-type defaults are objectively correct. |
| **Context menu (right-click)** | Right-click any item → Open, Edit, Delete. | Ux7 had no context menu. Users had to select items then find toolbar buttons. Adds discoverability. |
| **Non-group item navigation fix (Fix A)** | Double-clicking a data.data item opens carousel instead of treating it as a container. | Ux7 would route to `/list/data.data/{objectId}`, pagination would try to load it as `auth.group`, and fail with "Error loading container." This was a real bug in Ux7. |
| **Defensive null checks in pagination** | `getSearchQuery()` returns null if no resultType; `updatePage()` returns if no resultType. | Ux7 would throw unhandled errors on null types. |
| **Column provider API** | `pagination.setColumnProvider(fn)` lets list control tell pagination which extra fields to include in queries. | Ux7 used `window.am7decorator.map()` statically — couldn't vary per-list. |
| **Container lookup caching** | `containerCache` in pagination.js avoids re-querying the same group's numeric ID on every page change. | Ux7 re-queried every time. Genuine optimization for multi-page browsing. |

#### Regressions From Ux7

| Feature | What Was Lost | Impact |
|---------|--------------|--------|
| **Parent-based navigation in navigateUp()** | Ux7 had full `am7model.isParent()` branch: look up object, find parentId, navigate to parent. Ux75 removed this entirely — only handles `auth.group` type. | **HIGH** — any model with parent/child hierarchy (data.directory, data.note, etc.) cannot navigate up. |
| **Embedded mode** | Ux7 had `embeddedMode` + `embeddedController` for inline list views (e.g., member lists in object view). Ux75 removed it. | **MEDIUM** — embedded lists in object view broken. |
| **Drag-and-drop integration** | Ux7 had `dnd = page.components.dnd` with `dragProps()`, `doDragDecorate()`, `doDrop` on the container div. Ux75 has partial drag start but no drop handling and no drag decoration. | **MEDIUM** — can't drag items between groups. |
| **Tag batch progress dialog** | Ux7's `applyTagsToList()` used `page.components.dialog.batchProgress()` — a progress dialog showing each item being processed. Ux75 uses `Promise.allSettled()` with a toast — no progress visibility. | **LOW** — functional but less informative. |
| **Olio navigation button** | Ux7 had `getOlioButtons()` — a globe icon that navigated to `/Olio/Universes`. Ux75 removed it. | **LOW** — niche feature, but was useful for olio users. |
| **Cloud/tag cloud button** | Ux7 had `openCloud()` for data.tag, auth.role, auth.group types. Ux75 removed it. | **LOW** — niche feature. |
| **System library auto-creation** | Ux7's `openSystemLibrary()` could auto-create chat/prompt/policy libraries via `LLMConnector.initPromptLibrary()` etc. Ux75 only navigates to existing libraries. | **LOW** — first-time setup path missing. |
| **Infinite scroll pagination** | Ux7 concatenated pages in `displayList()` and had `checkScrollPagination()` on the result list. Ux75 has the code but it's less tested and the scroll handler uses a conditional binding pattern that hurts Firefox. | **MEDIUM** — feature exists but is fragile. |

#### Unnecessary Additions (Not In Ux7, Not Needed)

| Feature | What It Does | Why It's Unnecessary |
|---------|-------------|---------------------|
| **Column customization via localStorage** | Users can toggle individual columns on/off, persisted in localStorage. | Users almost never customize columns. This adds `columnConfigCache`, `columnPickerOpen`, `getColumnConfigKey()`, `getCustomColumns()`, `saveCustomColumns()`, `renderColumnPicker()` — ~80 lines of code and a toolbar button for a feature with near-zero usage. Ux7 had none of this and nobody missed it. |
| **Separate child group loading** | `loadChildGroups()` makes a separate API call to find child groups, stores them in `childGroups`, renders them as a section above results. | Ux7 didn't show child groups separately — you navigated via the tree or container mode. The child group display is visually nice but adds 50+ lines of code, a separate API call per navigation, and a loading flag (`childGroupsLoading`) that duplicates pagination's `requesting`. |
| **Live debounced search** | `doSearch()` with 300ms debounce timer, separate `searchQuery`/`searchTimer`/`searchActive` state. | Ux7 already had `doFilter()` with a text input and Enter key. The live search adds 3 state variables, a timer, and a debounce mechanism for marginal UX improvement (users can type and wait instead of typing and pressing Enter). The debounce itself triggers cascade redraws that hurt Firefox. |
| **Select All button** | Toolbar button to check/uncheck all items on current page. | Ux7 had a clickable "#" header cell that selected all. Ux75's separate toolbar button is redundant. |
| **Infinite scroll toggle button** | Toolbar button to switch between pagination and infinite scroll. | Niche feature. Ux7 had `infiniteScroll` internally but no UI toggle — it was set programmatically. Adding a toolbar button clutters the toolbar for a feature most users don't want. |

#### Just Different (No Clear Advantage Either Way)

| Feature | Ux7 Approach | Ux75 Approach | Verdict |
|---------|-------------|---------------|---------|
| **Button factory** | `pagination.button(class, icon, label, handler)` via pagination component | `iconBtn(class, icon, label, handler, ariaLabel)` local to list.js | Ux75 adds aria-labels (accessibility win), but duplicates what pagination already provides. Wash. |
| **Picker mode state** | Single `pickerMode` boolean + `pickerHandler` + `pickerCancel` callbacks | 6 variables: `pickerHandler`, `pickerType`, `pickerContainerId`, `pickerUserContainerId`, `pickerLibraryContainerId`, `pickerFavoritesContainerId`, `pickerActiveSource` | Ux75 supports more picker sources (home/library/favorites) but at massive state cost. Could be a single config object instead. |
| **ES6 modules vs IIFE** | Global IIFE, `page.views.list = newListControl` | `export { newListControl }`, imported by router | Ux75 follows modern JS conventions. Neither is faster. |
| **Route-based pagination** | `m.route.set(pagination.url(...))` to change page | Same, but also has `initParams(lastVnode)` + `updatePagination(lastVnode)` re-initialization | Ux75's `lastVnode` pattern is a code smell — it means state can't be derived from the route alone. |

#### Design Flaws Unique to Ux75

1. **`lastVnode` global** — storing a reference to the last Mithril vnode and using it to re-initialize state from toolbar button handlers. This breaks Mithril's unidirectional data flow and means state synchronization depends on a stale reference. Ux7 didn't need this because it derived state from route params.

2. **Picker mode state explosion** — 6 variables where Ux7 had 3. The `pickerActiveSource` string ('home'|'library'|'favorites') with separate container ID per source is fragile. A single `pickerConfig` object would be cleaner.

3. **Incomplete decorator refactor** — Ux75 has `components/decorator.js` with 3 basic functions, but list.js doesn't use it for rendering. The file exists but isn't wired up. Meanwhile, `page.components.decorator` is expected by pagination.js. Architectural confusion.

4. **Rendering mixed into controller** — Ux7 cleanly separated list.js (state/controller) from decorator.js (rendering). Ux75 puts ALL rendering (table, grid, gallery, carousel, breadcrumb, child groups) into list.js — making it a 1300+ line monolith that's hard to maintain.

#### Summary Verdict

**Genuinely better than Ux7:** Breadcrumb, per-model columns, context menu, non-group navigation fix, defensive null checks, container cache, column provider API.

**Regressions from Ux7:** Parent navigation, embedded mode, drag-drop, batch progress dialog, olio/cloud buttons, library auto-creation.

**Unnecessary additions:** Column localStorage, child group display, live search (vs existing filter), select-all button, infinite scroll toggle.

**The honest overall assessment:** Ux75 should have been a surgical enhancement to Ux7 — adding breadcrumb, per-model columns, and context menu while preserving everything else. Instead, it was a ground-up rewrite that lost functionality, added unnecessary features, created performance problems, and inflated the codebase from ~870 lines (Ux7 list.js + decorator.js combined) to ~1300 lines (Ux75 list.js alone) while still being incomplete.

---

## Issue #31: 'Not Authenticated' error should redirect to login then back
**Status:** Fixed (2026-03-18)
- [x] Added `_handle401(e, url)` function in `am7client.js` — detects `e.code === 401`, saves current route to `sessionStorage("am7.returnRoute")`, redirects to `/sig`
- [x] `_handle401` called in catch handlers of all 4 HTTP methods: `get()`, `post()`, `patch()`, `del()`
- [x] `patch()` previously had no `.catch()` handler — added one with 401 check + error logging
- [x] `router.js` `refreshApplication()` now reads `am7.returnRoute` from sessionStorage on startup — if present, uses it as the target route after login instead of `/main`
- [x] Works for both password login and passkey login (both call `page.router.refresh()` → `refreshApplication()`)
- [x] 8 Vitest tests in `bugfix3.test.js` verify source-level presence of 401 handling
