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

## Issue #31: 'Not Authenticated' error should redirect to login then back
**Status:** Fixed (2026-03-18)
- [x] Added `_handle401(e, url)` function in `am7client.js` — detects `e.code === 401`, saves current route to `sessionStorage("am7.returnRoute")`, redirects to `/sig`
- [x] `_handle401` called in catch handlers of all 4 HTTP methods: `get()`, `post()`, `patch()`, `del()`
- [x] `patch()` previously had no `.catch()` handler — added one with 401 check + error logging
- [x] `router.js` `refreshApplication()` now reads `am7.returnRoute` from sessionStorage on startup — if present, uses it as the target route after login instead of `/main`
- [x] Works for both password login and passkey login (both call `page.router.refresh()` → `refreshApplication()`)
- [x] 8 Vitest tests in `bugfix3.test.js` verify source-level presence of 401 handling
