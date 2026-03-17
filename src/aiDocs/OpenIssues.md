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
**Status:** Investigation plan complete
- [x] Full investigation plan: `aiDocs/MemoryOllamaInvestigation.md`
- [ ] Execute Phase 1: Run existing unit tests (TestMemExtract, TestMemoryPhase2, TestKeyframeMemory)
- [ ] Execute Phase 2: Verify single-worker blocking behavior
- [ ] Execute Phase 3: Root cause analysis (model appropriateness, JSON parsing, extraction limits)
- [ ] Execute Phase 4: Deduplication tuning
- [ ] Execute Phase 5: Write targeted unit tests
- [ ] Execute Phase 6: Apply fixes

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
